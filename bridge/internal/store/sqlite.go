package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	_ "modernc.org/sqlite"
	"os"
	"path/filepath"
	"time"
)

var ErrConflict = errors.New("revision conflict")
var ErrCancelled = errors.New("cancelled epoch cannot be reused")
var ErrOperationReuse = errors.New("operation_id reused with different content")

type Store struct {
	db      *sql.DB
	blobDir string
}

func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	fail := func(e error) (*Store, error) { db.Close(); return nil, e }
	if err = migrate(db); err != nil {
		return fail(err)
	}
	if _, err = db.Exec("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;"); err != nil {
		return fail(err)
	}
	var check string
	if err = db.QueryRow("PRAGMA quick_check").Scan(&check); err != nil || check != "ok" {
		return fail(errors.New("database integrity check failed"))
	}
	s := &Store{db: db, blobDir: filepath.Join(filepath.Dir(path), "blobs")}
	if err = os.MkdirAll(s.blobDir, 0700); err != nil {
		return fail(err)
	}
	return s, nil
}
func (s *Store) Close() error                   { return s.db.Close() }
func (s *Store) Ping(ctx context.Context) error { return s.db.PingContext(ctx) }
func loadEntity(ctx context.Context, q interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, typ, id string) (domain.Entity, bool, error) {
	e := domain.Entity{EntityType: typ, EntityID: id}
	var p []byte
	err := q.QueryRowContext(ctx, "SELECT revision,payload,deleted FROM entities WHERE entity_type=? AND entity_id=?", typ, id).Scan(&e.Revision, &p, &e.Deleted)
	if errors.Is(err, sql.ErrNoRows) {
		return e, false, nil
	}
	if err != nil {
		return e, false, err
	}
	if !json.Valid(p) {
		return e, false, errors.New("invalid stored entity")
	}
	e.Payload = p
	return e, true, nil
}
func (s *Store) Apply(ctx context.Context, m domain.Mutation) (domain.MutationResult, error) {
	result := domain.MutationResult{OperationID: m.OperationID}
	if err := domain.ValidateMutation(m); err != nil {
		return result, err
	}
	raw, _ := json.Marshal(m)
	sum := sha256.Sum256(raw)
	fingerprint := hex.EncodeToString(sum[:])
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	var priorHash string
	var priorResult []byte
	err = tx.QueryRowContext(ctx, "SELECT fingerprint,result FROM operations WHERE operation_id=?", m.OperationID).Scan(&priorHash, &priorResult)
	if err == nil {
		if priorHash != fingerprint {
			return result, ErrOperationReuse
		}
		err = json.Unmarshal(priorResult, &result)
		if err == nil && result.Status == "conflict" {
			return result, ErrConflict
		}
		return result, err
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return result, err
	}
	current, exists, err := loadEntity(ctx, tx, m.EntityType, m.EntityID)
	if err != nil {
		return result, err
	}
	if m.Action != "cancel" && m.BaseRevision != current.Revision {
		result.Status = "conflict"
		result.Revision = current.Revision
		if exists {
			result.Current = &current
		}
		saved, _ := json.Marshal(result)
		if _, err = tx.ExecContext(ctx, "INSERT INTO operations(operation_id,fingerprint,result) VALUES(?,?,?)", m.OperationID, fingerprint, saved); err != nil {
			return result, err
		}
		if err = tx.Commit(); err != nil {
			return result, err
		}
		return result, ErrConflict
	}
	payload := m.Payload
	deleted := false
	active := false
	writeEntity := true
	switch {
	case m.Action == "cancel":
		var p struct {
			EpochID string `json:"epoch_id"`
		}
		json.Unmarshal(payload, &p)
		if _, err = tx.ExecContext(ctx, "INSERT OR IGNORE INTO tombstones(schedule_id,epoch_id) VALUES(?,?)", m.EntityID, p.EpochID); err != nil {
			return result, err
		}
		writeEntity = false
		if exists {
			var r domain.ScheduleRule
			if err = json.Unmarshal(current.Payload, &r); err != nil {
				return result, err
			}
			if r.EpochID == p.EpochID {
				r.Enabled = false
				payload, _ = json.Marshal(r)
				writeEntity = true
			}
		}
		result.Status = "cancel_pending"
	case m.Action == "delete":
		payload, _ = json.Marshal(domain.Note{NoteID: m.EntityID, Text: ""})
		deleted = true
		result.Status = "deleted"
	case m.EntityType == "schedule":
		var r domain.ScheduleRule
		json.Unmarshal(payload, &r)
		var cancelled int
		if err = tx.QueryRowContext(ctx, "SELECT count(*) FROM tombstones WHERE schedule_id=? AND epoch_id=?", m.EntityID, r.EpochID).Scan(&cancelled); err != nil {
			return result, err
		}
		if cancelled > 0 && r.Enabled {
			return result, ErrCancelled
		}
		active = r.Enabled
		if active {
			future, _ := domain.NextOccurrences(r, time.Now(), 1)
			if len(future) > 0 {
				count, countErr := activeCount(ctx, tx, m.EntityID, time.Now())
				if countErr != nil {
					return result, countErr
				}
				if count >= 50 {
					return result, errors.New("at most 50 active schedules")
				}
			}
		} else {
			if _, err = tx.ExecContext(ctx, "INSERT OR IGNORE INTO tombstones(schedule_id,epoch_id) VALUES(?,?)", m.EntityID, r.EpochID); err != nil {
				return result, err
			}
		}
		result.Status = "pending_delivery"
		if !active {
			result.Status = "cancel_pending"
		}
	case m.EntityType == "note":
		var n domain.Note
		json.Unmarshal(payload, &n)
		if n.AudioBlobSHA256 != "" {
			var found int
			if err = tx.QueryRowContext(ctx, "SELECT count(*) FROM blobs WHERE hash=?", n.AudioBlobSHA256).Scan(&found); err != nil {
				return result, err
			}
			if found == 0 {
				return result, errors.New("upload audio before saving note")
			}
		}
		now := time.Now().UTC().Format(time.RFC3339Nano)
		n.CreatedAt = now
		if exists {
			var old domain.Note
			json.Unmarshal(current.Payload, &old)
			n.CreatedAt = old.CreatedAt
		}
		n.UpdatedAt = now
		payload, _ = json.Marshal(n)
		result.Status = "stored"
	}
	if err = tx.QueryRowContext(ctx, "UPDATE sequence SET value=value+1 WHERE id=1 RETURNING value").Scan(&result.Revision); err != nil {
		return result, err
	}
	if writeEntity {
		_, err = tx.ExecContext(ctx, "INSERT INTO entities(entity_type,entity_id,revision,payload,deleted,active) VALUES(?,?,?,?,?,?) ON CONFLICT(entity_type,entity_id) DO UPDATE SET revision=excluded.revision,payload=excluded.payload,deleted=excluded.deleted,active=excluded.active", m.EntityType, m.EntityID, result.Revision, []byte(payload), deleted, active)
		if err != nil {
			return result, err
		}
		result.Entity = &domain.Entity{EntityType: m.EntityType, EntityID: m.EntityID, Revision: result.Revision, Payload: payload, Deleted: deleted}
	} else if exists {
		result.Entity = &current
	}
	delivered := m
	if m.Action == "upsert" {
		delivered.Payload = payload
	}
	change, _ := json.Marshal(delivered)
	if _, err = tx.ExecContext(ctx, "INSERT INTO outbox(revision,mutation) VALUES(?,?)", result.Revision, change); err != nil {
		return result, err
	}
	saved, _ := json.Marshal(result)
	if _, err = tx.ExecContext(ctx, "INSERT INTO operations(operation_id,fingerprint,result) VALUES(?,?,?)", m.OperationID, fingerprint, saved); err != nil {
		return result, err
	}
	if err = tx.Commit(); err != nil {
		return result, err
	}
	return result, nil
}

func activeCount(ctx context.Context, tx *sql.Tx, except string, now time.Time) (int, error) {
	rows, err := tx.QueryContext(ctx, "SELECT payload FROM entities WHERE entity_type='schedule' AND active=1 AND entity_id<>?", except)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	count := 0
	for rows.Next() {
		var payload []byte
		if err = rows.Scan(&payload); err != nil {
			return 0, err
		}
		var r domain.ScheduleRule
		if err = json.Unmarshal(payload, &r); err != nil {
			return 0, err
		}
		next, e := domain.NextOccurrences(r, now, 1)
		if e != nil {
			return 0, e
		}
		if len(next) > 0 {
			count++
		}
	}
	return count, rows.Err()
}
func (s *Store) List(ctx context.Context) (domain.Snapshot, error) {
	snap := domain.Snapshot{SchemaVersion: 1, Entities: []domain.Entity{}, DeviceAcks: []domain.DeviceAck{}}
	rows, err := s.db.QueryContext(ctx, "SELECT entity_type,entity_id,revision,payload,deleted FROM entities ORDER BY revision")
	if err != nil {
		return snap, err
	}
	for rows.Next() {
		var e domain.Entity
		var p []byte
		if err = rows.Scan(&e.EntityType, &e.EntityID, &e.Revision, &p, &e.Deleted); err != nil {
			rows.Close()
			return snap, err
		}
		if !json.Valid(p) {
			rows.Close()
			return snap, fmt.Errorf("invalid stored entity")
		}
		e.Payload = p
		snap.Entities = append(snap.Entities, e)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return snap, err
	}
	rows, err = s.db.QueryContext(ctx, "SELECT payload FROM device_acks ORDER BY received_at")
	if err != nil {
		return snap, err
	}
	defer rows.Close()
	for rows.Next() {
		var p []byte
		var a domain.DeviceAck
		if err = rows.Scan(&p); err != nil {
			return snap, err
		}
		if err = json.Unmarshal(p, &a); err != nil {
			return snap, err
		}
		snap.DeviceAcks = append(snap.DeviceAcks, a)
	}
	return snap, rows.Err()
}
