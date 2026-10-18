package web

import (
	"bytes"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
)

func testServer(t *testing.T) (*httptest.Server, *Server) {
	t.Helper()
	s, e := store.Open(filepath.Join(t.TempDir(), "web.db"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	h, e := New(app.New(s), "bootstrap-secret")
	if e != nil {
		t.Fatal(e)
	}
	ts := httptest.NewUnstartedServer(h)
	h.Host = ts.Listener.Addr().String()
	ts.Start()
	t.Cleanup(ts.Close)
	return ts, h
}
func request(t *testing.T, ts *httptest.Server, path, origin, host, body string, cookie *http.Cookie, csrf string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(http.MethodPost, ts.URL+path, strings.NewReader(body))
	req.Header.Set("Origin", origin)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-CSRF-Token", csrf)
	if host != "" {
		req.Host = host
	}
	if cookie != nil {
		req.AddCookie(cookie)
	}
	r, e := ts.Client().Do(req)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { r.Body.Close() })
	return r
}
func session(t *testing.T, ts *httptest.Server) (*http.Cookie, string) {
	r := request(t, ts, "/api/session", ts.URL, "", `{"bootstrap":"bootstrap-secret"}`, nil, "")
	if r.StatusCode != 200 {
		b, _ := io.ReadAll(r.Body)
		t.Fatalf("session: %d %s", r.StatusCode, b)
	}
	var v struct {
		CSRF string `json:"csrf"`
	}
	if e := json.NewDecoder(r.Body).Decode(&v); e != nil {
		t.Fatal(e)
	}
	return r.Cookies()[0], v.CSRF
}
func TestForeignOriginRejected(t *testing.T) {
	ts, _ := testServer(t)
	r := request(t, ts, "/api/session", "https://evil.example", "", `{"bootstrap":"bootstrap-secret"}`, nil, "")
	if r.StatusCode != 403 {
		t.Fatal(r.StatusCode)
	}
	cookie, csrf := session(t, ts)
	r = request(t, ts, "/api/command", "https://evil.example", "", `{"schema_version":1,"method":"export","params":{}}`, cookie, csrf)
	if r.StatusCode != 403 {
		t.Fatal(r.StatusCode)
	}
}
func TestHostRebindingRejected(t *testing.T) {
	ts, _ := testServer(t)
	r := request(t, ts, "/api/session", ts.URL, "evil.example", `{"bootstrap":"bootstrap-secret"}`, nil, "")
	if r.StatusCode != 403 {
		t.Fatal(r.StatusCode)
	}
}
func TestSessionAndCsrfRequired(t *testing.T) {
	ts, _ := testServer(t)
	cookie, csrf := session(t, ts)
	for _, token := range []string{"", "wrong"} {
		r := request(t, ts, "/api/command", ts.URL, "", `{"schema_version":1,"method":"doctor","params":{}}`, cookie, token)
		if r.StatusCode != 403 {
			t.Fatal(r.StatusCode)
		}
	}
	r := request(t, ts, "/api/command", ts.URL, "", `{"schema_version":1,"method":"doctor","params":{}}`, cookie, csrf)
	if r.StatusCode != 200 {
		t.Fatal(r.StatusCode)
	}
	if !cookie.HttpOnly || cookie.SameSite != http.SameSiteStrictMode {
		t.Fatal("weak cookie")
	}
	r = request(t, ts, "/api/session", ts.URL, "", `{"bootstrap":"bootstrap-secret"}`, nil, "")
	if r.StatusCode != 403 {
		t.Fatal("bootstrap reused")
	}
}
func TestAuthenticatedPageUsesStrictCsp(t *testing.T) {
	ts, _ := testServer(t)
	r, e := ts.Client().Get(ts.URL)
	if e != nil {
		t.Fatal(e)
	}
	defer r.Body.Close()
	if !strings.Contains(r.Header.Get("Content-Security-Policy"), "default-src 'self'") || strings.Contains(r.Header.Get("Content-Security-Policy"), "unsafe-inline") {
		t.Fatal("weak CSP")
	}
	b, _ := io.ReadAll(r.Body)
	if !bytes.Contains(b, []byte("MemoAlarm Bridge")) {
		t.Fatal("UI missing")
	}
	u, _ := url.Parse(ts.URL)
	if u.Host == "" {
		t.Fatal("invalid fixture")
	}
}
