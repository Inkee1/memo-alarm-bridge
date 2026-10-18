package domain

import (
	"encoding/json"
	"os"
	"testing"
	"time"
)

func TestRecurrenceGoldenFixtures(t *testing.T) {
	b, err := os.ReadFile("../../../protocol/fixtures/recurrence.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name      string
		Kind      string
		LocalTime string `json:"local_time"`
		ZoneID    string `json:"zone_id"`
		AtUTC     string `json:"at_utc"`
		WeekDays  []int  `json:"week_days"`
		After     string
		Want      []string
	}
	if err = json.Unmarshal(b, &cases); err != nil {
		t.Fatal(err)
	}
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			rule := ScheduleRule{ScheduleID: "11111111-1111-4111-8111-111111111111", EpochID: "22222222-2222-4222-8222-222222222222", Kind: c.Kind, LocalTime: c.LocalTime, ZoneID: c.ZoneID, AtUTC: c.AtUTC, WeekDays: c.WeekDays, Enabled: true, Body: "test"}
			after, _ := time.Parse(time.RFC3339, c.After)
			got, err := NextOccurrences(rule, after, 2)
			if err != nil {
				t.Fatal(err)
			}
			if len(got) != len(c.Want) {
				t.Fatalf("got %d occurrences, want %d", len(got), len(c.Want))
			}
			for i, w := range c.Want {
				if got[i].AtUTC != w {
					t.Errorf("got %s want %s", got[i].AtUTC, w)
				}
			}
			again, _ := NextOccurrences(rule, after, 2)
			if len(got) > 0 && got[0].ID != again[0].ID {
				t.Fatal("occurrence ID changed on replay")
			}
		})
	}
}
func TestInvalidAndOversizedInput(t *testing.T) {
	for _, r := range []ScheduleRule{
		{Kind: "monthly", Enabled: true},
		{Kind: "daily", LocalTime: "25:90", ZoneID: "Asia/Seoul", Enabled: true},
		{Kind: "daily", LocalTime: "09:00", ZoneID: "Unknown/Nowhere", Enabled: true},
		{Kind: "weekly", LocalTime: "09:00", ZoneID: "UTC", Enabled: true},
	} {
		if _, err := NextOccurrences(r, time.Now(), 2); err == nil {
			t.Fatalf("invalid rule accepted: %+v", r)
		}
	}
}
