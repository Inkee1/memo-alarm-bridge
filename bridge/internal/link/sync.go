package link

import (
	"errors"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"io"
	"net/http"
	"os"
	"strings"
)

type SyncRequest struct {
	SchemaVersion int                `json:"schema_version"`
	Cursor        int64              `json:"cursor"`
	Operations    []domain.Mutation  `json:"operations"`
	Acks          []domain.DeviceAck `json:"acks"`
}
type SyncResponse struct {
	SchemaVersion int                     `json:"schema_version"`
	Cursor        int64                   `json:"cursor"`
	More          bool                    `json:"more"`
	Results       []domain.MutationResult `json:"results"`
	Changes       []store.Change          `json:"changes"`
}

func NewSyncHandler(s *store.Store) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		d, ok := DeviceFromContext(r.Context())
		if !ok {
			http.Error(w, "device authentication required", 401)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/v1/blob/") {
			hash := strings.TrimPrefix(r.URL.Path, "/v1/blob/")
			if !domain.ValidHash(hash) {
				http.Error(w, "invalid audio hash", 400)
				return
			}
			switch r.Method {
			case "PUT":
				ref, err := s.PutBlob(r.Context(), http.MaxBytesReader(w, r.Body, domain.MaxBlobBytes+1), hash)
				if err != nil {
					http.Error(w, "audio is incomplete, too large or has a different hash", 400)
					return
				}
				writeJSON(w, ref)
			case "GET":
				path, err := s.BlobPath(r.Context(), hash)
				if err != nil {
					http.NotFound(w, r)
					return
				}
				file, err := os.Open(path)
				if err != nil {
					http.Error(w, "audio unavailable", 500)
					return
				}
				defer file.Close()
				info, err := file.Stat()
				if err != nil {
					http.Error(w, "audio unavailable", 500)
					return
				}
				w.Header().Set("Content-Type", "application/octet-stream")
				http.ServeContent(w, r, hash, info.ModTime(), file)
			default:
				http.Error(w, "GET or PUT required", 405)
			}
			return
		}
		if r.URL.Path != "/v1/sync" {
			http.NotFound(w, r)
			return
		}
		if r.Method != "POST" {
			http.Error(w, "POST required", 405)
			return
		}
		if strings.Split(r.Header.Get("Content-Type"), ";")[0] != "application/json" {
			http.Error(w, "JSON required", 415)
			return
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4*1024*1024))
		var q SyncRequest
		if err != nil || domain.DecodeStrict(body, &q) != nil || q.SchemaVersion != 1 || q.Cursor < 0 || len(q.Operations) > 16 || len(q.Acks) > 100 {
			http.Error(w, "invalid or oversized sync request", 400)
			return
		}
		if _, _, _, err = s.Changes(r.Context(), q.Cursor, 1); err != nil {
			if errors.Is(err, store.ErrFutureCursor) {
				http.Error(w, err.Error(), 409)
			} else {
				http.Error(w, "server storage unavailable", 500)
			}
			return
		}
		ids := map[string]bool{}
		for _, m := range q.Operations {
			if domain.ValidateMutation(m) != nil || ids[m.OperationID] {
				http.Error(w, "invalid or duplicate operation", 400)
				return
			}
			ids[m.OperationID] = true
		}
		for _, a := range q.Acks {
			if store.ValidateAck(d.DeviceID, a) != nil {
				http.Error(w, "invalid device acknowledgement", 400)
				return
			}
		}
		out := SyncResponse{SchemaVersion: 1, Results: []domain.MutationResult{}}
		for _, m := range q.Operations {
			result, e := s.Apply(r.Context(), m)
			if e != nil {
				switch {
				case errors.Is(e, store.ErrConflict):
				case errors.Is(e, store.ErrCancelled):
					result.Status = "cancelled"
					result.Error = "The old alarm epoch was cancelled. Choose a new epoch to enable it."
				default:
					result.Status = "rejected"
					result.Error = "Operation rejected. Check revision, audio upload and active alarm limit."
				}
			}
			out.Results = append(out.Results, result)
		}
		for _, a := range q.Acks {
			if err = s.SaveAck(r.Context(), d.DeviceID, a); err != nil {
				http.Error(w, "acknowledgement could not be saved; retry unchanged request", 409)
				return
			}
		}
		out.Changes, out.Cursor, out.More, err = s.Changes(r.Context(), q.Cursor, 16)
		if err != nil {
			http.Error(w, "server storage unavailable; retry unchanged request", 500)
			return
		}
		if err = s.TouchDevice(r.Context(), d.DeviceID); err != nil {
			http.Error(w, "device unavailable", 401)
			return
		}
		writeJSON(w, out)
	})
}
