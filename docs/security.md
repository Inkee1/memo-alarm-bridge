# Security and privacy

The local PC and each paired phone form one trust domain. Every paired device can read and modify the collection. This preview has no per-note sharing or read-only device role. There is no central service, telemetry, advertising SDK, automatic transcription or required AI API. Build tools download dependencies; optional ChatGPT links use the selected external app/browser.

## Boundaries

| Boundary | Protection and limit |
|---|---|
| PC admin UI | Loopback only, explicit bootstrap approval, session cookie, Origin/Host checks, CSRF token and CSP. The private bootstrap link is a credential. |
| Local CLI | Windows named pipe restricted to the current user. A process already running as that user can access the collection. |
| Device connection | Off for a new installation; explicit address/port consent. The saved approved connection resumes on later starts. No firewall/router edits are automatic. |
| Pairing | Single-use ten-minute invitation, bounded attempts, exact certificate fingerprint checked out of band. Keep the entire invitation private. |
| Transport | TLS 1.2+, certificate pin plus hostname/validity checks, no redirects or HTTP proxy forwarding. The self-signed certificate is not public-CA identity. |
| Credentials | Windows identity key protected by current-user DPAPI; only hashes of device bearer tokens are stored on PC. Phone credentials use Android Keystore encryption in credential-protected no-backup storage. |
| Content | Notes and recordings are private local files, **not application-level encrypted**. OS account/device protection still matters. A compromised account or unlocked trusted device is outside this boundary. |
| Locked boot | Device-protected ledger contains schedule/occurrence IDs, times, version/state and opaque audio references. Private text, speech and credentials remain credential-protected. A generic bundled tone can play before first unlock. |
| Revocation | Stops later access to the PC. It cannot remotely erase a phone or cancel alarms while that phone is offline. |

Invitations and paired tokens authorize writes; do not put them into source control, chat transcripts, screenshots or issue reports. The local bridge has no Internet-facing administrative API. Do not expose the loopback UI through a tunnel.

## Deliberate identity repair

Use this only after confirming why an endpoint/pin changed, a certificate expired, or a DPAPI identity can no longer be read. It is a user action, never an automatic response to an unexpected certificate.

1. Record which phones may still have active alarms. Cancel directly on those phones if necessary. Revoke old devices in the PC UI when available, then disable the connection.
2. Stop the Bridge and privately back up its entire data folder. Preserve the original database and `blobs`.
3. If the PC address alone changed, choose the new address in Setup and create a fresh invitation. The identity is scoped to that address.
4. For an unreadable/expired identity, move the affected `identity-<address-hash>.json` file from the data folder into the private backup while stopped. If uncertain which file belongs to the endpoint, retain all originals and obtain local assistance. Do not delete the DB or accept an unchecked certificate.
5. Start the Bridge, explicitly enable the intended connection, verify its new fingerprint locally and pair each trusted phone again. Existing phone rules remain local; review any conflicts. Revoke obsolete PC device entries.

Migration to another Windows user/computer can require this process because DPAPI keys are account/machine bound. Restoring an older DB can also produce a future-cursor error on a phone; preserve both copies and review before re-pairing. An old backup cannot safely pretend to be current server history.

## Packaging and reporting

Windows artifacts are unsigned. The Android development APK uses a disposable project-local key; release APKs are unsigned until an owner deliberately signs them. Checksums establish byte equality, not trusted publisher identity. Do not disable platform security to run a blocked build.

The public scanner checks staged Git blobs and optional history against a source allowlist and high-confidence credential/private-path patterns. It is a heuristic, not a security audit. See [reporting](../SECURITY.md), [verification](verification.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
