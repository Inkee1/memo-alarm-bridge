package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/ipc"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/link"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/web"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"time"
)

var version = "0.1.0-dev"

func main() { os.Exit(Run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr)) }
func Run(args []string, in io.Reader, out, errout io.Writer) int {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()
	return run(ctx, args, in, out, errout)
}
func run(ctx context.Context, args []string, in io.Reader, out, errout io.Writer) int {
	if len(args) == 0 {
		args = []string{"serve"}
	}
	if args[0] == "version" {
		fmt.Fprintln(out, version)
		return 0
	}
	if args[0] != "serve" && args[0] != "request" && args[0] != "doctor" {
		fmt.Fprintln(errout, "Usage: memo-alarm-bridge [serve|request --json|doctor|version] [--data directory]")
		return 2
	}
	f := flag.NewFlagSet(args[0], flag.ContinueOnError)
	f.SetOutput(errout)
	root, _ := os.UserConfigDir()
	data := f.String("data", filepath.Join(root, "MemoAlarmBridge", "data"), "private data directory")
	jsonInput := f.Bool("json", false, "read versioned command JSON from stdin")
	port := f.Int("port", 0, "loopback UI port (0 selects a free port)")
	if e := f.Parse(args[1:]); e != nil {
		return 2
	}
	if f.NArg() != 0 {
		fmt.Fprintln(errout, "Unexpected arguments")
		return 2
	}
	if args[0] == "serve" {
		if e := serve(ctx, *data, *port, out); e != nil {
			fmt.Fprintln(errout, "Bridge could not start:", e)
			return 1
		}
		return 0
	}
	var raw []byte
	var e error
	if args[0] == "doctor" {
		raw = []byte(`{"schema_version":1,"method":"doctor","params":{}}`)
	} else {
		if !*jsonInput {
			fmt.Fprintln(errout, "request requires --json; send content through stdin")
			return 2
		}
		raw, e = io.ReadAll(io.LimitReader(in, app.MaxCommandBytes+1))
		if e != nil || len(raw) > app.MaxCommandBytes {
			json.NewEncoder(out).Encode(app.Failure("invalid_input", "command exceeds input limit"))
			return 2
		}
		var c app.Command
		if e = domain.DecodeStrict(raw, &c); e != nil {
			json.NewEncoder(out).Encode(app.Failure("invalid_input", e.Error()))
			return 2
		}
	}
	result, e := ipc.Request(ctx, *data, raw)
	if e != nil {
		json.NewEncoder(out).Encode(app.Failure("not_running", "start memo-alarm-bridge serve for this data directory"))
		return 1
	}
	json.NewEncoder(out).Encode(result)
	if !result.OK {
		return 1
	}
	return 0
}
func serve(ctx context.Context, dataDir string, port int, out io.Writer) error {
	if port < 0 || port > 65535 {
		return errors.New("invalid port")
	}
	// Take the exclusive per-user pipe before opening storage.
	pipe, e := ipc.Listen(dataDir)
	if e != nil {
		return fmt.Errorf("already running or local pipe unavailable: %w", e)
	}
	defer pipe.Close()
	s, e := store.Open(filepath.Join(dataDir, "bridge.db"))
	if e != nil {
		return e
	}
	defer s.Close()
	listener, e := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if e != nil {
		return e
	}
	defer listener.Close()
	token, e := web.RandomToken()
	if e != nil {
		return e
	}
	service := app.New(s)
	deviceLink := link.NewRuntime(s, dataDir)
	deviceLink.SetSyncHandler(link.NewSyncHandler(s))
	defer deviceLink.Close()
	service.LinkCommand = deviceLink.Handle
	if err := deviceLink.Restore(ctx); err != nil {
		fmt.Fprintln(out, "Previously approved phone connection could not restart. Local workspace remains available:", err)
	}
	handler, e := web.New(service, token)
	if e != nil {
		return e
	}
	handler.Host = listener.Addr().String()
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8192}
	child, cancel := context.WithCancel(ctx)
	defer cancel()
	done := make(chan error, 2)
	go func() { done <- ipc.ServeLocal(child, pipe, service) }()
	go func() { done <- server.Serve(listener) }()
	fmt.Fprintf(out, "MemoAlarm Bridge %s\nApprove this local session in your browser:\nhttp://%s/#bootstrap=%s\nKeep this process running to manage PC data. Phone alarms run independently after confirmed registration.\n", version, handler.Host, token)
	select {
	case <-ctx.Done():
	case e = <-done:
	}
	cancel()
	server.Close()
	if errors.Is(e, http.ErrServerClosed) {
		return nil
	}
	return e
}
