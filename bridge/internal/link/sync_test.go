package link

import (
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"io"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func syncRig(t *testing.T) (*Pairing, *httptest.Server, string, string) {
	t.Helper()
	p := manager(t)
	p.SetSyncHandler(NewSyncHandler(p.store))
	i, _ := p.CreateInvite(ctx, time.Now().Add(time.Minute))
	d, e := p.RedeemInvite(ctx, PairRequest{Nonce: i.Nonce, DeviceName: "Synthetic sync phone"})
	if e != nil {
		t.Fatal(e)
	}
	ts := httptest.NewTLSServer(p.Handler())
	t.Cleanup(ts.Close)
	return p, ts, d.DeviceID + "." + d.Token, d.DeviceID
}
func syncCall(t *testing.T, ts *httptest.Server, auth string, q SyncRequest) (SyncResponse, int) {
	t.Helper()
	b, _ := json.Marshal(q)
	r := pairedRequest(t, ts, "POST", "/v1/sync", auth, string(b))
	defer r.Body.Close()
	var out SyncResponse
	if r.StatusCode == 200 {
		if e := json.NewDecoder(r.Body).Decode(&out); e != nil {
			t.Fatal(e)
		}
	} else {
		io.Copy(io.Discard, r.Body)
	}
	return out, r.StatusCode
}
func syntheticNote() domain.Mutation {
	return domain.Mutation{SchemaVersion: 1, OperationID: "11111111-1111-4111-8111-111111111111", EntityType: "note", EntityID: "22222222-2222-4222-8222-222222222222", Action: "upsert", Payload: json.RawMessage(`{"note_id":"22222222-2222-4222-8222-222222222222","text":"Synthetic synced note"}`)}
}
func TestSyncDuplicateRetryStable(t *testing.T) {
	p, ts, auth, _ := syncRig(t)
	q := SyncRequest{SchemaVersion: 1, Operations: []domain.Mutation{syntheticNote()}}
	a, code := syncCall(t, ts, auth, q)
	if code != 200 {
		t.Fatal(code)
	}
	b, code := syncCall(t, ts, auth, q)
	aa, _ := json.Marshal(a.Results)
	bb, _ := json.Marshal(b.Results)
	if code != 200 || string(aa) != string(bb) || a.Cursor != b.Cursor || len(a.Changes) != 1 {
		t.Fatal("duplicate changed durable result")
	}
	snap, _ := p.store.List(ctx)
	if len(snap.Entities) != 1 || len(snap.DeviceAcks) != 0 {
		t.Fatal("save was confused with phone registration")
	}
}
func TestSyncConflictKeepsSavedVersion(t *testing.T) {
	_, ts, auth, _ := syncRig(t)
	m := syntheticNote()
	a, _ := syncCall(t, ts, auth, SyncRequest{SchemaVersion: 1, Operations: []domain.Mutation{m}})
	m.OperationID = "11111111-1111-4111-8111-111111111112"
	m.Payload = json.RawMessage(`{"note_id":"22222222-2222-4222-8222-222222222222","text":"stale"}`)
	b, code := syncCall(t, ts, auth, SyncRequest{SchemaVersion: 1, Cursor: a.Cursor, Operations: []domain.Mutation{m}})
	if code != 200 || b.Results[0].Status != "conflict" || !strings.Contains(string(b.Results[0].Current.Payload), "Synthetic") {
		t.Fatal("conflict lost original")
	}
}
func TestSyncRejectsForeignAndEmptyArmedAck(t *testing.T) {
	_, ts, auth, id := syncRig(t)
	ack := domain.DeviceAck{DeviceID: "33333333-3333-4333-8333-333333333333", ScheduleID: "22222222-2222-4222-8222-222222222222", EpochID: "44444444-4444-4444-8444-444444444444", Revision: 1, LocalGeneration: 1, State: "armed", ObservedAt: time.Now().UTC().Format(time.RFC3339), Capabilities: map[string]string{"exact_alarm": "granted"}}
	if _, code := syncCall(t, ts, auth, SyncRequest{SchemaVersion: 1, Acks: []domain.DeviceAck{ack}}); code != 400 {
		t.Fatal("foreign ACK accepted")
	}
	ack.DeviceID = id
	if _, code := syncCall(t, ts, auth, SyncRequest{SchemaVersion: 1, Acks: []domain.DeviceAck{ack}}); code != 400 {
		t.Fatal("empty armed ACK accepted")
	}
}
func TestSyncRevocationDoesNotClaimCancellation(t *testing.T) {
	p, ts, auth, id := syncRig(t)
	m := syntheticNote()
	if _, code := syncCall(t, ts, auth, SyncRequest{SchemaVersion: 1, Operations: []domain.Mutation{m}}); code != 200 {
		t.Fatal(code)
	}
	if e := p.store.RevokeDevice(ctx, id); e != nil {
		t.Fatal(e)
	}
	if _, code := syncCall(t, ts, auth, SyncRequest{SchemaVersion: 1}); code != 401 {
		t.Fatal("revoked sync accepted")
	}
	snap, _ := p.store.List(ctx)
	if len(snap.DeviceAcks) != 0 {
		t.Fatal("revocation invented ACK")
	}
}
func TestSyncCursorCannotSkipFutureData(t *testing.T) {
	_, ts, auth, _ := syncRig(t)
	if _, code := syncCall(t, ts, auth, SyncRequest{SchemaVersion: 1, Cursor: 100}); code != 409 {
		t.Fatal("future cursor accepted")
	}
}
