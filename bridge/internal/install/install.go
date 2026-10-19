// Package install stages immutable versions and switches a small current pointer.
// Callers must hold the data-directory IPC lock before mutating an installation.
package install

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/domain"
	"io"
	_ "modernc.org/sqlite"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const AppID = "io.github.inkee1.memoalarmbridge"

func InstalledData(root string) (string, error) {
	var o owner
	if e := readJSON(filepath.Join(root, "application.json"), &o); e != nil {
		return "", e
	}
	m := Manager{Root: root, Data: o.Data}
	if e := m.owned(false); e != nil {
		return "", e
	}
	return o.Data, nil
}

type Manifest struct {
	SchemaVersion  int    `json:"schema_version"`
	AppID          string `json:"app_id"`
	Version        string `json:"version"`
	Platform       string `json:"platform"`
	Executable     string `json:"executable"`
	SHA256         string `json:"sha256"`
	DatabaseSchema int    `json:"database_schema"`
	Signature      string `json:"signature"`
}
type owner struct {
	SchemaVersion int    `json:"schema_version"`
	AppID         string `json:"app_id"`
	Data          string `json:"data_directory"`
}
type InstallPlan struct {
	Source         string   `json:"source"`
	Root           string   `json:"install_directory"`
	Data           string   `json:"data_directory"`
	Package        Manifest `json:"package"`
	CurrentVersion string   `json:"current_version"`
	BackupRequired bool     `json:"backup_required"`
}
type InstallResult struct {
	Version      string `json:"version"`
	Launcher     string `json:"launcher"`
	Data         string `json:"data_directory"`
	Backup       string `json:"backup,omitempty"`
	StartupError string `json:"startup_error,omitempty"`
}
type Manager struct {
	Root, Data string
	Probe      func(context.Context, string, string) error
	Startup    func(string, bool) error
	failpoint  func(string) error
}

var versionPattern = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?$`)

func validManifest(m Manifest) error {
	if m.SchemaVersion != 1 || m.AppID != AppID || len(m.Version) > 64 || !versionPattern.MatchString(m.Version) || m.Platform != "windows-amd64" || m.Executable != "memo-alarm-bridge.exe" || !domain.ValidHash(m.SHA256) || m.DatabaseSchema != 2 || m.Signature != "unsigned" {
		return errors.New("unsupported package manifest, path or signing claim")
	}
	return nil
}
func readJSON(path string, value any) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	raw, e := io.ReadAll(io.LimitReader(f, 65537))
	if e != nil {
		return e
	}
	if len(raw) > 65536 {
		return errors.New("metadata too large")
	}
	return domain.DecodeStrict(raw, value)
}
func noLinks(path string) error {
	for p := filepath.Clean(path); ; p = filepath.Dir(p) {
		info, e := os.Lstat(p)
		if e == nil && info.Mode()&(os.ModeSymlink|os.ModeIrregular) != 0 {
			return errors.New("linked or reparse paths are not supported")
		}
		if e != nil && !os.IsNotExist(e) {
			return e
		}
		if filepath.Dir(p) == p {
			break
		}
	}
	return nil
}
func within(root, path string) bool {
	rel, e := filepath.Rel(root, path)
	return e == nil && (rel == "." || filepath.IsLocal(rel))
}
func (m *Manager) layout() error {
	for _, p := range []string{m.Root, m.Data} {
		if !filepath.IsAbs(p) || filepath.Clean(p) != p || filepath.Dir(p) == p {
			return errors.New("choose a dedicated absolute directory")
		}
		if e := noLinks(p); e != nil {
			return e
		}
	}
	if within(m.Root, m.Data) || within(m.Data, m.Root) {
		return errors.New("application and data directories must be separate")
	}
	return nil
}
func (m *Manager) owned(allowEmpty bool) error {
	if e := m.layout(); e != nil {
		return e
	}
	var o owner
	e := readJSON(filepath.Join(m.Root, "application.json"), &o)
	if os.IsNotExist(e) && allowEmpty {
		entries, err := os.ReadDir(m.Root)
		if os.IsNotExist(err) {
			return nil
		}
		if err != nil {
			return err
		}
		for _, entry := range entries {
			if entry.Name() != ".install.lock" {
				return errors.New("destination is not an owned installation; choose an empty app folder")
			}
		}
		return nil
	}
	if e != nil {
		return e
	}
	if o.SchemaVersion != 1 || o.AppID != AppID || !strings.EqualFold(filepath.Clean(o.Data), m.Data) {
		return errors.New("installation belongs to a different application or data directory")
	}
	return nil
}
func (m *Manager) Current() (Manifest, error) {
	var current Manifest
	if e := m.owned(false); e != nil {
		return current, e
	}
	if e := readJSON(filepath.Join(m.Root, "current.json"), &current); e != nil {
		return current, e
	}
	if e := validManifest(current); e != nil {
		return current, e
	}
	if e := verifyFile(filepath.Join(m.Root, "versions", current.Version, current.Executable), current.SHA256); e != nil {
		return current, e
	}
	return current, nil
}
func (m *Manager) PlanInstall(source string) (InstallPlan, error) {
	p := InstallPlan{Root: m.Root, Data: m.Data}
	if e := m.owned(true); e != nil {
		return p, e
	}
	absolute, e := filepath.Abs(source)
	if e != nil {
		return p, e
	}
	p.Source = absolute
	if e = noLinks(absolute); e != nil {
		return p, e
	}
	if e = readJSON(filepath.Join(absolute, "manifest.json"), &p.Package); e != nil {
		return p, e
	}
	if e = validManifest(p.Package); e != nil {
		return p, e
	}
	if e = verifyFile(filepath.Join(absolute, p.Package.Executable), p.Package.SHA256); e != nil {
		return p, e
	}
	current, e := m.Current()
	if e == nil {
		p.CurrentVersion = current.Version
		if compareVersion(p.Package.Version, current.Version) < 0 {
			return p, errors.New("downgrade rejected; use the current or newer package")
		}
		if p.Package.Version == current.Version && p.Package.SHA256 != current.SHA256 {
			return p, errors.New("same version has a different hash; obtain an explicitly versioned release")
		}
	} else if !os.IsNotExist(e) {
		return p, fmt.Errorf("current installation needs repair from its retained version/backup: %w", e)
	}
	_, e = os.Stat(filepath.Join(m.Data, "bridge.db"))
	p.BackupRequired = e == nil
	if e != nil && !os.IsNotExist(e) {
		return p, e
	}
	return p, nil
}
func (m *Manager) phase(name string) error {
	if m.failpoint != nil {
		return m.failpoint(name)
	}
	return nil
}
func (m *Manager) Install(ctx context.Context, proposed InstallPlan, startup bool) (InstallResult, error) {
	var result InstallResult
	if proposed.Root != m.Root || proposed.Data != m.Data {
		return result, errors.New("installation plan destination changed")
	}
	if e := m.owned(true); e != nil {
		return result, e
	}
	if e := os.MkdirAll(m.Root, 0700); e != nil {
		return result, e
	}
	unlock, e := lockInstall(filepath.Join(m.Root, ".install.lock"))
	if e != nil {
		return result, e
	}
	defer unlock()
	plan, e := m.PlanInstall(proposed.Source)
	if e != nil {
		return result, e
	}
	if plan.Package != proposed.Package {
		return result, errors.New("package changed since review")
	}
	if m.Probe == nil {
		return result, errors.New("candidate compatibility check is required")
	}
	// Claim the reviewed empty destination before staging, so a crashed first
	// install can be resumed without mistaking its staging folder for user data.
	if e = atomicJSON(filepath.Join(m.Root, "application.json"), owner{1, AppID, m.Data}); e != nil {
		return result, e
	}
	stage, e := os.MkdirTemp(m.Root, ".stage-")
	if e != nil {
		return result, e
	}
	defer func() {
		if stage != "" && within(m.Root, stage) && strings.HasPrefix(filepath.Base(stage), ".stage-") {
			os.RemoveAll(stage)
		}
	}()
	exe := filepath.Join(stage, plan.Package.Executable)
	if e = copyChecked(filepath.Join(plan.Source, plan.Package.Executable), exe, plan.Package.SHA256); e != nil {
		return result, e
	}
	if e = atomicJSON(filepath.Join(stage, "manifest.json"), plan.Package); e != nil {
		return result, e
	}
	if e = m.phase("staged"); e != nil {
		return result, e
	}
	probe := filepath.Join(stage, "probe")
	if e = os.Mkdir(probe, 0700); e != nil {
		return result, e
	}
	if plan.BackupRequired {
		backups := filepath.Join(m.Data, "backups")
		if e = noLinks(backups); e != nil {
			return result, e
		}
		if e = os.MkdirAll(backups, 0700); e != nil {
			return result, e
		}
		result.Backup = filepath.Join(backups, "before-"+plan.Package.Version+"-"+strconv.FormatInt(time.Now().UnixNano(), 10)+".db")
		if e = backupDatabase(ctx, filepath.Join(m.Data, "bridge.db"), result.Backup); e != nil {
			return result, fmt.Errorf("backup failed; current version retained: %w", e)
		}
		if e = copyChecked(result.Backup, filepath.Join(probe, "bridge.db"), ""); e != nil {
			return result, e
		}
	}
	if e = m.phase("backup"); e != nil {
		return result, e
	}
	if e = m.Probe(ctx, exe, probe); e != nil {
		return result, fmt.Errorf("candidate rejected; original database and previous version retained: %w", e)
	}
	// No original DB migration happens here. The candidate opens only this copy.
	if e = os.RemoveAll(probe); e != nil {
		return result, e
	}
	if e = atomicJSON(filepath.Join(m.Root, "application.json"), owner{1, AppID, m.Data}); e != nil {
		return result, e
	}
	versions := filepath.Join(m.Root, "versions")
	if e = noLinks(versions); e != nil {
		return result, e
	}
	if e = os.MkdirAll(versions, 0700); e != nil {
		return result, e
	}
	target := filepath.Join(versions, plan.Package.Version)
	if _, e = os.Stat(target); e == nil {
		if e = verifyFile(filepath.Join(target, plan.Package.Executable), plan.Package.SHA256); e != nil {
			return result, e
		}
	} else if os.IsNotExist(e) {
		if e = os.Rename(stage, target); e != nil {
			return result, e
		}
		stage = ""
	} else {
		return result, e
	}
	launcher := filepath.Join(m.Root, "MemoAlarmBridge.exe")
	if _, e = os.Stat(launcher); os.IsNotExist(e) {
		if e = copyChecked(filepath.Join(target, plan.Package.Executable), launcher, plan.Package.SHA256); e != nil {
			return result, e
		}
	} else if e != nil {
		return result, e
	}
	if e = m.phase("before_switch"); e != nil {
		return result, e
	}
	if current, e := m.Current(); e == nil {
		if e = atomicJSON(filepath.Join(m.Root, "previous.json"), current); e != nil {
			return result, e
		}
	}
	if e = atomicJSON(filepath.Join(m.Root, "current.json"), plan.Package); e != nil {
		return result, e
	}
	result.Version = plan.Package.Version
	result.Launcher = launcher
	result.Data = m.Data
	if m.Startup != nil {
		if e = m.Startup(launcher, startup); e != nil {
			result.StartupError = e.Error()
		}
	}
	return result, nil
}
func verifyFile(path, expected string) error {
	if e := noLinks(path); e != nil {
		return e
	}
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	info, e := f.Stat()
	if e != nil || !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > 150*1024*1024 {
		return errors.New("invalid or oversized package file")
	}
	hash := sha256.New()
	if _, e = io.Copy(hash, f); e != nil {
		return e
	}
	if hex.EncodeToString(hash.Sum(nil)) != expected {
		return errors.New("package checksum mismatch")
	}
	return nil
}
func copyChecked(source, target, expected string) error {
	if e := noLinks(source); e != nil {
		return e
	}
	input, e := os.Open(source)
	if e != nil {
		return e
	}
	defer input.Close()
	output, e := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0700)
	if e != nil {
		return e
	}
	ok := false
	defer func() {
		output.Close()
		if !ok {
			os.Remove(target)
		}
	}()
	hash := sha256.New()
	if _, e = io.Copy(io.MultiWriter(output, hash), input); e != nil {
		return e
	}
	if expected != "" && hex.EncodeToString(hash.Sum(nil)) != expected {
		return errors.New("source changed while copying")
	}
	if e = output.Sync(); e != nil {
		return e
	}
	if e = output.Close(); e != nil {
		return e
	}
	ok = true
	return nil
}
func atomicJSON(path string, value any) error {
	raw, e := json.MarshalIndent(value, "", "  ")
	if e != nil {
		return e
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".metadata-")
	if e != nil {
		return e
	}
	name := f.Name()
	defer os.Remove(name)
	if _, e = f.Write(raw); e == nil {
		e = f.Sync()
	}
	closeError := f.Close()
	if e != nil {
		return e
	}
	if closeError != nil {
		return closeError
	}
	return replaceFile(name, path)
}
func backupDatabase(ctx context.Context, source, target string) error {
	if e := noLinks(source); e != nil {
		return e
	}
	path := filepath.ToSlash(source)
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	uri := url.URL{Scheme: "file", Path: path, RawQuery: "mode=ro"}
	db, e := sql.Open("sqlite", uri.String())
	if e != nil {
		return e
	}
	defer db.Close()
	var check string
	if e = db.QueryRowContext(ctx, "PRAGMA quick_check").Scan(&check); e != nil || check != "ok" {
		return errors.New("source database integrity failed")
	}
	if _, e = os.Stat(target); !os.IsNotExist(e) {
		return errors.New("backup destination already exists")
	}
	if _, e = db.ExecContext(ctx, "VACUUM INTO ?", target); e != nil {
		return e
	}
	f, e := os.OpenFile(target, os.O_RDWR, 0600)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
func compareVersion(a, b string) int {
	split := func(s string) ([]int, string) {
		parts := strings.SplitN(s, "-", 2)
		core := strings.Split(parts[0], ".")
		nums := make([]int, 3)
		for i := range nums {
			nums[i], _ = strconv.Atoi(core[i])
		}
		pre := ""
		if len(parts) > 1 {
			pre = parts[1]
		}
		return nums, pre
	}
	aa, ap := split(a)
	bb, bp := split(b)
	for i := range aa {
		if aa[i] < bb[i] {
			return -1
		}
		if aa[i] > bb[i] {
			return 1
		}
	}
	if ap == bp {
		return 0
	}
	if ap == "" {
		return 1
	}
	if bp == "" {
		return -1
	}
	aParts, bParts := strings.Split(ap, "."), strings.Split(bp, ".")
	for i := 0; i < len(aParts) && i < len(bParts); i++ {
		x, y := aParts[i], bParts[i]
		if x == y {
			continue
		}
		xn, xe := strconv.Atoi(x)
		yn, ye := strconv.Atoi(y)
		if xe == nil && ye == nil {
			if xn < yn {
				return -1
			}
			return 1
		}
		if xe == nil {
			return -1
		}
		if ye == nil {
			return 1
		}
		return strings.Compare(x, y)
	}
	if len(aParts) < len(bParts) {
		return -1
	}
	return 1
}
