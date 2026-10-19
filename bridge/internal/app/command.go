package app

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"strings"
	"time"
)

const MaxCommandBytes = 128 * 1024

type Command struct {
	SchemaVersion int             `json:"schema_version"`
	Method        string          `json:"method"`
	Params        json.RawMessage `json:"params"`
}
type CommandError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}
type CommandResult struct {
	SchemaVersion int           `json:"schema_version"`
	OK            bool          `json:"ok"`
	Result        any           `json:"result,omitempty"`
	Error         *CommandError `json:"error,omitempty"`
}

func Failure(code, message string) CommandResult {
	return CommandResult{SchemaVersion: 1, Error: &CommandError{Code: code, Message: message}}
}
func success(v any) CommandResult { return CommandResult{SchemaVersion: 1, OK: true, Result: v} }
func (s *Service) Dispatch(ctx context.Context, c Command) CommandResult {
	if c.SchemaVersion != 1 {
		return Failure("invalid_input", "schema_version must be 1")
	}
	if len(c.Params) == 0 || string(c.Params) == "null" {
		return Failure("invalid_input", "params must be an object")
	}
	switch c.Method {
	case "app.open", "app.stop":
		if e := domain.DecodeStrict(c.Params, &struct{}{}); e != nil {
			return Failure("invalid_input", e.Error())
		}
		if s.ControlCommand == nil {
			return Failure("unavailable", "process control unavailable")
		}
		value, e := s.ControlCommand(ctx, c.Method)
		if e != nil {
			return Failure("process_error", e.Error())
		}
		return success(value)
	case "link.status", "link.enable", "link.invite", "link.disable":
		if s.LinkCommand == nil {
			return Failure("unavailable", "device link is unavailable")
		}
		v, e := s.LinkCommand(ctx, c.Method, c.Params)
		if e != nil {
			return Failure("connection_error", e.Error())
		}
		return success(v)
	case "note.upsert", "note.delete", "schedule.upsert", "schedule.cancel":
		var m domain.Mutation
		if e := domain.DecodeStrict(c.Params, &m); e != nil {
			return Failure("invalid_input", e.Error())
		}
		parts := strings.Split(c.Method, ".")
		if m.EntityType != parts[0] || m.Action != parts[1] {
			return Failure("invalid_input", "method and mutation differ")
		}
		if e := domain.ValidateMutation(m); e != nil {
			return Failure("invalid_input", e.Error())
		}
		r, e := s.Apply(ctx, m)
		if errors.Is(e, store.ErrConflict) {
			v := Failure("conflict", "preserve both versions and choose which to keep")
			v.Result = r
			return v
		}
		if errors.Is(e, store.ErrCancelled) {
			return Failure("cancelled_epoch", "create a new epoch to explicitly enable this schedule")
		}
		if errors.Is(e, store.ErrOperationReuse) {
			return Failure("operation_reused", "retry an operation only with its original content")
		}
		if e != nil {
			return Failure("storage_error", "could not save; check storage and input limits")
		}
		return success(r)
	case "schedule.preview":
		var p struct {
			Rule     domain.ScheduleRule `json:"rule"`
			AfterUTC string              `json:"after_utc"`
			Count    int                 `json:"count"`
		}
		if e := domain.DecodeStrict(c.Params, &p); e != nil {
			return Failure("invalid_input", e.Error())
		}
		after, e := time.Parse(time.RFC3339Nano, p.AfterUTC)
		if e != nil {
			return Failure("invalid_input", "valid after_utc required")
		}
		r, e := domain.NextOccurrences(p.Rule, after, p.Count)
		if e != nil {
			return Failure("invalid_input", e.Error())
		}
		return success(r)
	case "note.list", "schedule.list", "export", "doctor", "device.list":
		if e := domain.DecodeStrict(c.Params, &struct{}{}); e != nil {
			return Failure("invalid_input", e.Error())
		}
		if c.Method == "doctor" {
			return success(s.Doctor(ctx))
		}
		if c.Method == "device.list" {
			r, e := s.Store.Devices(ctx)
			if e != nil {
				return Failure("storage_error", "could not read devices")
			}
			return success(r)
		}
		snap, e := s.List(ctx)
		if e != nil {
			return Failure("storage_error", "could not read local data")
		}
		if c.Method != "export" {
			filtered := []domain.Entity{}
			typ := strings.Split(c.Method, ".")[0]
			for _, e := range snap.Entities {
				if e.EntityType == typ && !e.Deleted {
					filtered = append(filtered, e)
				}
			}
			snap.Entities = filtered
		}
		return success(snap)
	case "device.revoke":
		var p struct {
			DeviceID string `json:"device_id"`
		}
		if e := domain.DecodeStrict(c.Params, &p); e != nil || !domain.ValidID(p.DeviceID) {
			return Failure("invalid_input", "valid device_id required")
		}
		if e := s.Store.RevokeDevice(ctx, p.DeviceID); e != nil {
			return Failure("storage_error", "device could not be revoked")
		}
		return success(map[string]string{"state": "revoked", "warning": "existing alarms on the phone remain until cancelled there"})
	default:
		return Failure("unknown_method", "unsupported method")
	}
}
