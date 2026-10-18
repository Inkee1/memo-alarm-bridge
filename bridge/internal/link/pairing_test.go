package link

import (
	"context"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var ctx = context.Background()

func manager(t *testing.T) *Pairing {
	t.Helper()
	s, e := store.Open(filepath.Join(t.TempDir(), "pair.db"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	p, e := NewPairing(s, "https://127.0.0.1:38491", strings.Repeat("1", 64))
	if e != nil {
		t.Fatal(e)
	}
	return p
}
func TestExpiredInviteRejected(t *testing.T) {
	p := manager(t)
	now := time.Now()
	p.now = func() time.Time { return now }
	i, e := p.CreateInvite(ctx, now.Add(time.Minute))
	if e != nil {
		t.Fatal(e)
	}
	now = now.Add(time.Minute)
	if _, e = p.RedeemInvite(ctx, PairRequest{Nonce: i.Nonce, DeviceName: "Test phone"}); e == nil {
		t.Fatal("expired invite accepted")
	}
	if _, e = p.CreateInvite(ctx, now.Add(11*time.Minute)); e == nil {
		t.Fatal("long-lived invite accepted")
	}
}
func TestInviteSingleUse(t *testing.T) {
	p := manager(t)
	i, e := p.CreateInvite(ctx, time.Now().Add(time.Minute))
	if e != nil {
		t.Fatal(e)
	}
	req := PairRequest{Nonce: i.Nonce, DeviceName: "Test phone"}
	first, e := p.RedeemInvite(ctx, req)
	if e != nil || first.Token == "" {
		t.Fatal(e)
	}
	if _, e = p.RedeemInvite(ctx, req); e == nil {
		t.Fatal("replayed invitation accepted")
	}
	if _, e = p.store.AuthenticateDevice(ctx, first.DeviceID, first.Token); e != nil {
		t.Fatal("issued credential unusable")
	}
}
func pairedRequest(t *testing.T, ts *httptest.Server, method, path, credential, body string) *http.Response {
	t.Helper()
	r, _ := http.NewRequest(method, ts.URL+path, strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	if credential != "" {
		r.Header.Set("Authorization", "Bearer "+credential)
	}
	res, e := ts.Client().Do(r)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { res.Body.Close() })
	return res
}
func TestRevokedDeviceDenied(t *testing.T) {
	p := manager(t)
	i, _ := p.CreateInvite(ctx, time.Now().Add(time.Minute))
	d, e := p.RedeemInvite(ctx, PairRequest{Nonce: i.Nonce, DeviceName: "Test phone"})
	if e != nil {
		t.Fatal(e)
	}
	ts := httptest.NewTLSServer(p.Handler())
	defer ts.Close()
	auth := d.DeviceID + "." + d.Token
	if r := pairedRequest(t, ts, "GET", "/v1/status", auth, ""); r.StatusCode != 200 {
		t.Fatal(r.StatusCode)
	}
	if e = p.store.RevokeDevice(ctx, d.DeviceID); e != nil {
		t.Fatal(e)
	}
	if r := pairedRequest(t, ts, "GET", "/v1/status", auth, ""); r.StatusCode != 401 {
		t.Fatal("revoked credential accepted")
	}
}
func TestRateLimitedPairing(t *testing.T) {
	p := manager(t)
	ts := httptest.NewTLSServer(p.Handler())
	defer ts.Close()
	for n := 0; n < 10; n++ {
		r := pairedRequest(t, ts, "POST", "/v1/pair", "", `{"nonce":"wrong","device_name":"test"}`)
		io.Copy(io.Discard, r.Body)
		r.Body.Close()
		if r.StatusCode == 200 {
			t.Fatal("bad nonce accepted")
		}
	}
	if r := pairedRequest(t, ts, "POST", "/v1/pair", "", `{"nonce":"wrong","device_name":"test"}`); r.StatusCode != 429 {
		t.Fatalf("not rate limited: %d", r.StatusCode)
	}
}
func TestNoAdminRoutesOnLan(t *testing.T) {
	p := manager(t)
	ts := httptest.NewTLSServer(p.Handler())
	defer ts.Close()
	for _, path := range []string{"/", "/api/command", "/api/session", "/app.js"} {
		r := pairedRequest(t, ts, "GET", path, "", "")
		if r.StatusCode != 404 {
			t.Fatalf("admin route exposed: %s %d", path, r.StatusCode)
		}
	}
	for _, path := range []string{"/v1/sync", "/v1/ack", "/v1/blob/anything", "/v1/status"} {
		r := pairedRequest(t, ts, "POST", path, "wrong", `{}`)
		if r.StatusCode != 401 {
			t.Fatalf("unauthenticated route: %s %d", path, r.StatusCode)
		}
	}
}
func TestPairOverTlsRejectsReplay(t *testing.T) {
	p := manager(t)
	i, _ := p.CreateInvite(ctx, time.Now().Add(time.Minute))
	ts := httptest.NewTLSServer(p.Handler())
	defer ts.Close()
	body, _ := json.Marshal(PairRequest{Nonce: i.Nonce, DeviceName: "Test phone"})
	r := pairedRequest(t, ts, "POST", "/v1/pair", "", string(body))
	if r.StatusCode != 200 {
		t.Fatal(r.StatusCode)
	}
	r = pairedRequest(t, ts, "POST", "/v1/pair", "", string(body))
	if r.StatusCode != 403 {
		t.Fatal("HTTP replay accepted")
	}
}
