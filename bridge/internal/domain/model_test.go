package domain

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestMutationValidationAndLimits(t *testing.T) {
	r := ScheduleRule{ScheduleID: "11111111-1111-4111-8111-111111111111", EpochID: "22222222-2222-4222-8222-222222222222", Kind: "daily", LocalTime: "09:00", ZoneID: "UTC", Enabled: true, Body: strings.Repeat("가", 2000)}
	p, _ := json.Marshal(r)
	m := Mutation{SchemaVersion: 1, OperationID: "33333333-3333-4333-8333-333333333333", EntityType: "schedule", EntityID: r.ScheduleID, Action: "upsert", Payload: p}
	if err := ValidateMutation(m); err != nil {
		t.Fatal(err)
	}
	r.Body += "가"
	m.Payload, _ = json.Marshal(r)
	if ValidateMutation(m) == nil {
		t.Fatal("oversized speech accepted")
	}
	r.Body = "test"
	p, _ = json.Marshal(r)
	m.Payload = append(p[:len(p)-1], []byte(`,"shell":"whoami"}`)...)
	if ValidateMutation(m) == nil {
		t.Fatal("unknown executable field accepted")
	}
	n := Note{NoteID: r.ScheduleID, Text: strings.Repeat("🙂", 32768)}
	m.EntityType = "note"
	m.Payload, _ = json.Marshal(n)
	if err := ValidateMutation(m); err != nil {
		t.Fatal(err)
	}
	n.Text += "🙂"
	m.Payload, _ = json.Marshal(n)
	if ValidateMutation(m) == nil {
		t.Fatal("oversized note accepted")
	}
}
func TestEachInvalidRuleRejected(t *testing.T) {
	valid := ScheduleRule{ScheduleID: "11111111-1111-4111-8111-111111111111", EpochID: "22222222-2222-4222-8222-222222222222", Kind: "daily", LocalTime: "09:00", ZoneID: "UTC", Body: "test", Enabled: true}
	for _, change := range []func(*ScheduleRule){func(r *ScheduleRule) { r.Kind = "monthly" }, func(r *ScheduleRule) { r.LocalTime = "25:90" }, func(r *ScheduleRule) { r.ZoneID = "Unknown/Nowhere" }, func(r *ScheduleRule) { r.Kind = "weekly"; r.WeekDays = nil }} {
		r := valid
		change(&r)
		if ValidateRule(r) == nil {
			t.Fatalf("invalid rule accepted: %+v", r)
		}
	}
}
