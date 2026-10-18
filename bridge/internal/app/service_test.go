package app

import (
	"context"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"path/filepath"
	"testing"
	"time"
)

func TestDoctorReportsUnavailableStorage(t *testing.T) {
	s, err := store.Open(filepath.Join(t.TempDir(), "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	a := New(s)
	r := a.Doctor(context.Background())
	if r.Storage != "granted" || r.ExactAlarm != "unknown" || r.OfflineVoice != "unknown" {
		t.Fatalf("unsupported health claim: %+v", r)
	}
	if _, err = time.Parse(time.RFC3339Nano, r.ObservedAt); err != nil {
		t.Fatal(err)
	}
	s.Close()
	if r = a.Doctor(context.Background()); r.Storage != "unknown" {
		t.Fatalf("closed database reported healthy: %+v", r)
	}
}
