// Protocol harness only: it does not run Android, synthesize speech or register OS alarms.
package integration

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/link"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestTLSDeviceReconnectReplayOfflineCancelAndRevoke(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "bridge.db")
	db, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { db.Close() }()
	type route struct{ handler http.Handler }
	var current atomic.Value
	current.Store(route{http.NotFoundHandler()})
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { current.Load().(route).handler.ServeHTTP(w, r) }))
	server.StartTLS()
	defer server.Close()
	sum := sha256.Sum256(server.Certificate().Raw)
	pin := hex.EncodeToString(sum[:])
	pairing, err := link.NewPairing(db, server.URL, pin)
	if err != nil {
		t.Fatal(err)
	}
	pairing.SetSyncHandler(link.NewSyncHandler(db))
	current.Store(route{pairing.Handler()})
	client, err := link.NewPinnedClient(server.URL, pin)
	if err != nil {
		t.Fatal(err)
	}
	defer client.CloseIdleConnections()
	invite, err := pairing.CreateInvite(ctx, time.Now().Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	request := func(path string, value any, auth string) *http.Response {
		t.Helper()
		body, _ := json.Marshal(value)
		r, _ := http.NewRequest("POST", server.URL+path, bytes.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		if auth != "" {
			r.Header.Set("Authorization", "Bearer "+auth)
		}
		out, e := client.Do(r)
		if e != nil {
			t.Fatal(e)
		}
		return out
	}
	paired := request("/v1/pair", link.PairRequest{Nonce: invite.Nonce, DeviceName: "Synthetic protocol device"}, "")
	var credential link.DeviceCredential
	if paired.StatusCode != 200 {
		t.Fatal(paired.StatusCode)
	}
	err = json.NewDecoder(paired.Body).Decode(&credential)
	paired.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	auth := credential.DeviceID + "." + credential.Token
	raw, err := os.ReadFile("../../protocol/fixtures/sync.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Note  domain.Mutation `json:"note"`
		Alarm domain.Mutation `json:"alarm"`
	}
	if err = json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	q := link.SyncRequest{SchemaVersion: 1, Operations: []domain.Mutation{fixture.Note, fixture.Alarm}}
	// Discard a successful HTTP response, then restart the persistent server store.
	lost := request("/v1/sync", q, auth)
	if lost.StatusCode != 200 {
		t.Fatal(lost.StatusCode)
	}
	io.Copy(io.Discard, lost.Body)
	lost.Body.Close()
	client.CloseIdleConnections()
	db.Close()
	db, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	pairing, err = link.NewPairing(db, server.URL, pin)
	if err != nil {
		t.Fatal(err)
	}
	pairing.SetSyncHandler(link.NewSyncHandler(db))
	current.Store(route{pairing.Handler()})
	exchange := func(q link.SyncRequest) link.SyncResponse {
		t.Helper()
		r := request("/v1/sync", q, auth)
		defer r.Body.Close()
		if r.StatusCode != 200 {
			b, _ := io.ReadAll(r.Body)
			t.Fatalf("sync %d: %s", r.StatusCode, b)
		}
		var out link.SyncResponse
		if e := json.NewDecoder(r.Body).Decode(&out); e != nil {
			t.Fatal(e)
		}
		return out
	}
	response := exchange(q)
	if response.Cursor != 2 || len(response.Results) != 2 || response.Results[0].Revision != 1 || response.Results[1].Revision != 2 {
		t.Fatalf("retry duplicated data: %#v", response)
	}
	snapshot, _ := db.List(ctx)
	if len(snapshot.Entities) != 2 || len(snapshot.DeviceAcks) != 0 {
		t.Fatal("save invented device registration")
	}
	var rule domain.ScheduleRule
	if err = json.Unmarshal(fixture.Alarm.Payload, &rule); err != nil {
		t.Fatal(err)
	}
	occurrences, err := domain.NextOccurrences(rule, time.Now(), 1)
	if err != nil {
		t.Fatal(err)
	}
	// Deliberately synthetic evidence tests storage semantics, never Android readiness.
	ack := domain.DeviceAck{DeviceID: credential.DeviceID, ScheduleID: rule.ScheduleID, EpochID: rule.EpochID, Revision: 2, LocalGeneration: 1, State: "armed", ObservedAt: time.Now().UTC().Format(time.RFC3339Nano), Capabilities: map[string]string{"exact_alarm": "granted"}, ArmedOccurrences: []domain.Occurrence{{ID: occurrences[0].ID, AtUTC: occurrences[0].AtUTC, AudioSHA256: strings.Repeat("a", 64)}}}
	response = exchange(link.SyncRequest{SchemaVersion: 1, Cursor: response.Cursor, Acks: []domain.DeviceAck{ack}})
	cancelPayload, _ := json.Marshal(map[string]string{"epoch_id": rule.EpochID})
	cancel := domain.Mutation{SchemaVersion: 1, OperationID: "11111111-1111-4111-8111-111111111113", EntityType: "schedule", EntityID: rule.ScheduleID, BaseRevision: 2, Action: "cancel", Payload: cancelPayload}
	result, err := db.Apply(ctx, cancel)
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "cancel_pending" {
		t.Fatal(result.Status)
	}
	snapshot, _ = db.List(ctx)
	if snapshot.DeviceAcks[0].State != "armed" {
		t.Fatal("offline phone cancellation invented")
	}
	response = exchange(link.SyncRequest{SchemaVersion: 1, Cursor: response.Cursor})
	if len(response.Changes) != 1 || response.Changes[0].Mutation.Action != "cancel" {
		t.Fatal("reconnect lost cancellation")
	}
	ack.Revision = response.Cursor
	ack.LocalGeneration++
	ack.State = "cancelled"
	ack.ArmedOccurrences = nil
	ack.ObservedAt = time.Now().UTC().Format(time.RFC3339Nano)
	exchange(link.SyncRequest{SchemaVersion: 1, Cursor: response.Cursor, Acks: []domain.DeviceAck{ack}})
	stale := fixture.Alarm
	stale.OperationID = "11111111-1111-4111-8111-111111111114"
	stale.BaseRevision = response.Cursor
	rejected := exchange(link.SyncRequest{SchemaVersion: 1, Cursor: response.Cursor, Operations: []domain.Mutation{stale}})
	if rejected.Results[0].Status != "cancelled" {
		t.Fatal("old epoch resurrected")
	}
	if err = db.RevokeDevice(ctx, credential.DeviceID); err != nil {
		t.Fatal(err)
	}
	denied := request("/v1/sync", link.SyncRequest{SchemaVersion: 1}, auth)
	denied.Body.Close()
	if denied.StatusCode != 401 {
		t.Fatal("revoked access remained")
	}
	snapshot, _ = db.List(ctx)
	if snapshot.DeviceAcks[0].State != "cancelled" {
		t.Fatal("revoke rewrote device report")
	}
}
