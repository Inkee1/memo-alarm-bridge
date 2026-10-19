package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/app"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/install"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/ipc"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/web"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

func defaultLayout() (string, string) {
	config, _ := os.UserConfigDir()
	return filepath.Join(os.Getenv("LOCALAPPDATA"), "Programs", "MemoAlarmBridge"), filepath.Join(config, "MemoAlarmBridge", "data")
}
func lifecycle(ctx context.Context, args []string, out, errout io.Writer) int {
	rootDefault, dataDefault := defaultLayout()
	exe, e := os.Executable()
	if e != nil {
		fmt.Fprintln(errout, e)
		return 1
	}
	f := flag.NewFlagSet(args[0], flag.ContinueOnError)
	f.SetOutput(errout)
	root := f.String("root", rootDefault, "dedicated user installation directory")
	data := f.String("data", dataDefault, "private data directory")
	source := f.String("source", filepath.Dir(exe), "extracted package directory")
	yes := f.Bool("yes", false, "approve the reviewed lifecycle action")
	startup := f.Bool("startup", false, "opt into sign-in startup")
	noOpen := f.Bool("no-open", false, "print local setup link without opening a browser")
	background := f.Bool("background", false, "launch without opening a browser")
	port := f.Int("port", 0, "setup loopback port")
	if e = f.Parse(args[1:]); e != nil || f.NArg() != 0 {
		return 2
	}
	if args[0] == "check-data" {
		explicitData := false
		f.Visit(func(option *flag.Flag) {
			if option.Name == "data" {
				explicitData = true
			}
		})
		if !explicitData {
			fmt.Fprintln(errout, "check-data requires an explicit isolated --data directory")
			return 2
		}
		db, err := store.Open(filepath.Join(*data, "bridge.db"))
		if err != nil {
			fmt.Fprintln(errout, err)
			return 1
		}
		defer db.Close()
		fmt.Fprintln(out, version)
		return 0
	}
	manager := &install.Manager{Root: filepath.Clean(*root), Data: filepath.Clean(*data), Probe: probeCandidate, Startup: setStartup}
	switch args[0] {
	case "setup":
		e = serveInstaller(ctx, manager, *source, *port, !*noOpen, out)
	case "launch":
		if saved, err := install.InstalledData(manager.Root); err == nil {
			manager.Data = saved
		} else {
			e = err
			break
		}
		e = launchManaged(ctx, manager, !*background)
	case "install":
		var plan install.InstallPlan
		plan, e = manager.PlanInstall(*source)
		if e == nil {
			if !*yes {
				json.NewEncoder(out).Encode(plan)
				fmt.Fprintln(errout, "Review this plan, then repeat with --yes. Startup is off unless --startup is explicit.")
				return 0
			}
			lock, err := ipc.Listen(manager.Data)
			if err != nil {
				e = errors.New("Bridge is running; stop it before installation/update")
				break
			}
			defer lock.Close()
			var result install.InstallResult
			result, e = manager.Install(ctx, plan, *startup)
			if e == nil {
				json.NewEncoder(out).Encode(result)
			}
		}
	case "uninstall":
		if !*yes {
			fmt.Fprintln(out, "Uninstall removes app files/startup only and preserves all data. Review --root and --data, then repeat with --yes.")
			return 0
		}
		lock, err := ipc.Listen(manager.Data)
		if err != nil {
			e = errors.New("Bridge is running; stop it before uninstalling")
			break
		}
		defer lock.Close()
		e = manager.Uninstall()
		if e == nil {
			fmt.Fprintln(out, "Uninstalled. Data retained:", manager.Data)
		}
	}
	if e != nil {
		fmt.Fprintln(errout, e)
		return 1
	}
	return 0
}
func probeCandidate(ctx context.Context, exe, data string) error {
	probeCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	command := exec.CommandContext(probeCtx, exe, "check-data", "--data", data)
	hideProcess(command)
	output, e := command.CombinedOutput()
	if e != nil {
		return fmt.Errorf("candidate database check failed: %s", strings.TrimSpace(string(output)))
	}
	// The staged binary must identify the version in the staged manifest.
	var manifest install.Manifest
	raw, e := os.ReadFile(filepath.Join(filepath.Dir(exe), "manifest.json"))
	if e != nil {
		return e
	}
	if e = domain.DecodeStrict(raw, &manifest); e != nil {
		return e
	}
	if strings.TrimSpace(string(output)) != manifest.Version {
		return errors.New("binary version differs from reviewed manifest")
	}
	return nil
}
func runningCommand(ctx context.Context, data, method string) (app.CommandResult, error) {
	bounded, cancel := context.WithTimeout(ctx, 1500*time.Millisecond)
	defer cancel()
	body, _ := json.Marshal(app.Command{SchemaVersion: 1, Method: method, Params: json.RawMessage(`{}`)})
	return ipc.Request(bounded, data, body)
}
func launchProcess(ctx context.Context, exe, data string, open bool) error {
	if result, e := runningCommand(ctx, data, "app.open"); e == nil && result.OK {
		if !open {
			return nil
		}
		value, ok := result.Result.(map[string]any)
		if !ok {
			return errors.New("unexpected app response")
		}
		url, ok := value["url"].(string)
		if !ok {
			return errors.New("missing local UI link")
		}
		return openBrowser(url)
	}
	args := []string{"serve", "--data", data}
	if open {
		args = append(args, "--open")
	}
	if e := startDetached(exe, args...); e != nil {
		return e
	}
	for i := 0; i < 10; i++ {
		result, e := runningCommand(ctx, data, "doctor")
		if e == nil && result.OK {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(300 * time.Millisecond):
		}
	}
	return errors.New("Bridge has not reported ready; run doctor or serve from a terminal for details")
}
func launchManaged(ctx context.Context, manager *install.Manager, open bool) error {
	current, e := manager.Current()
	if e != nil {
		return e
	}
	return launchProcess(ctx, filepath.Join(manager.Root, "versions", current.Version, current.Executable), manager.Data, open)
}
func setupDispatcher(manager *install.Manager, source string) func(context.Context, app.Command) app.CommandResult {
	var mutation sync.Mutex
	return func(ctx context.Context, c app.Command) app.CommandResult {
		if c.SchemaVersion != 1 {
			return app.Failure("invalid_input", "schema_version must be 1")
		}
		ok := func(value any) app.CommandResult { return app.CommandResult{SchemaVersion: 1, OK: true, Result: value} }
		if c.Method == "install.plan" {
			if e := domain.DecodeStrict(c.Params, &struct{}{}); e != nil {
				return app.Failure("invalid_input", e.Error())
			}
			plan, e := manager.PlanInstall(source)
			raw, _ := json.Marshal(plan)
			value := map[string]any{}
			json.Unmarshal(raw, &value)
			value["startup"] = startupEnabled(filepath.Join(manager.Root, "MemoAlarmBridge.exe"))
			if e != nil {
				value["error"] = e.Error()
			}
			return ok(value)
		}
		var input struct {
			Consent bool `json:"consent"`
			Startup bool `json:"startup"`
		}
		if e := domain.DecodeStrict(c.Params, &input); e != nil || !input.Consent {
			return app.Failure("approval_required", "review this action and approve it explicitly")
		}
		mutation.Lock()
		defer mutation.Unlock()
		var result any
		var e error
		switch c.Method {
		case "install.apply", "install.remove":
			lock, err := ipc.Listen(manager.Data)
			if err != nil {
				return app.Failure("bridge_running", "Stop this Bridge before updating or uninstalling; no files were changed")
			}
			defer lock.Close()
			if c.Method == "install.apply" {
				var plan install.InstallPlan
				plan, e = manager.PlanInstall(source)
				if e == nil {
					result, e = manager.Install(ctx, plan, input.Startup)
				}
			} else {
				e = manager.Uninstall()
				result = map[string]string{"data_retained": manager.Data}
			}
		case "install.stop":
			response, err := runningCommand(ctx, manager.Data, "app.stop")
			e = err
			if err == nil && !response.OK {
				e = errors.New("Bridge rejected stop request")
			}
			result = map[string]bool{"stop_requested": e == nil}
		case "install.launch":
			e = launchManaged(ctx, manager, true)
			result = map[string]bool{"ready": e == nil}
		case "install.run":
			exe, err := os.Executable()
			e = err
			if e == nil {
				e = launchProcess(ctx, exe, manager.Data, true)
			}
			result = map[string]bool{"ready": e == nil}
		default:
			return app.Failure("unknown_method", "unknown installer command")
		}
		if e != nil {
			return app.Failure("installation_error", e.Error())
		}
		return ok(result)
	}
}
func serveInstaller(ctx context.Context, manager *install.Manager, source string, port int, open bool, out io.Writer) error {
	if port < 0 || port > 65535 {
		return errors.New("invalid port")
	}
	listener, e := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if e != nil {
		return e
	}
	defer listener.Close()
	token, e := web.RandomToken()
	if e != nil {
		return e
	}
	handler, e := web.NewInstaller(setupDispatcher(manager, source), token)
	if e != nil {
		return e
	}
	handler.Host = listener.Addr().String()
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 3 * time.Minute, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8192}
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	defer server.Close()
	url := "http://" + handler.Host + "/#bootstrap=" + token
	fmt.Fprintln(out, "MemoAlarm Bridge setup. Review choices in this local page; close this console when finished:\n"+url)
	if open {
		if err := openBrowser(url); err != nil {
			fmt.Fprintln(out, "Open the printed local link manually:", err)
		}
	}
	select {
	case <-ctx.Done():
		return nil
	case e = <-done:
		if errors.Is(e, http.ErrServerClosed) {
			return nil
		}
		return e
	}
}
