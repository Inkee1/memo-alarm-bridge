package link

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/secrets"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func validateEndpoint(endpoint string) (*url.URL, error) {
	u, e := url.Parse(endpoint)
	if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return nil, errors.New("endpoint must be an HTTPS origin without credentials, path, query or fragment")
	}
	return u, nil
}

type pinnedTransport struct {
	origin    string
	transport *http.Transport
}

func (p pinnedTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Scheme+"://"+r.URL.Host != p.origin || r.URL.User != nil {
		return nil, errors.New("request outside pinned origin rejected")
	}
	return p.transport.RoundTrip(r)
}
func NewPinnedClient(endpoint, pin string) (*http.Client, error) {
	u, e := validateEndpoint(endpoint)
	if e != nil {
		return nil, e
	}
	if !domain.ValidHash(pin) {
		return nil, errors.New("invalid fingerprint")
	}
	cfg := &tls.Config{MinVersion: tls.VersionTLS12, ServerName: u.Hostname(), InsecureSkipVerify: true, VerifyConnection: func(cs tls.ConnectionState) error {
		// Public CA trust is replaced by an out-of-band exact certificate pin, plus
		// hostname and validity checks. Never accept an unverified certificate.
		if len(cs.PeerCertificates) == 0 {
			return errors.New("missing certificate")
		}
		cert := cs.PeerCertificates[0]
		hash := sha256.Sum256(cert.Raw)
		if subtle.ConstantTimeCompare([]byte(hex.EncodeToString(hash[:])), []byte(pin)) != 1 {
			return errors.New("server fingerprint mismatch")
		}
		if time.Now().Before(cert.NotBefore) || time.Now().After(cert.NotAfter) {
			return errors.New("certificate expired or not yet valid")
		}
		return cert.VerifyHostname(u.Hostname())
	}}
	transport := &http.Transport{TLSClientConfig: cfg, Proxy: nil, TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 15 * time.Second}
	return &http.Client{Transport: pinnedTransport{origin: u.Scheme + "://" + u.Host, transport: transport}, Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, nil
}

type savedIdentity struct {
	CertificatePEM []byte `json:"certificate_pem"`
	ProtectedKey   []byte `json:"protected_private_key"`
}

func parseIdentity(raw []byte, host string) (tls.Certificate, string, error) {
	var saved savedIdentity
	if e := domain.DecodeStrict(raw, &saved); e != nil {
		return tls.Certificate{}, "", e
	}
	key, e := secrets.Unprotect(saved.ProtectedKey)
	if e != nil {
		return tls.Certificate{}, "", e
	}
	defer clear(key)
	cert, e := tls.X509KeyPair(saved.CertificatePEM, key)
	if e != nil {
		return cert, "", e
	}
	leaf, e := x509.ParseCertificate(cert.Certificate[0])
	if e != nil {
		return cert, "", e
	}
	if e = leaf.VerifyHostname(host); e != nil {
		return cert, "", e
	}
	if time.Now().After(leaf.NotAfter) || time.Now().Before(leaf.NotBefore) {
		return cert, "", errors.New("stored identity expired; explicit repair and re-pairing required")
	}
	cert.Leaf = leaf
	hash := sha256.Sum256(leaf.Raw)
	return cert, hex.EncodeToString(hash[:]), nil
}

// Called only by an explicit user-enabled pairing flow, or with test-only paths.
func LoadOrCreateIdentity(path, host string) (tls.Certificate, string, error) {
	raw, e := os.ReadFile(path)
	if e == nil {
		return parseIdentity(raw, host)
	}
	if !os.IsNotExist(e) {
		return tls.Certificate{}, "", e
	}
	key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		return tls.Certificate{}, "", e
	}
	serial, e := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if e != nil {
		return tls.Certificate{}, "", e
	}
	cert := x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "MemoAlarm Bridge"}, NotBefore: time.Now().Add(-5 * time.Minute), NotAfter: time.Now().AddDate(1, 0, 0), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, BasicConstraintsValid: true}
	if ip := net.ParseIP(host); ip != nil {
		cert.IPAddresses = []net.IP{ip}
	} else if host != "" && !strings.ContainsAny(host, "/\\ ") {
		cert.DNSNames = []string{host}
	} else {
		return tls.Certificate{}, "", errors.New("invalid identity host")
	}
	der, e := x509.CreateCertificate(rand.Reader, &cert, &cert, &key.PublicKey, key)
	if e != nil {
		return tls.Certificate{}, "", e
	}
	keyDER, e := x509.MarshalPKCS8PrivateKey(key)
	if e != nil {
		return tls.Certificate{}, "", e
	}
	defer clear(keyDER)
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	defer clear(keyPEM)
	protected, e := secrets.Protect(keyPEM)
	if e != nil {
		return tls.Certificate{}, "", e
	}
	raw, e = json.Marshal(savedIdentity{CertificatePEM: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), ProtectedKey: protected})
	if e != nil {
		return tls.Certificate{}, "", e
	}
	if e = os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		return tls.Certificate{}, "", e
	}
	f, e := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if e != nil {
		return tls.Certificate{}, "", e
	}
	if _, e = f.Write(raw); e == nil {
		e = f.Sync()
	}
	closeErr := f.Close()
	if e == nil {
		e = closeErr
	}
	if e != nil {
		os.Remove(path)
		return tls.Certificate{}, "", e
	}
	return parseIdentity(raw, host)
}
