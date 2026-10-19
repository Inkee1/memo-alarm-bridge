package main

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/ipc"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"os"
	"path/filepath"
	"testing"
)

func TestDocumentedCLIExamplesNeverInventArmedState(t *testing.T) {
	dir := t.TempDir()
	s, err := store.Open(filepath.Join(dir, "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	listener, err := ipc.Listen(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go ipc.ServeLocal(ctx, listener, app.New(s))
	for _, name := range []string{"create-alarm", "status", "cancel-alarm"} {
		raw, e := os.ReadFile("../../../examples/" + name + ".json")
		if e != nil {
			t.Fatal(e)
		}
		var out, errout bytes.Buffer
		if code := Run([]string{"request", "--json", "--data", dir}, bytes.NewReader(raw), &out, &errout); code != 0 {
			t.Fatalf("%s failed: %s %s", name, out.String(), errout.String())
		}
		var result app.CommandResult
		if err = json.Unmarshal(out.Bytes(), &result); err != nil || !result.OK {
			t.Fatal("invalid CLI response")
		}
		if bytes.Contains(out.Bytes(), []byte(`"state":"armed"`)) {
			t.Fatal("PC save invented phone registration")
		}
	}
	snapshot, err := s.List(ctx)
	if err != nil || len(snapshot.DeviceAcks) != 0 {
		t.Fatal("CLI created an ACK")
	}
}
