package main

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/ipc"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"path/filepath"
	"strings"
	"testing"
)

func TestRunRequestsJSON(t *testing.T) {
	dir := t.TempDir()
	s, e := store.Open(filepath.Join(dir, "bridge.db"))
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	l, e := ipc.Listen(dir)
	if e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go ipc.ServeLocal(ctx, l, app.New(s))
	var out, errout bytes.Buffer
	code := Run([]string{"request", "--json", "--data", dir}, strings.NewReader(`{"schema_version":1,"method":"doctor","params":{}}`), &out, &errout)
	var r app.CommandResult
	if e = json.Unmarshal(out.Bytes(), &r); e != nil || code != 0 || !r.OK {
		t.Fatalf("CLI failed: code=%d output=%s stderr=%s error=%v", code, out.String(), errout.String(), e)
	}
	out.Reset()
	code = Run([]string{"request", "--json", "--data", dir}, strings.NewReader(`{} trailing`), &out, &errout)
	if code == 0 {
		t.Fatal("invalid JSON succeeded")
	}
}
