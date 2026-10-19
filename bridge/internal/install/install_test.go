package install

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/Inkee1/memo-alarm-bridge/bridge/internal/store"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func packageFixture(t *testing.T, version string) string {
	t.Helper()
	path := t.TempDir()
	bytes := []byte("synthetic executable " + version)
	sum := sha256.Sum256(bytes)
	m := Manifest{SchemaVersion: 1, AppID: AppID, Version: version, Platform: "windows-amd64", Executable: "memo-alarm-bridge.exe", SHA256: hex.EncodeToString(sum[:]), DatabaseSchema: 2, Signature: "unsigned"}
	raw, _ := json.Marshal(m)
	if err := os.WriteFile(filepath.Join(path, m.Executable), bytes, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(path, "manifest.json"), raw, 0600); err != nil {
		t.Fatal(err)
	}
	return path
}
func testManager(t *testing.T) *Manager {
	t.Helper()
	base := t.TempDir()
	return &Manager{Root: filepath.Join(base, "app"), Data: filepath.Join(base, "data"), Probe: func(context.Context, string, string) error { return nil }}
}
func installOK(t *testing.T, m *Manager, path string) {
	t.Helper()
	plan, err := m.PlanInstall(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = m.Install(context.Background(), plan, false); err != nil {
		t.Fatal(err)
	}
}
func TestIdempotentInstall(t *testing.T) {
	m := testManager(t)
	path := packageFixture(t, "0.1.0-dev")
	installOK(t, m, path)
	installOK(t, m, path)
	current, err := m.Current()
	if err != nil || current.Version != "0.1.0-dev" {
		t.Fatal(current, err)
	}
	files, _ := os.ReadDir(filepath.Join(m.Root, "versions"))
	if len(files) != 1 {
		t.Fatal("duplicate version created")
	}
}
func TestInterruptedUpdateRetainsOldVersion(t *testing.T) {
	for _, phase := range []string{"staged", "backup", "before_switch"} {
		t.Run(phase, func(t *testing.T) {
			m := testManager(t)
			installOK(t, m, packageFixture(t, "0.1.0"))
			m.failpoint = func(at string) error {
				if at == phase {
					return errors.New("synthetic interruption")
				}
				return nil
			}
			plan, err := m.PlanInstall(packageFixture(t, "0.2.0"))
			if err != nil {
				t.Fatal(err)
			}
			if _, err = m.Install(context.Background(), plan, false); err == nil {
				t.Fatal("interruption ignored")
			}
			current, err := m.Current()
			if err != nil || current.Version != "0.1.0" {
				t.Fatal("old version lost", err)
			}
			m.failpoint = nil
			installOK(t, m, plan.Source)
		})
	}
}
func TestMigrationFailureRestoresBackup(t *testing.T) {
	m := testManager(t)
	installOK(t, m, packageFixture(t, "0.1.0"))
	db, err := store.Open(filepath.Join(m.Data, "bridge.db"))
	if err != nil {
		t.Fatal(err)
	}
	db.Close()
	m.Probe = func(_ context.Context, _ string, data string) error {
		candidate, err := sql.Open("sqlite", filepath.Join(data, "bridge.db"))
		if err != nil {
			return err
		}
		defer candidate.Close()
		candidate.Exec("DROP TABLE entities")
		return errors.New("synthetic migration failure")
	}
	plan, err := m.PlanInstall(packageFixture(t, "0.2.0"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = m.Install(context.Background(), plan, false); err == nil {
		t.Fatal("bad migration promoted")
	}
	original, err := store.Open(filepath.Join(m.Data, "bridge.db"))
	if err != nil {
		t.Fatal("original modified", err)
	}
	original.Close()
	backups, _ := filepath.Glob(filepath.Join(m.Data, "backups", "*.db"))
	if len(backups) != 1 {
		t.Fatal("pre-update backup missing")
	}
	current, _ := m.Current()
	if current.Version != "0.1.0" {
		t.Fatal("old version lost")
	}
}
func TestUninstallPreservesDataAndUnknownFiles(t *testing.T) {
	m := testManager(t)
	installOK(t, m, packageFixture(t, "0.1.0"))
	os.MkdirAll(m.Data, 0700)
	note := filepath.Join(m.Data, "keep.txt")
	os.WriteFile(note, []byte("synthetic private data"), 0600)
	extra := filepath.Join(m.Root, "keep-user-file.txt")
	os.WriteFile(extra, []byte("keep"), 0600)
	if err := m.Uninstall(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(note); err != nil {
		t.Fatal("data deleted")
	}
	if _, err := os.Stat(extra); err != nil {
		t.Fatal("unknown file deleted")
	}
	if _, err := m.Current(); err == nil {
		t.Fatal("still installed")
	}
}
func TestRejectPathEscapeAndUnownedDestination(t *testing.T) {
	m := testManager(t)
	path := packageFixture(t, "0.1.0")
	raw, _ := os.ReadFile(filepath.Join(path, "manifest.json"))
	var manifest Manifest
	json.Unmarshal(raw, &manifest)
	for _, v := range []string{"../escape", "..\\escape", "C:\\escape", "0.1.0/../../escape"} {
		manifest.Version = v
		b, _ := json.Marshal(manifest)
		os.WriteFile(filepath.Join(path, "manifest.json"), b, 0600)
		if _, err := m.PlanInstall(path); err == nil {
			t.Fatal("accepted unsafe version", v)
		}
	}
	path = packageFixture(t, "0.1.0")
	os.MkdirAll(m.Root, 0700)
	os.WriteFile(filepath.Join(m.Root, "someones-file"), []byte("keep"), 0600)
	if _, err := m.PlanInstall(path); err == nil {
		t.Fatal("unowned folder accepted")
	}
	m.Root = m.Data
	if _, err := m.PlanInstall(path); err == nil {
		t.Fatal("data and app same directory")
	}
}
func TestRejectHashDowngradeAndUnsupportedSignature(t *testing.T) {
	m := testManager(t)
	path := packageFixture(t, "0.2.0")
	installOK(t, m, path)
	if _, err := m.PlanInstall(packageFixture(t, "0.1.0")); err == nil {
		t.Fatal("downgrade accepted")
	}
	bad := packageFixture(t, "0.3.0")
	os.WriteFile(filepath.Join(bad, "memo-alarm-bridge.exe"), []byte("tampered"), 0600)
	if _, err := m.PlanInstall(bad); err == nil {
		t.Fatal("hash mismatch accepted")
	}
	bad = packageFixture(t, "0.3.0")
	raw, _ := os.ReadFile(filepath.Join(bad, "manifest.json"))
	os.WriteFile(filepath.Join(bad, "manifest.json"), []byte(strings.ReplaceAll(string(raw), "unsigned", "claimed-signed")), 0600)
	if _, err := m.PlanInstall(bad); err == nil {
		t.Fatal("unverified signing claim accepted")
	}
}
func TestPlanDoesNotCreateDirectoriesOrStartup(t *testing.T) {
	m := testManager(t)
	m.Startup = func(string, bool) error { t.Fatal("startup changed while planning"); return nil }
	if _, err := m.PlanInstall(packageFixture(t, "0.1.0")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(m.Root); !os.IsNotExist(err) {
		t.Fatal("plan modified profile")
	}
}
func TestChangedSourceAfterPlanIsRejected(t *testing.T) {
	m := testManager(t)
	source := packageFixture(t, "0.1.0")
	plan, err := m.PlanInstall(source)
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(source, "memo-alarm-bridge.exe"), []byte("different"), 0600)
	if _, err := m.Install(context.Background(), plan, false); err == nil {
		t.Fatal("changed source accepted")
	}
}
