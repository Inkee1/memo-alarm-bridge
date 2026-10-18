package store

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"io"
	"os"
	"path/filepath"
)

type BlobRef struct {
	Hash string `json:"hash"`
	Size int64  `json:"size"`
}

func (s *Store) PutBlob(ctx context.Context, r io.Reader, expectedHash string) (BlobRef, error) {
	ref := BlobRef{Hash: expectedHash}
	if !domain.ValidHash(expectedHash) {
		return ref, errors.New("invalid blob hash")
	}
	if err := ctx.Err(); err != nil {
		return ref, err
	}
	f, err := os.CreateTemp(s.blobDir, "upload-")
	if err != nil {
		return ref, err
	}
	defer os.Remove(f.Name())
	hash := sha256.New()
	size, err := io.Copy(io.MultiWriter(f, hash), io.LimitReader(r, domain.MaxBlobBytes+1))
	if err != nil {
		f.Close()
		return ref, err
	}
	if size > domain.MaxBlobBytes {
		f.Close()
		return ref, errors.New("audio exceeds 25 MiB")
	}
	if hex.EncodeToString(hash.Sum(nil)) != expectedHash {
		f.Close()
		return ref, errors.New("audio hash mismatch")
	}
	if err = f.Sync(); err != nil {
		f.Close()
		return ref, err
	}
	if err = f.Close(); err != nil {
		return ref, err
	}
	if err = ctx.Err(); err != nil {
		return ref, err
	}
	target := filepath.Join(s.blobDir, expectedHash)
	if _, err = os.Stat(target); os.IsNotExist(err) {
		if err = os.Rename(f.Name(), target); err != nil {
			return ref, err
		}
	} else if err != nil {
		return ref, err
	}
	if _, err = s.db.ExecContext(ctx, "INSERT OR IGNORE INTO blobs(hash,size) VALUES(?,?)", expectedHash, size); err != nil {
		return ref, err
	}
	ref.Size = size
	return ref, nil
}
func (s *Store) BlobPath(ctx context.Context, hash string) (string, error) {
	if !domain.ValidHash(hash) {
		return "", errors.New("invalid blob hash")
	}
	var size int64
	if err := s.db.QueryRowContext(ctx, "SELECT size FROM blobs WHERE hash=?", hash).Scan(&size); err != nil {
		return "", err
	}
	path := filepath.Join(s.blobDir, hash)
	info, err := os.Stat(path)
	if err != nil || info.Size() != size {
		return "", errors.New("audio missing or incomplete")
	}
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	sum := sha256.New()
	if _, err = io.Copy(sum, f); err != nil {
		return "", err
	}
	if hex.EncodeToString(sum.Sum(nil)) != hash {
		return "", errors.New("audio integrity check failed")
	}
	return path, nil
}
