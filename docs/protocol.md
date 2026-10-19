# Protocol v1

The public contract is JSON with `schema_version: 1`. The [mutation schema](../protocol/v1.schema.json), [CLI examples](../examples/) and shared [fixtures](../protocol/fixtures/) accompany the implementation. JSON Schema checks shape; application validation additionally checks identity equality, known IANA zones, recurrence semantics, current revision, blob existence and authorization. Unknown command/mutation fields are rejected. Content is data, never a shell command.

## Storage and versions

PC SQLite owns a monotonically increasing `server_revision` for accepted mutations. `base_revision: 0` creates a new entity; updates compare against the known entity revision. A UUID `operation_id` identifies an immutable request. Retry exactly that request after a lost response; reusing its ID with changed content is rejected. Success/conflict responses preserve the authoritative entity snapshot for that operation.

Phone `local_generation` orders local execution across edits and even across different PC identities. Server revision numbers from different PCs are not comparable. An edit made during a request remains a new local generation; the in-flight operation is frozen. Remote changes never silently replace dirty local content. Conflict resolution explicitly chooses a copy against the displayed revision and generation.

Cancellation is a durable tombstone for `(schedule_id, epoch_id)`. Re-enabling a cancelled rule requires a new epoch. Cancellation events remain in history even when an old epoch is unseen locally. An offline phone can still ring until it receives the cancellation.

## Local commands

The Windows process exposes a current-user named pipe. `request --json` accepts one JSON command on standard input and produces one result (`schema_version`, `ok`, `result` or `error`). Supported user methods include `note.upsert/list/delete`, `schedule.upsert/list/cancel/preview`, `device.list/revoke`, `doctor`, `export`, `link.status/enable/invite/disable`, and `app.open/stop`. Mutating note/schedule commands wrap a full Mutation in `params`. The CLI guide explains the actual invocation and connection boundary in [dot integration](dot-integration.md).

Loopback UI approval/CSRF sessions are separate from phone credentials. Phone transport exposes no local administration methods.

## Phone HTTPS API

An invitation carries `schema_version`, `endpoint`, `cert_sha256`, `nonce`, `expires_at`. `POST /v1/pair` redeems the nonce with a device name and returns a device ID/token. Pairing is one-use and bounded; all subsequent sync/blob routes require the paired bearer credential. TLS checks the exact certificate pin, hostname and validity before sending credentials; redirects are rejected.

`POST /v1/sync` accepts:

```json
{"schema_version":1,"cursor":0,"operations":[],"acks":[]}
```

It returns `schema_version`, `cursor`, `more`, `results` and `changes`. Each change contains `server_revision` and `mutation`. Each result contains `operation_id`, `server_revision`, `status` and optional `entity`, `conflict_current`, `error`. Accepted results use `stored`, `deleted`, `pending_delivery` or `cancel_pending`; other outcomes include `conflict`, `cancelled` and `rejected`. Cursors and received content commit atomically on Android. A cursor newer than restored PC history is rejected rather than reset silently.

Limits: 16 operations and 100 rotating ACKs per request; 16 changes per response; 4 MiB request / 8 MiB client response bound. More data requires more sync runs. Blobs use `PUT`/`GET /v1/blob/<lowercase-sha256>`, max 25 MiB, with verified content hash and atomic placement. A referenced voice-note blob is uploaded before its mutation.

## What an ACK means

`DeviceAck` includes `device_id`, `schedule_id`, `epoch_id`, `server_revision`, `local_generation`, `state`, `armed_occurrences`, `observed_at`, `capabilities`, and optional `previous_applied`. An armed occurrence contains deterministic `id`, `at_utc` and `audio_sha256`. Capabilities map names to `granted`, `denied` or `unknown`.

The phone first finishes offline TTS, validates and commits the immutable audio, then registers actual OS alarms and commits the applied ledger. Only that process may report `armed`. Receiving a PC mutation, queuing TTS or opening a network connection does not establish readiness. Pending/preparing/blocked/conflict/cancel_pending remain separate states. ACK versions and observation time must not regress.

The UI shows **last reported** evidence. It requires a future occurrence and exact-alarm permission before presenting readiness; old/elapsed evidence is not a fresh guarantee. `last_seen` is merely contact time. `previous_applied` can describe an older still-working version while a newer one is blocked; it never upgrades the new version to armed. All relevant phones must acknowledge a cancellation before PC status becomes cancelled.

The Android ledger has candidate/applied phases and immutable PendingIntent occurrence identity. Startup reconciliation recovers interrupted registration, honors tombstones and skips stale occurrences. Playback uses a process-owned claim and a final serialized start check; a new process does not replay an interrupted utterance automatically.
