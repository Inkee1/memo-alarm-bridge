package web

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"embed"
	"encoding/hex"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"io"
	"io/fs"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

//go:embed assets/index.html assets/app.js assets/state.mjs assets/audio.mjs assets/style.css assets/setup.html assets/setup.js
var assets embed.FS

type localSession struct {
	csrf    string
	expires time.Time
}
type Server struct {
	Host      string
	service   *app.Service
	mu        sync.Mutex
	bootstrap string
	sessions  map[string]localSession
	files     http.Handler
	dispatch  func(context.Context, app.Command) app.CommandResult
	installer bool
}

func RandomToken() (string, error) {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}
func New(s *app.Service, bootstrap string) (*Server, error) {
	root, e := fs.Sub(assets, "assets")
	if e != nil {
		return nil, e
	}
	server := &Server{service: s, bootstrap: bootstrap, sessions: map[string]localSession{}, files: http.FileServer(http.FS(root))}
	if s != nil {
		server.dispatch = s.Dispatch
	}
	return server, nil
}
func NewInstaller(dispatch func(context.Context, app.Command) app.CommandResult, bootstrap string) (*Server, error) {
	s, e := New(nil, bootstrap)
	if e != nil {
		return nil, e
	}
	s.dispatch = dispatch
	s.installer = true
	return s, nil
}
func (s *Server) ApprovalURL() (string, error) {
	token, e := RandomToken()
	if e != nil {
		return "", e
	}
	s.mu.Lock()
	s.bootstrap = token
	s.mu.Unlock()
	return "http://" + s.Host + "/#bootstrap=" + token, nil
}
func equal(a, b string) bool {
	return len(a) > 0 && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Cache-Control", "no-store")
	host, _, e := net.SplitHostPort(s.Host)
	ip := net.ParseIP(host)
	if e != nil || ip == nil || !ip.IsLoopback() || r.Host != s.Host {
		http.Error(w, "host rejected", 403)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/api/") {
		if r.Header.Get("Origin") != "http://"+s.Host {
			http.Error(w, "origin rejected", 403)
			return
		}
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		if !strings.HasPrefix(r.URL.Path, "/api/audio/upload/") && strings.Split(r.Header.Get("Content-Type"), ";")[0] != "application/json" {
			http.Error(w, "JSON required", 415)
			return
		}
		if r.URL.Path == "/api/session" {
			s.session(w, r)
			return
		}
		sess, ok := s.authorize(r)
		if !ok || !equal(r.Header.Get("X-CSRF-Token"), sess.csrf) {
			http.Error(w, "session approval required", 403)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/audio/upload/") {
			if s.service == nil {
				http.NotFound(w, r)
				return
			}
			hash := strings.TrimPrefix(r.URL.Path, "/api/audio/upload/")
			ref, err := s.service.PutBlob(r.Context(), http.MaxBytesReader(w, r.Body, domain.MaxBlobBytes+1), hash)
			if err != nil {
				http.Error(w, "audio incomplete, too large or hash mismatch", 400)
				return
			}
			writeJSON(w, 200, ref)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/audio/read/") {
			if s.service == nil {
				http.NotFound(w, r)
				return
			}
			hash := strings.TrimPrefix(r.URL.Path, "/api/audio/read/")
			path, err := s.service.Store.BlobPath(r.Context(), hash)
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
			return
		}
		if r.URL.Path != "/api/command" {
			http.NotFound(w, r)
			return
		}
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, app.MaxCommandBytes))
		if err != nil {
			http.Error(w, "request too large", 413)
			return
		}
		var c app.Command
		if err = domain.DecodeStrict(body, &c); err != nil {
			writeJSON(w, 400, app.Failure("invalid_input", err.Error()))
			return
		}
		writeJSON(w, 200, s.dispatch(r.Context(), c))
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "GET required", 405)
		return
	}
	switch r.URL.Path {
	case "/":
		if s.installer {
			raw, _ := assets.ReadFile("assets/setup.html")
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Write(raw)
			return
		}
		s.files.ServeHTTP(w, r)
	case "/app.js", "/state.mjs", "/audio.mjs", "/style.css", "/setup.js":
		s.files.ServeHTTP(w, r)
	default:
		http.NotFound(w, r)
	}
}
func (s *Server) authorize(r *http.Request) (localSession, bool) {
	c, e := r.Cookie("memoalarm_session")
	if e != nil {
		return localSession{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.sessions[c.Value]
	if ok && time.Now().Before(v.expires) {
		return v, true
	}
	delete(s.sessions, c.Value)
	return localSession{}, false
}
func (s *Server) session(w http.ResponseWriter, r *http.Request) {
	b, e := io.ReadAll(http.MaxBytesReader(w, r.Body, 1024))
	var p struct {
		Bootstrap string `json:"bootstrap"`
	}
	if e != nil || domain.DecodeStrict(b, &p) != nil {
		http.Error(w, "invalid session request", 400)
		return
	}
	if p.Bootstrap == "" {
		if v, ok := s.authorize(r); ok {
			writeJSON(w, 200, map[string]string{"csrf": v.csrf})
			return
		}
		http.Error(w, "local approval required", 403)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !equal(p.Bootstrap, s.bootstrap) {
		http.Error(w, "invalid or already used local approval link", 403)
		return
	}
	token, e := RandomToken()
	if e != nil {
		http.Error(w, "session unavailable", 500)
		return
	}
	csrf, e := RandomToken()
	if e != nil {
		http.Error(w, "session unavailable", 500)
		return
	}
	s.bootstrap = ""
	s.sessions[token] = localSession{csrf: csrf, expires: time.Now().Add(8 * time.Hour)}
	http.SetCookie(w, &http.Cookie{Name: "memoalarm_session", Value: token, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 8 * 3600})
	writeJSON(w, 200, map[string]string{"csrf": csrf})
}
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}
