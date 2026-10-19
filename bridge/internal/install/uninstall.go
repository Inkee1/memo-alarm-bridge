package install

import (
	"errors"
	"os"
	"path/filepath"
)

// Uninstall removes only known app files. Data and unexpected files are retained.
func (m *Manager) Uninstall() error {
	if e := m.owned(false); e != nil {
		return e
	}
	unlock, e := lockInstall(filepath.Join(m.Root, ".install.lock"))
	if e != nil {
		return e
	}
	defer unlock()
	if m.Startup != nil {
		if e = m.Startup(filepath.Join(m.Root, "MemoAlarmBridge.exe"), false); e != nil {
			return e
		}
	}
	if e = os.Remove(filepath.Join(m.Root, "current.json")); e != nil && !os.IsNotExist(e) {
		return e
	}
	versions := filepath.Join(m.Root, "versions")
	if e = noLinks(versions); e != nil {
		return e
	}
	entries, e := os.ReadDir(versions)
	if e != nil && !os.IsNotExist(e) {
		return e
	}
	var failures []error
	for _, entry := range entries {
		if !entry.IsDir() || !versionPattern.MatchString(entry.Name()) {
			continue
		}
		path := filepath.Join(versions, entry.Name())
		if e = noLinks(path); e != nil {
			return e
		}
		var manifest Manifest
		if readJSON(filepath.Join(path, "manifest.json"), &manifest) != nil || validManifest(manifest) != nil || manifest.Version != entry.Name() {
			continue
		}
		for _, name := range []string{manifest.Executable, "manifest.json"} {
			if e = os.Remove(filepath.Join(path, name)); e != nil && !os.IsNotExist(e) {
				failures = append(failures, e)
			}
		}
		os.Remove(path)
	}
	os.Remove(versions)
	for _, name := range []string{"MemoAlarmBridge.exe", "previous.json"} {
		if e = os.Remove(filepath.Join(m.Root, name)); e != nil && !os.IsNotExist(e) {
			failures = append(failures, e)
		}
	}
	if len(failures) > 0 {
		return errors.Join(failures...)
	}
	return os.Remove(filepath.Join(m.Root, "application.json"))
}
