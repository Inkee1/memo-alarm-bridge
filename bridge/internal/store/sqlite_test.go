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
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var ctx = context.Background()

const noteID = "11111111-1111-4111-8111-111111111111"
const epochID = "22222222-2222-4222-8222-222222222222"

func op(i int) string { return fmt.Sprintf("33333333-3333-4333-8333-%012d", i) }
func noteMutation(i int, base int64, text string) domain.Mutation {
	p, _ := json.Marshal(domain.Note{NoteID: noteID, Text: text})
	return domain.Mutation{SchemaVersion: 1, OperationID: op(i), EntityType: "note", EntityID: noteID, BaseRevision: base, Action: "upsert", Payload: p}
}
func openTest(t *testing.T) *Store {
	t.Helper()
	s, e := Open(filepath.Join(t.TempDir(), "bridge.db"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	return s
}
func TestOperationRetryReturnsSameRevision(t *testing.T) {
	s := openTest(t)
	m := noteMutation(1, 0, "hello")
	a, e := s.Apply(ctx, m)
	if e != nil {
		t.Fatal(e)
	}
	b, e := s.Apply(ctx, m)
	if e != nil || a.Revision != b.Revision {
		t.Fatalf("retry changed result: %+v %+v %v", a, b, e)
	}
	m = noteMutation(1, 0, "different")
	if _, e = s.Apply(ctx, m); e == nil {
		t.Fatal("operation ID reused with new data")
	}
}
func TestStaleBaseReturnsConflict(t *testing.T) {
	s := openTest(t)
	a, _ := s.Apply(ctx, noteMutation(1, 0, "first"))
	_, e := s.Apply(ctx, noteMutation(2, 0, "stale"))
	if !errors.Is(e, ErrConflict) {
		t.Fatalf("expected conflict: %v", e)
	}
	snap, _ := s.List(ctx)
	if len(snap.Entities) != 1 || snap.Entities[0].Revision != a.Revision || !strings.Contains(string(snap.Entities[0].Payload), "first") {
		t.Fatal("stale edit overwrote data")
	}
}
func TestCancelEpochCannotResurrect(t *testing.T) {
	s := openTest(t)
	r := domain.ScheduleRule{ScheduleID: noteID, EpochID: epochID, Kind: "daily", LocalTime: "09:00", ZoneID: "UTC", Body: "test", Enabled: true}
	p, _ := json.Marshal(r)
	m := domain.Mutation{SchemaVersion: 1, OperationID: op(1), EntityType: "schedule", EntityID: noteID, Action: "upsert", Payload: p}
	a, e := s.Apply(ctx, m)
	if e != nil {
		t.Fatal(e)
	}
	c := m
	c.OperationID = op(2)
	c.BaseRevision = a.Revision
	c.Action = "cancel"
	c.Payload = json.RawMessage(`{"epoch_id":"22222222-2222-4222-8222-222222222222"}`)
	cancelled, e := s.Apply(ctx, c)
	if e != nil {
		t.Fatal(e)
	}
	m.OperationID = op(3)
	m.BaseRevision = cancelled.Revision
	if _, e = s.Apply(ctx, m); !errors.Is(e, ErrCancelled) {
		t.Fatalf("cancelled epoch resurrected: %v", e)
	}
	r.EpochID = "22222222-2222-4222-8222-222222222223"
	m.Payload, _ = json.Marshal(r)
	m.OperationID = op(4)
	if _, e = s.Apply(ctx, m); e != nil {
		t.Fatalf("explicit new epoch rejected: %v", e)
	}
}
func TestTransactionRollbackLeavesNoAck(t *testing.T) {
	s := openTest(t)
	_, e := s.db.Exec("CREATE TRIGGER fail_operation BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT,'test transaction interruption'); END;")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Apply(ctx, noteMutation(1, 0, "must roll back")); e == nil {
		t.Fatal("injected transaction failure ignored")
	}
	snap, e := s.List(ctx)
	if e != nil || len(snap.Entities) != 0 || len(snap.DeviceAcks) != 0 {
		t.Fatalf("partial write survived: %+v %v", snap, e)
	}
}
func TestReopenRetainsData(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bridge.db")
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	s.Apply(ctx, noteMutation(1, 0, "persisted"))
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	snap, e := s.List(ctx)
	if e != nil || len(snap.Entities) != 1 {
		t.Fatalf("lost persisted data: %v", e)
	}
}

type failReader struct{}

func (failReader) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }
func TestPartialBlobNotVisible(t *testing.T) {
	s := openTest(t)
	sum := sha256.Sum256([]byte("hello"))
	hash := hex.EncodeToString(sum[:])
	if _, e := s.PutBlob(ctx, io.MultiReader(strings.NewReader("hel"), failReader{}), hash); e == nil {
		t.Fatal("partial upload accepted")
	}
	if _, e := s.BlobPath(ctx, hash); e == nil {
		t.Fatal("partial file became visible")
	}
	ref, e := s.PutBlob(ctx, strings.NewReader("hello"), hash)
	if e != nil || ref.Size != 5 {
		t.Fatalf("valid upload failed: %+v %v", ref, e)
	}
	if _, e = s.PutBlob(ctx, strings.NewReader("wrong"), hash); e == nil {
		t.Fatal("hash mismatch accepted")
	}
}
func TestCorruptDatabaseRejected(t *testing.T) {
	p := filepath.Join(t.TempDir(), "bad.db")
	os.WriteFile(p, []byte("not a sqlite database"), 0600)
	if s, e := Open(p); e == nil {
		s.Close()
		t.Fatal("corrupt database accepted")
	}
}

type zeros struct{}

func (zeros) Read(p []byte) (int, error) { clear(p); return len(p), nil }
func TestOversizedBlobRejected(t *testing.T) {
	s := openTest(t)
	if _, err := s.PutBlob(ctx, io.LimitReader(zeros{}, domain.MaxBlobBytes+1), strings.Repeat("0", 64)); err == nil {
		t.Fatal("oversized audio accepted")
	}
	entries, _ := os.ReadDir(s.blobDir)
	if len(entries) != 0 {
		t.Fatal("rejected upload left a visible or partial file")
	}
}
func TestCorruptedBlobRejected(t *testing.T) {
	s := openTest(t)
	sum := sha256.Sum256([]byte("hello"))
	hash := hex.EncodeToString(sum[:])
	if _, err := s.PutBlob(ctx, strings.NewReader("hello"), hash); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(s.blobDir, hash), []byte("other"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := s.BlobPath(ctx, hash); err == nil {
		t.Fatal("tampered file returned")
	}
}

func TestBackupReopensWithCommittedData(t *testing.T) {
	s := openTest(t)
	if _, err := s.Apply(ctx, noteMutation(1, 0, "backup survives")); err != nil {
		t.Fatal(err)
	}
	backup := filepath.Join(t.TempDir(), "backup.db")
	if err := s.Backup(ctx, backup); err != nil {
		t.Fatal(err)
	}
	b, err := Open(backup)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	snap, err := b.List(ctx)
	if err != nil || len(snap.Entities) != 1 || !strings.Contains(string(snap.Entities[0].Payload), "backup survives") {
		t.Fatalf("invalid backup: %+v %v", snap, err)
	}
	if err = s.Backup(ctx, backup); err == nil {
		t.Fatal("backup overwrote existing file")
	}
}
func TestNewerDatabaseRejectedWithoutChange(t *testing.T) {
	path := filepath.Join(t.TempDir(), "future.db")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec("CREATE TABLE future_data(value TEXT);INSERT INTO future_data VALUES('keep');PRAGMA user_version=2;"); err != nil {
		t.Fatal(err)
	}
	db.Close()
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if s, err := Open(path); err == nil {
		s.Close()
		t.Fatal("newer schema accepted")
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(before) != string(after) {
		t.Fatal("newer database was changed")
	}
}
func TestActiveScheduleLimit(t *testing.T) {
	s := openTest(t)
	for i := 1; i <= 51; i++ {
		id := fmt.Sprintf("44444444-4444-4444-8444-%012d", i)
		r := domain.ScheduleRule{ScheduleID: id, EpochID: epochID, Kind: "daily", LocalTime: "09:00", ZoneID: "UTC", Body: "limit", Enabled: true}
		p, _ := json.Marshal(r)
		m := domain.Mutation{SchemaVersion: 1, OperationID: op(i), EntityType: "schedule", EntityID: id, Action: "upsert", Payload: p}
		_, err := s.Apply(ctx, m)
		if i <= 50 && err != nil {
			t.Fatal(err)
		}
		if i == 51 && err == nil {
			t.Fatal("51 active rules accepted")
		}
	}
}
