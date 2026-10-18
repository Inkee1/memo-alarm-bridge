package secrets

import (
	"errors"
	"golang.org/x/sys/windows"
	"unsafe"
)

func transform(data []byte, protect bool) ([]byte, error) {
	if len(data) == 0 || len(data) > 1024*1024 {
		return nil, errors.New("invalid protected data size")
	}
	in := windows.DataBlob{Size: uint32(len(data)), Data: &data[0]}
	var out windows.DataBlob
	var err error
	if protect {
		err = windows.CryptProtectData(&in, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out)
	} else {
		err = windows.CryptUnprotectData(&in, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out)
	}
	if err != nil {
		return nil, err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	return append([]byte(nil), unsafe.Slice(out.Data, int(out.Size))...), nil
}
func Protect(data []byte) ([]byte, error)   { return transform(data, true) }
func Unprotect(data []byte) ([]byte, error) { return transform(data, false) }
