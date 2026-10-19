//go:build windows

package install

import (
	"golang.org/x/sys/windows"
	"os"
)

func replaceFile(from, to string) error {
	a, e := windows.UTF16PtrFromString(from)
	if e != nil {
		return e
	}
	b, e := windows.UTF16PtrFromString(to)
	if e != nil {
		return e
	}
	return windows.MoveFileEx(a, b, windows.MOVEFILE_REPLACE_EXISTING|windows.MOVEFILE_WRITE_THROUGH)
}
func lockInstall(path string) (func(), error) {
	file, e := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	overlap := &windows.Overlapped{}
	if e = windows.LockFileEx(windows.Handle(file.Fd()), windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, overlap); e != nil {
		file.Close()
		return nil, e
	}
	return func() { windows.UnlockFileEx(windows.Handle(file.Fd()), 0, 1, 0, overlap); file.Close() }, nil
}
