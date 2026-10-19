package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

func migrate(db *sql.DB) error {
	var version int
	if err := db.QueryRow("PRAGMA user_version").Scan(&version); err != nil {
		return err
	}
	if version > 2 {
		return errors.New("database is newer than this app; keep the newer executable")
	}
	if version == 2 {
		return nil
	}
	if version == 1 {
		var path string
		if err := db.QueryRow("SELECT file FROM pragma_database_list WHERE name='main'").Scan(&path); err != nil {
			return err
		}
		if err := (&Store{db: db}).Backup(context.Background(), fmt.Sprintf("%s.before-v2-%d.db", path, time.Now().UnixNano())); err != nil {
			return fmt.Errorf("migration backup failed; original retained: %w", err)
		}
		tx, err := db.Begin()
		if err != nil {
			return err
		}
		defer tx.Rollback()
		if _, err = tx.Exec("ALTER TABLE devices ADD COLUMN last_seen TEXT NOT NULL DEFAULT '';INSERT INTO schema_migrations VALUES(2,datetime('now'));PRAGMA user_version=2;"); err != nil {
			return err
		}
		return tx.Commit()
	}
	var tables int
	if err := db.QueryRow("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").Scan(&tables); err != nil {
		return err
	}
	if tables != 0 {
		return errors.New("unrecognized database; original left unchanged")
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, err = tx.Exec(`
CREATE TABLE entities(entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,revision INTEGER NOT NULL,payload BLOB NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,active INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(entity_type,entity_id));
CREATE TABLE sequence(id INTEGER PRIMARY KEY,value INTEGER NOT NULL);
INSERT INTO sequence VALUES(1,0);
CREATE TABLE operations(operation_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,result BLOB NOT NULL);
CREATE TABLE tombstones(schedule_id TEXT NOT NULL,epoch_id TEXT NOT NULL,PRIMARY KEY(schedule_id,epoch_id));
CREATE TABLE outbox(revision INTEGER PRIMARY KEY,mutation BLOB NOT NULL);
CREATE TABLE device_acks(device_id TEXT NOT NULL,schedule_id TEXT NOT NULL,payload BLOB NOT NULL,received_at TEXT NOT NULL,PRIMARY KEY(device_id,schedule_id));
CREATE TABLE devices(device_id TEXT PRIMARY KEY,name TEXT NOT NULL,token_hash TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,last_seen TEXT NOT NULL DEFAULT '');
CREATE TABLE blobs(hash TEXT PRIMARY KEY,size INTEGER NOT NULL);
CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL);
INSERT INTO schema_migrations VALUES(2,datetime('now'));
PRAGMA user_version=2;
`)
	if err != nil {
		return err
	}
	return tx.Commit()
}
