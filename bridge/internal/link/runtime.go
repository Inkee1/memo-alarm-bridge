package link

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"time"
)

type EnableRequest struct {
	Consent     bool   `json:"consent"`
	BindAddress string `json:"bind_address"`
	Port        int    `json:"port"`
}
type LinkStatus struct {
	SchemaVersion int      `json:"schema_version"`
	Enabled       bool     `json:"enabled"`
	Endpoint      string   `json:"endpoint,omitempty"`
	CertSHA256    string   `json:"cert_sha256,omitempty"`
	Addresses     []string `json:"addresses"`
	Error         string   `json:"error,omitempty"`
}
type linkConfig struct {
	SchemaVersion int    `json:"schema_version"`
	Enabled       bool   `json:"enabled"`
	BindAddress   string `json:"bind_address"`
	Port          int    `json:"port"`
}
type Runtime struct {
	store                    *store.Store
	dir                      string
	mu                       sync.Mutex
	server                   *http.Server
	pairing                  *Pairing
	config                   linkConfig
	endpoint, pin, lastError string
	syncHandler              http.Handler
}

func NewRuntime(s *store.Store, dir string) *Runtime { return &Runtime{store: s, dir: dir} }
func bindAddresses() []string {
	out := []string{}
	seen := map[string]bool{}
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if v, ok := a.(*net.IPNet); ok {
			ip := v.IP
			if !ip.IsLoopback() && !ip.IsUnspecified() && !ip.IsLinkLocalUnicast() && ip.To4() != nil && !seen[ip.String()] {
				out = append(out, ip.String())
				seen[ip.String()] = true
			}
		}
	}
	sort.Strings(out)
	return append(out, "127.0.0.1")
}
func (r *Runtime) statusLocked() LinkStatus {
	return LinkStatus{SchemaVersion: 1, Enabled: r.server != nil, Endpoint: r.endpoint, CertSHA256: r.pin, Addresses: bindAddresses(), Error: r.lastError}
}
func (r *Runtime) Status() LinkStatus { r.mu.Lock(); defer r.mu.Unlock(); return r.statusLocked() }
func (r *Runtime) saveConfig(c linkConfig) error {
	raw, e := json.Marshal(c)
	if e != nil {
		return e
	}
	f, e := os.CreateTemp(r.dir, "link-config-")
	if e != nil {
		return e
	}
	defer os.Remove(f.Name())
	if _, e = f.Write(raw); e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e != nil {
		return e
	}
	return os.Rename(f.Name(), filepath.Join(r.dir, "link-config.json"))
}
func (r *Runtime) Enable(ctx context.Context, req EnableRequest) (LinkStatus, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !req.Consent {
		return r.statusLocked(), errors.New("explicit consent is required to create credentials and accept device connections")
	}
	if e := ctx.Err(); e != nil {
		return r.statusLocked(), e
	}
	if r.server != nil {
		return r.statusLocked(), nil
	}
	ip := net.ParseIP(req.BindAddress)
	if ip == nil || ip.IsUnspecified() || ip.IsMulticast() || req.Port < 0 || req.Port > 65535 {
		return r.statusLocked(), errors.New("choose one local IP address and a valid port")
	}
	listener, e := net.Listen("tcp", net.JoinHostPort(ip.String(), strconv.Itoa(req.Port)))
	if e != nil {
		return r.statusLocked(), e
	}
	digest := sha256.Sum256([]byte(ip.String()))
	identity := filepath.Join(r.dir, "identity-"+hex.EncodeToString(digest[:8])+".json")
	cert, pin, e := LoadOrCreateIdentity(identity, ip.String())
	if e != nil {
		listener.Close()
		return r.statusLocked(), e
	}
	endpoint := "https://" + listener.Addr().String()
	pairing, e := NewPairing(r.store, endpoint, pin)
	if e != nil {
		listener.Close()
		return r.statusLocked(), e
	}
	pairing.SetSyncHandler(r.syncHandler)
	config := linkConfig{SchemaVersion: 1, Enabled: true, BindAddress: ip.String(), Port: listener.Addr().(*net.TCPAddr).Port}
	if e = r.saveConfig(config); e != nil {
		listener.Close()
		return r.statusLocked(), e
	}
	server := &http.Server{Handler: pairing.Handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 60 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8192}
	secure := tls.NewListener(listener, &tls.Config{MinVersion: tls.VersionTLS12, Certificates: []tls.Certificate{cert}})
	r.server = server
	r.pairing = pairing
	r.endpoint = endpoint
	r.pin = pin
	r.config = config
	r.lastError = ""
	go func() {
		e := server.Serve(secure)
		if e != nil && !errors.Is(e, http.ErrServerClosed) {
			r.mu.Lock()
			if r.server == server {
				r.server = nil
				r.lastError = "device listener stopped; disable and enable connection to retry"
			}
			r.mu.Unlock()
		}
	}()
	return r.statusLocked(), nil
}
func (r *Runtime) Restore(ctx context.Context) error {
	raw, e := os.ReadFile(filepath.Join(r.dir, "link-config.json"))
	if os.IsNotExist(e) {
		return nil
	}
	if e != nil {
		return e
	}
	var c linkConfig
	if e = domain.DecodeStrict(raw, &c); e != nil || c.SchemaVersion != 1 {
		return errors.New("invalid saved link configuration; connection remains off")
	}
	if !c.Enabled {
		return nil
	}
	_, e = r.Enable(ctx, EnableRequest{Consent: true, BindAddress: c.BindAddress, Port: c.Port})
	return e
}
func (r *Runtime) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	var err error
	if r.server != nil {
		err = r.server.Close()
		r.server = nil
	}
	r.pairing = nil
	r.endpoint = ""
	r.pin = ""
	return err
}
func (r *Runtime) SetSyncHandler(h http.Handler) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.syncHandler = h
	if r.pairing != nil {
		r.pairing.SetSyncHandler(h)
	}
}
func (r *Runtime) Handle(ctx context.Context, method string, raw json.RawMessage) (any, error) {
	if method == "link.enable" {
		var req EnableRequest
		if e := domain.DecodeStrict(raw, &req); e != nil {
			return nil, e
		}
		return r.Enable(ctx, req)
	}
	if e := domain.DecodeStrict(raw, &struct{}{}); e != nil {
		return nil, e
	}
	switch method {
	case "link.status":
		return r.Status(), nil
	case "link.invite":
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.pairing == nil {
			return nil, errors.New("enable phone connection first")
		}
		i, e := r.pairing.CreateInvite(ctx, time.Now().Add(10*time.Minute))
		if e != nil {
			return nil, e
		}
		b, _ := json.Marshal(i)
		return map[string]any{"invite": i, "encoded": base64.RawURLEncoding.EncodeToString(b)}, nil
	case "link.disable":
		r.mu.Lock()
		defer r.mu.Unlock()
		c := r.config
		c.SchemaVersion = 1
		c.Enabled = false
		if e := r.saveConfig(c); e != nil {
			return nil, e
		}
		if r.server != nil {
			r.server.Close()
		}
		r.server = nil
		r.pairing = nil
		r.endpoint = ""
		r.pin = ""
		r.config = c
		return r.statusLocked(), nil
	default:
		return nil, fmt.Errorf("unsupported link command")
	}
}
