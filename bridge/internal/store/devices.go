package store

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"strings"
	"unicode/utf8"
)

type Device struct {
	DeviceID string `json:"device_id"`
	Name     string `json:"name"`
	Revoked  bool   `json:"revoked"`
	LastSeen string `json:"last_seen,omitempty"`
}

func (s *Store) RegisterDevice(ctx context.Context, id, name, token string) error {
	if !domain.ValidID(id) || strings.TrimSpace(name) == "" || utf8.RuneCountInString(name) > 80 || len(token) < 40 {
		return errors.New("invalid device credential")
	}
	hash := sha256.Sum256([]byte(token))
	_, err := s.db.ExecContext(ctx, "INSERT INTO devices(device_id,name,token_hash,revoked) VALUES(?,?,?,0)", id, name, hex.EncodeToString(hash[:]))
	return err
}
func (s *Store) AuthenticateDevice(ctx context.Context, id, token string) (Device, error) {
	var d Device
	var stored string
	if !domain.ValidID(id) || len(token) < 40 || len(token) > 128 {
		return d, errors.New("unauthorized")
	}
	err := s.db.QueryRowContext(ctx, "SELECT device_id,name,token_hash,revoked FROM devices WHERE device_id=?", id).Scan(&d.DeviceID, &d.Name, &stored, &d.Revoked)
	hash := sha256.Sum256([]byte(token))
	match := subtle.ConstantTimeCompare([]byte(stored), []byte(hex.EncodeToString(hash[:])))
	if err != nil || d.Revoked || match != 1 {
		return Device{}, errors.New("unauthorized")
	}
	return d, nil
}

func (s *Store) Devices(ctx context.Context) ([]Device, error) {
	out := []Device{}
	rows, err := s.db.QueryContext(ctx, "SELECT device_id,name,revoked,last_seen FROM devices ORDER BY name")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var d Device
		if err = rows.Scan(&d.DeviceID, &d.Name, &d.Revoked, &d.LastSeen); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}
func (s *Store) RevokeDevice(ctx context.Context, id string) error {
	r, err := s.db.ExecContext(ctx, "UPDATE devices SET revoked=1 WHERE device_id=?", id)
	if err != nil {
		return err
	}
	n, err := r.RowsAffected()
	if err == nil && n == 0 {
		return errors.New("device not found")
	}
	return err
}
