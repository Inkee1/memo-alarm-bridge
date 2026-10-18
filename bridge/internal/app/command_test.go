package app

import (
	"context"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"path/filepath"
	"strings"
	"testing"
)

func commandService(t *testing.T) *Service {
	t.Helper()
	s, e := store.Open(filepath.Join(t.TempDir(), "app.db"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	return New(s)
}
func TestCliPersistsWithoutDot(t *testing.T) {
	s := commandService(t)
	raw := []byte(`{"schema_version":1,"method":"note.upsert","params":{"schema_version":1,"operation_id":"11111111-1111-4111-8111-111111111111","entity_type":"note","entity_id":"22222222-2222-4222-8222-222222222222","base_revision":0,"action":"upsert","payload":{"note_id":"22222222-2222-4222-8222-222222222222","text":"literal $(echo nope) ; 한국어"}}}`)
	var cmd Command
	if e := domain.DecodeStrict(raw, &cmd); e != nil {
		t.Fatal(e)
	}
	r := s.Dispatch(context.Background(), cmd)
	if !r.OK {
		t.Fatalf("save failed: %+v", r)
	}
	r = s.Dispatch(context.Background(), Command{SchemaVersion: 1, Method: "note.list", Params: json.RawMessage(`{}`)})
	b, _ := json.Marshal(r)
	if !r.OK || !strings.Contains(string(b), "literal $(echo nope) ; 한국어") {
		t.Fatalf("missing literal content: %s", b)
	}
}
func TestCliDoesNotClaimArmed(t *testing.T) {
	s := commandService(t)
	raw := []byte(`{"schema_version":1,"method":"schedule.upsert","params":{"schema_version":1,"operation_id":"11111111-1111-4111-8111-111111111111","entity_type":"schedule","entity_id":"22222222-2222-4222-8222-222222222222","base_revision":0,"action":"upsert","payload":{"schedule_id":"22222222-2222-4222-8222-222222222222","epoch_id":"33333333-3333-4333-8333-333333333333","kind":"one_shot","at_utc":"2030-01-01T00:00:00Z","body":"test","enabled":true}}}`)
	var cmd Command
	domain.DecodeStrict(raw, &cmd)
	r := s.Dispatch(context.Background(), cmd)
	b, _ := json.Marshal(r)
	if !r.OK || !strings.Contains(string(b), "pending_delivery") || strings.Contains(string(b), `"armed"`) {
		t.Fatalf("false ready claim: %s", b)
	}
}
func TestUnknownFieldsRejected(t *testing.T) {
	s := commandService(t)
	r := s.Dispatch(context.Background(), Command{SchemaVersion: 1, Method: "note.list", Params: json.RawMessage(`{"exec":"anything"}`)})
	if r.OK {
		t.Fatal("unknown params accepted")
	}
	var c Command
	if domain.DecodeStrict([]byte(`{"schema_version":1,"method":"doctor","params":{},"exec":"anything"}`), &c) == nil {
		t.Fatal("unknown command field accepted")
	}
}

func TestDeleteRejectsUnknownPayloadFields(t *testing.T) {
	s := commandService(t)
	r := s.Dispatch(context.Background(), Command{SchemaVersion: 1, Method: "note.delete", Params: json.RawMessage(`{"schema_version":1,"operation_id":"11111111-1111-4111-8111-111111111111","entity_type":"note","entity_id":"22222222-2222-4222-8222-222222222222","base_revision":0,"action":"delete","payload":{"exec":"ignored-but-not-allowed"}}`)})
	if r.OK {
		t.Fatal("delete accepted unknown payload")
	}
}
