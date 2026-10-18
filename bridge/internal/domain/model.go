package domain

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"time"
	"unicode/utf8"
)

const MaxBlobBytes int64 = 25 * 1024 * 1024

var idPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
var hashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func ValidID(id string) bool  { return idPattern.MatchString(id) }
func ValidHash(s string) bool { return hashPattern.MatchString(s) }

type Note struct {
	NoteID          string `json:"note_id"`
	Text            string `json:"text"`
	AudioBlobSHA256 string `json:"audio_blob_sha256,omitempty"`
	CreatedAt       string `json:"created_at,omitempty"`
	UpdatedAt       string `json:"updated_at,omitempty"`
}
type ScheduleRule struct {
	ScheduleID    string `json:"schedule_id"`
	EpochID       string `json:"epoch_id"`
	Kind          string `json:"kind"`
	AtUTC         string `json:"at_utc,omitempty"`
	LocalTime     string `json:"local_time,omitempty"`
	ZoneID        string `json:"zone_id,omitempty"`
	WeekDays      []int  `json:"week_days,omitempty"`
	Body          string `json:"body"`
	Enabled       bool   `json:"enabled"`
	RespectSilent bool   `json:"respect_silent,omitempty"`
}
type Occurrence struct {
	ID          string `json:"id"`
	AtUTC       string `json:"at_utc"`
	AudioSHA256 string `json:"audio_sha256,omitempty"`
}
type Mutation struct {
	SchemaVersion int             `json:"schema_version"`
	OperationID   string          `json:"operation_id"`
	EntityType    string          `json:"entity_type"`
	EntityID      string          `json:"entity_id"`
	BaseRevision  int64           `json:"base_revision"`
	Action        string          `json:"action"`
	Payload       json.RawMessage `json:"payload"`
}
type Entity struct {
	EntityType string          `json:"entity_type"`
	EntityID   string          `json:"entity_id"`
	Revision   int64           `json:"server_revision"`
	Payload    json.RawMessage `json:"payload"`
	Deleted    bool            `json:"deleted"`
}
type MutationResult struct {
	OperationID string  `json:"operation_id"`
	Revision    int64   `json:"server_revision"`
	Status      string  `json:"status"`
	Current     *Entity `json:"conflict_current,omitempty"`
}
type DeviceAck struct {
	DeviceID         string            `json:"device_id"`
	ScheduleID       string            `json:"schedule_id"`
	EpochID          string            `json:"epoch_id"`
	Revision         int64             `json:"server_revision"`
	LocalGeneration  int64             `json:"local_generation"`
	State            string            `json:"state"`
	ArmedOccurrences []Occurrence      `json:"armed_occurrences"`
	ObservedAt       string            `json:"observed_at"`
	Capabilities     map[string]string `json:"capabilities"`
}
type Snapshot struct {
	SchemaVersion int         `json:"schema_version"`
	Entities      []Entity    `json:"entities"`
	DeviceAcks    []DeviceAck `json:"device_acks"`
}

func DecodeStrict(data []byte, v any) error {
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		return err
	}
	if err := d.Decode(new(any)); err != io.EOF {
		return errors.New("one JSON object required")
	}
	return nil
}
func ValidateRule(r ScheduleRule) error {
	if !ValidID(r.ScheduleID) || !ValidID(r.EpochID) {
		return errors.New("valid schedule_id and epoch_id required")
	}
	if utf8.RuneCountInString(r.Body) == 0 || utf8.RuneCountInString(r.Body) > 2000 {
		return errors.New("speech must be 1–2000 characters")
	}
	switch r.Kind {
	case "one_shot":
		if _, err := time.Parse(time.RFC3339Nano, r.AtUTC); err != nil {
			return errors.New("valid at_utc required")
		}
	case "daily", "weekly":
		if len(r.LocalTime) != 5 {
			return errors.New("local_time must be HH:mm")
		}
		if _, err := time.Parse("15:04", r.LocalTime); err != nil {
			return err
		}
		if r.ZoneID == "" {
			return errors.New("zone_id required")
		}
		if _, err := time.LoadLocation(r.ZoneID); err != nil {
			return errors.New("unknown IANA zone")
		}
		if r.Kind == "weekly" {
			if len(r.WeekDays) == 0 || len(r.WeekDays) > 7 {
				return errors.New("select weekdays")
			}
			seen := map[int]bool{}
			for _, d := range r.WeekDays {
				if d < 1 || d > 7 || seen[d] {
					return errors.New("invalid or duplicate weekday")
				}
				seen[d] = true
			}
		}
	default:
		return errors.New("unsupported recurrence")
	}
	return nil
}
func ValidateMutation(m Mutation) error {
	if m.SchemaVersion != 1 || !ValidID(m.OperationID) || !ValidID(m.EntityID) || m.BaseRevision < 0 {
		return errors.New("invalid mutation envelope")
	}
	if m.EntityType != "note" && m.EntityType != "schedule" {
		return errors.New("unknown entity type")
	}
	if m.Action == "delete" && m.EntityType == "note" {
		if string(m.Payload) == "null" || len(m.Payload) == 0 {
			return errors.New("delete payload must be an empty object")
		}
		return DecodeStrict(m.Payload, &struct{}{})
	}
	if m.Action == "cancel" && m.EntityType == "schedule" {
		var p struct {
			EpochID string `json:"epoch_id"`
		}
		if err := DecodeStrict(m.Payload, &p); err != nil {
			return err
		}
		if !ValidID(p.EpochID) {
			return errors.New("epoch_id required")
		}
		return nil
	}
	if m.Action != "upsert" {
		return errors.New("invalid action")
	}
	if m.EntityType == "note" {
		var n Note
		if err := DecodeStrict(m.Payload, &n); err != nil {
			return err
		}
		if n.NoteID != m.EntityID {
			return errors.New("note ID mismatch")
		}
		if utf8.RuneCountInString(n.Text) > 32768 {
			return errors.New("note exceeds 32768 characters")
		}
		if n.AudioBlobSHA256 != "" && !ValidHash(n.AudioBlobSHA256) {
			return errors.New("invalid audio hash")
		}
		return nil
	}
	var r ScheduleRule
	if err := DecodeStrict(m.Payload, &r); err != nil {
		return err
	}
	if r.ScheduleID != m.EntityID {
		return fmt.Errorf("schedule ID mismatch")
	}
	return ValidateRule(r)
}
