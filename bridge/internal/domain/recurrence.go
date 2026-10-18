package domain

import (
	"crypto/sha256"
	"fmt"
	"sort"
	"time"
	_ "time/tzdata"
)

func occurrenceID(r ScheduleRule, at string) string {
	b := sha256.Sum256([]byte(r.ScheduleID + "|" + r.EpochID + "|" + at))
	b[6] = (b[6] & 15) | 128
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
func NextOccurrences(r ScheduleRule, after time.Time, count int) ([]Occurrence, error) {
	if err := ValidateRule(r); err != nil {
		return nil, err
	}
	out := []Occurrence{}
	if count < 1 || count > 100 {
		return nil, fmt.Errorf("count must be 1–100")
	}
	if !r.Enabled {
		return out, nil
	}
	add := func(t time.Time) {
		at := t.UTC().Format(time.RFC3339Nano)
		out = append(out, Occurrence{ID: occurrenceID(r, at), AtUTC: at})
	}
	if r.Kind == "one_shot" {
		at, _ := time.Parse(time.RFC3339Nano, r.AtUTC)
		if at.After(after) {
			add(at)
		}
		return out, nil
	}
	loc, _ := time.LoadLocation(r.ZoneID)
	clock, _ := time.Parse("15:04", r.LocalTime)
	local := after.In(loc)
	date := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, time.UTC)
	for i := 0; i < 3700 && len(out) < count; i++ {
		d := date.AddDate(0, 0, i)
		day := int(d.Weekday())
		if day == 0 {
			day = 7
		}
		if r.Kind == "weekly" {
			selected := false
			for _, w := range r.WeekDays {
				selected = selected || w == day
			}
			if !selected {
				continue
			}
		}
		naive := time.Date(d.Year(), d.Month(), d.Day(), clock.Hour(), clock.Minute(), 0, 0, time.UTC)
		at := resolveLocal(naive, loc)
		if at.After(after) {
			add(at)
		}
	}
	return out, nil
}

// Enumerating nearby offsets makes overlap selection and gap shifting explicit,
// rather than relying on time.Date's unspecified choice for nonexistent wall times.
func resolveLocal(naive time.Time, loc *time.Location) time.Time {
	offsets := map[int]bool{}
	for h := -48; h <= 48; h += 6 {
		_, off := naive.Add(time.Duration(h) * time.Hour).In(loc).Zone()
		offsets[off] = true
	}
	exact := []time.Time{}
	var shifted time.Time
	var smallest time.Duration = 1<<63 - 1
	for off := range offsets {
		at := naive.Add(-time.Duration(off) * time.Second)
		v := at.In(loc)
		wall := time.Date(v.Year(), v.Month(), v.Day(), v.Hour(), v.Minute(), v.Second(), 0, time.UTC)
		delta := wall.Sub(naive)
		if delta == 0 {
			exact = append(exact, at)
		} else if delta > 0 && delta < smallest {
			shifted = at
			smallest = delta
		}
	}
	if len(exact) > 0 {
		sort.Slice(exact, func(i, j int) bool { return exact[i].Before(exact[j]) })
		return exact[0]
	}
	return shifted
}
