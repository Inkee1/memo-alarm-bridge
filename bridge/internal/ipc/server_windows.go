package ipc

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
	"io"
	"net"
	"net/http"
	"path/filepath"
	"strings"
	"time"
)

func pipeIdentity(dataDir string) (string, string, error) {
	u, e := windows.GetCurrentProcessToken().GetTokenUser()
	if e != nil {
		return "", "", e
	}
	sid := u.User.Sid.String()
	abs, e := filepath.Abs(dataDir)
	if e != nil {
		return "", "", e
	}
	sum := sha256.Sum256([]byte(strings.ToLower(filepath.Clean(abs))))
	return `\\.\pipe\MemoAlarmBridge.` + sid + "." + hex.EncodeToString(sum[:8]), sid, nil
}
func Listen(dataDir string) (net.Listener, error) {
	name, sid, e := pipeIdentity(dataDir)
	if e != nil {
		return nil, e
	}
	return winio.ListenPipe(name, &winio.PipeConfig{SecurityDescriptor: "D:P(A;;GA;;;" + sid + ")", InputBufferSize: 65536, OutputBufferSize: 65536})
}
func ServeLocal(ctx context.Context, l net.Listener, s *app.Service) error {
	server := &http.Server{ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 4096}
	server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method != "POST" || r.URL.Path != "/v1/command" {
			w.WriteHeader(404)
			json.NewEncoder(w).Encode(app.Failure("not_found", "unknown IPC route"))
			return
		}
		body, e := io.ReadAll(http.MaxBytesReader(w, r.Body, app.MaxCommandBytes))
		var c app.Command
		if e != nil {
			json.NewEncoder(w).Encode(app.Failure("invalid_input", "command too large"))
			return
		}
		if e = domain.DecodeStrict(body, &c); e != nil {
			json.NewEncoder(w).Encode(app.Failure("invalid_input", e.Error()))
			return
		}
		json.NewEncoder(w).Encode(s.Dispatch(r.Context(), c))
	})
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			server.Close()
		case <-done:
		}
	}()
	err := server.Serve(l)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}
