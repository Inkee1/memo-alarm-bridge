package store

import (
	"encoding/json"
	"fmt"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"testing"
	"time"
)

func TestExpiredOneShotsDoNotConsumeActiveLimit(t *testing.T) {
	s := openTest(t)
	for i := 1; i <= 51; i++ {
		id := fmt.Sprintf("44444444-4444-4444-8444-%012d", i)
		r := domain.ScheduleRule{ScheduleID: id, EpochID: epochID, Kind: "one_shot", AtUTC: "2000-01-01T00:00:00Z", Body: "Past synthetic reminder", Enabled: true}
		p, _ := json.Marshal(r)
		_, e := s.Apply(ctx, domain.Mutation{SchemaVersion: 1, OperationID: op(i), EntityType: "schedule", EntityID: id, Action: "upsert", Payload: p})
		if e != nil {
			t.Fatal(e)
		}
	}
}

func TestConflictRetryKeepsOriginalConflictSnapshot(t *testing.T) {
	s := openTest(t)
	a, _ := s.Apply(ctx, noteMutation(1, 0, "first"))
	conflict, _ := s.Apply(ctx, noteMutation(2, 0, "stale"))
	s.Apply(ctx, noteMutation(3, a.Revision, "later"))
	retry, e := s.Apply(ctx, noteMutation(2, 0, "stale"))
	first, _ := json.Marshal(conflict)
	second, _ := json.Marshal(retry)
	if e != ErrConflict || string(first) != string(second) {
		t.Fatal("conflict retry changed its recorded result")
	}
}
func TestCancelOldEpochReturnsActualEntityRevision(t *testing.T) {
	s := openTest(t)
	r := domain.ScheduleRule{ScheduleID: noteID, EpochID: epochID, Kind: "daily", LocalTime: "09:00", ZoneID: "UTC", Body: "Reminder", Enabled: true}
	p, _ := json.Marshal(r)
	a, e := s.Apply(ctx, domain.Mutation{SchemaVersion: 1, OperationID: op(1), EntityType: "schedule", EntityID: noteID, Action: "upsert", Payload: p})
	if e != nil {
		t.Fatal(e)
	}
	cancel := domain.Mutation{SchemaVersion: 1, OperationID: op(2), EntityType: "schedule", EntityID: noteID, Action: "cancel", Payload: json.RawMessage(`{"epoch_id":"22222222-2222-4222-8222-222222222223"}`)}
	result, e := s.Apply(ctx, cancel)
	if e != nil || result.Entity == nil || result.Entity.Revision != a.Revision || result.Revision <= a.Revision {
		t.Fatalf("wrong CAS base %+v %v", result, e)
	}
}
func TestAckIsMonotonicAndNeverCreatedByStoreSave(t *testing.T) {
	s := openTest(t)
	deviceID := "55555555-5555-4555-8555-555555555555"
	if e := s.RegisterDevice(ctx, deviceID, "Synthetic phone", "synthetic-token-with-at-least-forty-characters"); e != nil {
		t.Fatal(e)
	}
	r := domain.ScheduleRule{ScheduleID: noteID, EpochID: epochID, Kind: "daily", LocalTime: "09:00", ZoneID: "UTC", Body: "Reminder", Enabled: true}
	p, _ := json.Marshal(r)
	saved, e := s.Apply(ctx, domain.Mutation{SchemaVersion: 1, OperationID: op(1), EntityType: "schedule", EntityID: noteID, Action: "upsert", Payload: p})
	if e != nil {
		t.Fatal(e)
	}
	snap, _ := s.List(ctx)
	if len(snap.DeviceAcks) != 0 {
		t.Fatal("save generated ACK")
	}
	a := domain.DeviceAck{DeviceID: deviceID, ScheduleID: noteID, EpochID: epochID, Revision: saved.Revision, LocalGeneration: 3, State: "preparing", ObservedAt: time.Now().UTC().Format(time.RFC3339Nano), Capabilities: map[string]string{"exact_alarm": "granted"}}
	if e = s.SaveAck(ctx, deviceID, a); e != nil {
		t.Fatal(e)
	}
	a.LocalGeneration = 2
	a.State = "blocked_audio"
	if e = s.SaveAck(ctx, deviceID, a); e != nil {
		t.Fatal(e)
	}
	snap, _ = s.List(ctx)
	if len(snap.DeviceAcks) != 1 || snap.DeviceAcks[0].LocalGeneration != 3 {
		t.Fatal("old ACK replaced newer state")
	}
}
