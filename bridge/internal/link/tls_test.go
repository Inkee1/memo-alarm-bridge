package link

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestBadPinRejectedBeforeTokenSend(t *testing.T) {
	var received atomic.Bool
	ts := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { received.Store(true) }))
	defer ts.Close()
	client, e := NewPinnedClient(ts.URL, strings.Repeat("0", 64))
	if e != nil {
		t.Fatal(e)
	}
	req, _ := http.NewRequest("GET", ts.URL, nil)
	req.Header.Set("Authorization", "Bearer synthetic-test-token")
	if r, e := client.Do(req); e == nil {
		r.Body.Close()
		t.Fatal("bad pin accepted")
	}
	if received.Load() {
		t.Fatal("credential reached server despite bad pin")
	}
	hash := sha256.Sum256(ts.Certificate().Raw)
	client, e = NewPinnedClient(ts.URL, hex.EncodeToString(hash[:]))
	if e != nil {
		t.Fatal(e)
	}
	r, e := client.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	r.Body.Close()
	if !received.Load() {
		t.Fatal("correct pin rejected")
	}
}
func TestPinnedClientRejectsRedirect(t *testing.T) {
	var leaked atomic.Bool
	other := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { leaked.Store(true) }))
	defer other.Close()
	ts := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, other.URL, 302) }))
	defer ts.Close()
	hash := sha256.Sum256(ts.Certificate().Raw)
	client, _ := NewPinnedClient(ts.URL, hex.EncodeToString(hash[:]))
	req, _ := http.NewRequest("GET", ts.URL, nil)
	req.Header.Set("Authorization", "Bearer synthetic-test-token")
	r, e := client.Do(req)
	if e == nil {
		r.Body.Close()
		if r.StatusCode != 302 {
			t.Fatal(r.StatusCode)
		}
	}
	if leaked.Load() {
		t.Fatal("followed credential-bearing redirect")
	}
}
func TestIdentitySurvivesReopenAndRejectsWrongHost(t *testing.T) {
	path := filepath.Join(t.TempDir(), "identity.json")
	a, ha, e := LoadOrCreateIdentity(path, "127.0.0.1")
	if e != nil {
		t.Fatal(e)
	}
	b, hb, e := LoadOrCreateIdentity(path, "127.0.0.1")
	if e != nil || ha != hb || len(a.Certificate) == 0 || len(b.Certificate) == 0 {
		t.Fatalf("identity changed: %v", e)
	}
	if _, _, e = LoadOrCreateIdentity(path, "192.0.2.1"); e == nil {
		t.Fatal("certificate accepted for wrong host")
	}
}
