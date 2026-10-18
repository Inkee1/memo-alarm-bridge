package store

import (
	"context"
	"os"
)

// Backup creates a consistent SQLite snapshot, including committed WAL data.
// It never replaces an existing file. Audio blobs must be exported separately.
func (s *Store) Backup(ctx context.Context, path string) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	if err = f.Close(); err != nil {
		os.Remove(path)
		return err
	}
	if _, err = s.db.ExecContext(ctx, "VACUUM INTO ?", path); err != nil {
		os.Remove(path)
		return err
	}
	f, err = os.OpenFile(path, os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}
