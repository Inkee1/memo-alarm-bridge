package store

import (
	"context"
	"errors"
)

type Device struct {
	DeviceID string `json:"device_id"`
	Name     string `json:"name"`
	Revoked  bool   `json:"revoked"`
}

func (s *Store) Devices(ctx context.Context) ([]Device, error) {
	out := []Device{}
	rows, err := s.db.QueryContext(ctx, "SELECT device_id,name,revoked FROM devices ORDER BY name")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var d Device
		if err = rows.Scan(&d.DeviceID, &d.Name, &d.Revoked); err != nil {
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
