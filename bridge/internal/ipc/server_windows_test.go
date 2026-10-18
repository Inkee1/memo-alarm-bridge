package ipc

import (
	"context"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"path/filepath"
	"testing"
	"time"
)

func TestNamedPipePersistsAndRejectsUnknownFields(t *testing.T) {
	dir := t.TempDir()
	s, e := store.Open(filepath.Join(dir, "data.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	l, e := Listen(dir)
	if e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- ServeLocal(ctx, l, app.New(s)) }()
	defer func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("pipe did not close")
		}
	}()
	raw := []byte(`{"schema_version":1,"method":"note.upsert","params":{"schema_version":1,"operation_id":"11111111-1111-4111-8111-111111111111","entity_type":"note","entity_id":"22222222-2222-4222-8222-222222222222","base_revision":0,"action":"upsert","payload":{"note_id":"22222222-2222-4222-8222-222222222222","text":"from real pipe"}}}`)
	r, e := Request(ctx, dir, raw)
	if e != nil || !r.OK {
		t.Fatalf("pipe save failed: %+v %v", r, e)
	}
	snap, e := s.List(ctx)
	if e != nil || len(snap.Entities) != 1 {
		t.Fatal("pipe did not persist")
	}
	r, e = Request(ctx, dir, []byte(`{"schema_version":1,"method":"export","params":{},"exec":"anything"}`))
	if e != nil {
		t.Fatal(e)
	}
	if r.OK || r.Error == nil || r.Error.Code != "invalid_input" {
		b, _ := json.Marshal(r)
		t.Fatalf("unknown field accepted: %s", b)
	}
}
