package link

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type Invite struct {
	SchemaVersion int    `json:"schema_version"`
	Endpoint      string `json:"endpoint"`
	CertSHA256    string `json:"cert_sha256"`
	Nonce         string `json:"nonce"`
	ExpiresAt     string `json:"expires_at"`
}
type PairRequest struct {
	Nonce      string `json:"nonce"`
	DeviceName string `json:"device_name"`
}
type DeviceCredential struct {
	SchemaVersion int    `json:"schema_version"`
	DeviceID      string `json:"device_id"`
	Token         string `json:"token"`
}
type attemptWindow struct {
	start time.Time
	count int
}
type Pairing struct {
	store         *store.Store
	endpoint, pin string
	mu            sync.Mutex
	invite        *Invite
	now           func() time.Time
	attempts      map[string]attemptWindow
	api           http.Handler
}

func NewPairing(s *store.Store, endpoint, pin string) (*Pairing, error) {
	if _, e := validateEndpoint(endpoint); e != nil {
		return nil, e
	}
	if !domain.ValidHash(pin) {
		return nil, errors.New("invalid certificate fingerprint")
	}
	return &Pairing{store: s, endpoint: strings.TrimRight(endpoint, "/"), pin: pin, now: time.Now, attempts: map[string]attemptWindow{}}, nil
}
func randomToken() (string, error) {
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		return "", e
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
func newID() (string, error) {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		return "", e
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}
func (p *Pairing) CreateInvite(ctx context.Context, expiry time.Time) (Invite, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e := ctx.Err(); e != nil {
		return Invite{}, e
	}
	now := p.now()
	if !expiry.After(now) || expiry.After(now.Add(10*time.Minute)) {
		return Invite{}, errors.New("invite must expire within ten minutes")
	}
	nonce, e := randomToken()
	if e != nil {
		return Invite{}, e
	}
	i := Invite{SchemaVersion: 1, Endpoint: p.endpoint, CertSHA256: p.pin, Nonce: nonce, ExpiresAt: expiry.UTC().Format(time.RFC3339Nano)}
	p.invite = &i
	return i, nil
}
func (p *Pairing) RedeemInvite(ctx context.Context, r PairRequest) (DeviceCredential, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	var out DeviceCredential
	if p.invite == nil {
		return out, errors.New("invalid or used invitation")
	}
	expires, e := time.Parse(time.RFC3339Nano, p.invite.ExpiresAt)
	if e != nil || !p.now().Before(expires) || subtle.ConstantTimeCompare([]byte(r.Nonce), []byte(p.invite.Nonce)) != 1 {
		return out, errors.New("invalid or expired invitation")
	}
	if strings.TrimSpace(r.DeviceName) == "" || utf8.RuneCountInString(r.DeviceName) > 80 {
		return out, errors.New("device name must be 1–80 characters")
	}
	id, e := newID()
	if e != nil {
		return out, e
	}
	token, e := randomToken()
	if e != nil {
		return out, e
	}
	if e = p.store.RegisterDevice(ctx, id, r.DeviceName, token); e != nil {
		return out, e
	}
	p.invite = nil
	return DeviceCredential{SchemaVersion: 1, DeviceID: id, Token: token}, nil
}
func (p *Pairing) allowAttempt(remote string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	for key, v := range p.attempts {
		if now.Sub(v.start) >= time.Minute {
			delete(p.attempts, key)
		}
	}
	v, ok := p.attempts[remote]
	if !ok {
		if len(p.attempts) >= 1024 {
			return false
		}
		v = attemptWindow{start: now}
	}
	if v.count >= 10 {
		return false
	}
	v.count++
	p.attempts[remote] = v
	return true
}

type deviceContextKey struct{}

func DeviceFromContext(ctx context.Context) (store.Device, bool) {
	d, ok := ctx.Value(deviceContextKey{}).(store.Device)
	return d, ok
}
func (p *Pairing) SetSyncHandler(h http.Handler) { p.mu.Lock(); p.api = h; p.mu.Unlock() }
func (p *Pairing) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if r.TLS == nil {
			http.Error(w, "TLS required", 400)
			return
		}
		if !strings.HasPrefix(r.URL.Path, "/v1/") {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Origin") != "" {
			http.Error(w, "browser origins are not accepted on device routes", 403)
			return
		}
		if r.URL.Path == "/v1/pair" {
			if r.Method != "POST" {
				http.Error(w, "POST required", 405)
				return
			}
			host, _, _ := net.SplitHostPort(r.RemoteAddr)
			if !p.allowAttempt(host) {
				w.Header().Set("Retry-After", "60")
				http.Error(w, "too many pairing attempts", 429)
				return
			}
			body, e := io.ReadAll(http.MaxBytesReader(w, r.Body, 4096))
			var req PairRequest
			if e != nil || domain.DecodeStrict(body, &req) != nil {
				http.Error(w, "invalid pairing request", 400)
				return
			}
			d, e := p.RedeemInvite(r.Context(), req)
			if e != nil {
				http.Error(w, "invalid, expired or used invitation", 403)
				return
			}
			writeJSON(w, d)
			return
		}
		auth := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		parts := strings.Split(auth, ".")
		if len(parts) != 2 {
			http.Error(w, "unauthorized", 401)
			return
		}
		d, e := p.store.AuthenticateDevice(r.Context(), parts[0], parts[1])
		if e != nil {
			http.Error(w, "unauthorized", 401)
			return
		}
		if r.URL.Path == "/v1/status" && r.Method == "GET" {
			writeJSON(w, map[string]any{"schema_version": 1, "device_id": d.DeviceID, "state": "connected"})
			return
		}
		p.mu.Lock()
		api := p.api
		p.mu.Unlock()
		if api == nil {
			http.NotFound(w, r)
			return
		}
		api.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), deviceContextKey{}, d)))
	})
}
func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}
