//go:build windows

package link

import (
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func runtimeTest(t *testing.T) (*Runtime, string) {
	t.Helper()
	dir := t.TempDir()
	s, e := store.Open(filepath.Join(dir, "bridge.db"))
	if e != nil {
		t.Fatal(e)
	}
	r := NewRuntime(s, dir)
	t.Cleanup(func() { r.Close(); s.Close() })
	return r, dir
}
func TestRuntimeIsOffUntilConsent(t *testing.T) {
	r, dir := runtimeTest(t)
	if e := r.Restore(ctx); e != nil {
		t.Fatal(e)
	}
	if r.Status().Enabled {
		t.Fatal("new install enabled pairing")
	}
	if _, e := r.Enable(ctx, EnableRequest{BindAddress: "127.0.0.1", Port: 0}); e == nil {
		t.Fatal("enabled without consent")
	}
	if _, e := os.Stat(filepath.Join(dir, "link-config.json")); !os.IsNotExist(e) {
		t.Fatal("wrote configuration without consent")
	}
}
func TestRuntimeEnablesLoopbackOnlyOnRequest(t *testing.T) {
	r, _ := runtimeTest(t)
	status, e := r.Enable(ctx, EnableRequest{Consent: true, BindAddress: "127.0.0.1", Port: 0})
	if e != nil {
		t.Fatal(e)
	}
	if !status.Enabled {
		t.Fatal("not enabled")
	}
	client, e := NewPinnedClient(status.Endpoint, status.CertSHA256)
	if e != nil {
		t.Fatal(e)
	}
	res, e := client.Get(status.Endpoint + "/v1/status")
	if e != nil {
		t.Fatal(e)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusUnauthorized {
		t.Fatal("unauthenticated access")
	}
	invite, e := r.Handle(ctx, "link.invite", json.RawMessage(`{}`))
	if e != nil || invite == nil {
		t.Fatal("no invite")
	}
	if _, e = r.Handle(ctx, "link.disable", json.RawMessage(`{}`)); e != nil {
		t.Fatal(e)
	}
	if r.Status().Enabled {
		t.Fatal("disable failed")
	}
}
func TestRuntimeRestoresOnlyApprovedConfig(t *testing.T) {
	r, dir := runtimeTest(t)
	a, e := r.Enable(ctx, EnableRequest{Consent: true, BindAddress: "127.0.0.1", Port: 0})
	if e != nil {
		t.Fatal(e)
	}
	r.Close()
	other := NewRuntime(r.store, dir)
	defer other.Close()
	if e = other.Restore(ctx); e != nil {
		t.Fatal(e)
	}
	b := other.Status()
	if !b.Enabled || b.CertSHA256 != a.CertSHA256 || b.Endpoint != a.Endpoint {
		t.Fatalf("approved link not restored: %+v %+v", a, b)
	}
}
