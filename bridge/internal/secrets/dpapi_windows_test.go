package secrets

import (
	"bytes"
	"testing"
)

func TestProtectRoundTripAndTamper(t *testing.T) {
	plain := []byte("synthetic-test-credential")
	sealed, e := Protect(plain)
	if e != nil {
		t.Fatal(e)
	}
	if bytes.Contains(sealed, plain) {
		t.Fatal("plaintext in protected output")
	}
	opened, e := Unprotect(sealed)
	if e != nil || !bytes.Equal(opened, plain) {
		t.Fatal("DPAPI round trip failed")
	}
	sealed[len(sealed)/2] ^= 0xff
	if _, e = Unprotect(sealed); e == nil {
		t.Fatal("tampered DPAPI data accepted")
	}
}
