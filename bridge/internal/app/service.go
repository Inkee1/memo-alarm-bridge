package app

import (
	"context"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"io"
	"time"
)

type Service struct {
	Store          *store.Store
	LinkCommand    func(context.Context, string, json.RawMessage) (any, error)
	ControlCommand func(context.Context, string) (any, error)
}

func New(s *store.Store) *Service { return &Service{Store: s} }
func (s *Service) Apply(ctx context.Context, m domain.Mutation) (domain.MutationResult, error) {
	return s.Store.Apply(ctx, m)
}
func (s *Service) List(ctx context.Context) (domain.Snapshot, error) { return s.Store.List(ctx) }
func (s *Service) PutBlob(ctx context.Context, r io.Reader, hash string) (store.BlobRef, error) {
	return s.Store.PutBlob(ctx, r, hash)
}
func (s *Service) Doctor(ctx context.Context) domain.CapabilityReport {
	r := domain.CapabilityReport{ExactAlarm: "unknown", Notifications: "unknown", OfflineVoice: "unknown", Storage: "unknown", ObservedAt: time.Now().UTC().Format(time.RFC3339Nano)}
	if s.Store.Ping(ctx) == nil {
		r.Storage = "granted"
	}
	return r
}
