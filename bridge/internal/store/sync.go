package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"time"
)

var ErrFutureCursor = errors.New("server history is older than the device cursor; review restored server before reconnecting")

type Change struct {
	Revision int64           `json:"server_revision"`
	Mutation domain.Mutation `json:"mutation"`
}

func (s *Store) Changes(ctx context.Context, after int64, limit int) ([]Change, int64, bool, error) {
	if after < 0 || limit < 1 || limit > 100 {
		return nil, after, false, errors.New("invalid cursor or page size")
	}
	var head int64
	if err := s.db.QueryRowContext(ctx, "SELECT value FROM sequence WHERE id=1").Scan(&head); err != nil {
		return nil, after, false, err
	}
	if after > head {
		return nil, after, false, ErrFutureCursor
	}
	rows, err := s.db.QueryContext(ctx, "SELECT revision,mutation FROM outbox WHERE revision>? ORDER BY revision LIMIT ?", after, limit+1)
	if err != nil {
		return nil, after, false, err
	}
	defer rows.Close()
	out := []Change{}
	cursor := after
	more := false
	for rows.Next() {
		var c Change
		var raw []byte
		if err = rows.Scan(&c.Revision, &raw); err != nil {
			return nil, after, false, err
		}
		if len(out) == limit {
			more = true
			break
		}
		if err = json.Unmarshal(raw, &c.Mutation); err != nil {
			return nil, after, false, err
		}
		out = append(out, c)
		cursor = c.Revision
	}
	return out, cursor, more, rows.Err()
}
func ValidateAck(deviceID string, a domain.DeviceAck) error {
	if a.DeviceID != deviceID || !domain.ValidID(a.DeviceID) || !domain.ValidID(a.ScheduleID) || !domain.ValidID(a.EpochID) || a.Revision < 1 || a.LocalGeneration < 1 {
		return errors.New("invalid ACK identity or version")
	}
	switch a.State {
	case "preparing", "armed", "cancelled", "blocked_permission", "blocked_audio", "fired", "dismissed", "snoozed", "missed", "interrupted", "conflict", "pending_delivery", "cancel_pending":
	default:
		return errors.New("invalid ACK state")
	}
	if _, err := time.Parse(time.RFC3339Nano, a.ObservedAt); err != nil {
		return errors.New("invalid observation time")
	}
	if len(a.ArmedOccurrences) > 3 || len(a.Capabilities) > 16 || len(a.PreviousApplied) > 2 {
		return errors.New("ACK exceeds limits")
	}
	if a.State == "armed" && (len(a.ArmedOccurrences) == 0 || a.Capabilities["exact_alarm"] != "granted") {
		return errors.New("armed ACK needs registered occurrences and permission")
	}
	if a.State == "cancelled" && len(a.ArmedOccurrences) != 0 {
		return errors.New("cancelled ACK cannot contain active occurrences")
	}
	seen := map[string]bool{}
	for _, o := range a.ArmedOccurrences {
		if !domain.ValidID(o.ID) || !domain.ValidHash(o.AudioSHA256) || seen[o.ID] {
			return errors.New("invalid occurrence evidence")
		}
		seen[o.ID] = true
		if _, err := time.Parse(time.RFC3339Nano, o.AtUTC); err != nil {
			return errors.New("invalid occurrence time")
		}
	}
	for key, value := range a.Capabilities {
		if len(key) < 1 || len(key) > 40 || (value != "granted" && value != "denied" && value != "unknown") {
			return errors.New("invalid capability")
		}
	}
	for _, previous := range a.PreviousApplied {
		if previous.Revision < 0 || previous.Revision > a.Revision || previous.LocalGeneration < 1 || previous.LocalGeneration > a.LocalGeneration || !domain.ValidID(previous.EpochID) || len(previous.Occurrences) < 1 || len(previous.Occurrences) > 3 {
			return errors.New("invalid previous applied version")
		}
		for _, o := range previous.Occurrences {
			if !domain.ValidID(o.ID) || !domain.ValidHash(o.AudioSHA256) {
				return errors.New("invalid previous occurrence")
			}
			if _, err := time.Parse(time.RFC3339Nano, o.AtUTC); err != nil {
				return err
			}
		}
	}
	return nil
}
func (s *Store) SaveAck(ctx context.Context, deviceID string, a domain.DeviceAck) error {
	if err := ValidateAck(deviceID, a); err != nil {
		return err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var revoked bool
	if err = tx.QueryRowContext(ctx, "SELECT revoked FROM devices WHERE device_id=?", deviceID).Scan(&revoked); err != nil || revoked {
		return errors.New("device unavailable")
	}
	current, exists, err := loadEntity(ctx, tx, "schedule", a.ScheduleID)
	if err != nil {
		return err
	}
	if !exists || a.Revision > current.Revision {
		return errors.New("ACK has no corresponding saved revision")
	}
	var prior []byte
	err = tx.QueryRowContext(ctx, "SELECT payload FROM device_acks WHERE device_id=? AND schedule_id=?", deviceID, a.ScheduleID).Scan(&prior)
	if err == nil {
		var old domain.DeviceAck
		if err = json.Unmarshal(prior, &old); err != nil {
			return err
		}
		oldAt, _ := time.Parse(time.RFC3339Nano, old.ObservedAt)
		newAt, _ := time.Parse(time.RFC3339Nano, a.ObservedAt)
		if a.Revision < old.Revision || (a.Revision == old.Revision && (a.LocalGeneration < old.LocalGeneration || (a.LocalGeneration == old.LocalGeneration && newAt.Before(oldAt)))) {
			return nil
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	raw, err := json.Marshal(a)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO device_acks(device_id,schedule_id,payload,received_at) VALUES(?,?,?,?) ON CONFLICT(device_id,schedule_id) DO UPDATE SET payload=excluded.payload,received_at=excluded.received_at", deviceID, a.ScheduleID, raw, time.Now().UTC().Format(time.RFC3339Nano))
	if err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Store) TouchDevice(ctx context.Context, id string) error {
	r, err := s.db.ExecContext(ctx, "UPDATE devices SET last_seen=? WHERE device_id=? AND revoked=0", time.Now().UTC().Format(time.RFC3339Nano), id)
	if err != nil {
		return err
	}
	n, _ := r.RowsAffected()
	if n != 1 {
		return fmt.Errorf("device unavailable")
	}
	return nil
}
