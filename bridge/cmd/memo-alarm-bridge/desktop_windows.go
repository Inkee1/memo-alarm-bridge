package main

import (
	"errors"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
	"net"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
)

func hideProcess(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW}
}
func startDetached(exe string, args ...string) error {
	command := exec.Command(exe, args...)
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NO_WINDOW | windows.CREATE_NEW_PROCESS_GROUP}
	if e := command.Start(); e != nil {
		return e
	}
	return command.Process.Release()
}
func openBrowser(value string) error {
	u, e := url.Parse(value)
	if e != nil || u.Scheme != "http" || u.User != nil || net.ParseIP(u.Hostname()) == nil || !net.ParseIP(u.Hostname()).IsLoopback() {
		return errors.New("only a local Bridge browser URL may be opened")
	}
	command := exec.Command(filepath.Join(os.Getenv("SystemRoot"), "System32", "rundll32.exe"), "url.dll,FileProtocolHandler", value)
	hideProcess(command)
	if e = command.Start(); e != nil {
		return e
	}
	return command.Process.Release()
}

const runKey = `Software\Microsoft\Windows\CurrentVersion\Run`

func startupCommand(exe string) string {
	return `"` + exe + `" launch --background --root "` + strings.TrimSuffix(exe, `\MemoAlarmBridge.exe`) + `"`
}
func startupEnabled(exe string) bool {
	key, e := registry.OpenKey(registry.CURRENT_USER, runKey, registry.QUERY_VALUE)
	if e != nil {
		return false
	}
	defer key.Close()
	value, _, e := key.GetStringValue("MemoAlarmBridge")
	return e == nil && value == startupCommand(exe)
}
func setStartup(exe string, enabled bool) error {
	key, e := registry.OpenKey(registry.CURRENT_USER, runKey, registry.QUERY_VALUE|registry.SET_VALUE)
	if e != nil {
		return e
	}
	defer key.Close()
	existing, _, e := key.GetStringValue("MemoAlarmBridge")
	if e != nil && e != registry.ErrNotExist {
		return e
	}
	expected := startupCommand(exe)
	if e == nil && existing != expected {
		return errors.New("another startup value uses this name; it was left unchanged")
	}
	if enabled {
		return key.SetStringValue("MemoAlarmBridge", expected)
	}
	if e == registry.ErrNotExist {
		return nil
	}
	return key.DeleteValue("MemoAlarmBridge")
}
