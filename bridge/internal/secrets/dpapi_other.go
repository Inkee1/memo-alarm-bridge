//go:build !windows

package secrets

import "errors"

func Protect([]byte) ([]byte, error) {
	return nil, errors.New("persistent device identities require Windows DPAPI; this platform is unsupported")
}
func Unprotect([]byte) ([]byte, error) {
	return nil, errors.New("Windows DPAPI data cannot be opened on this platform")
}
