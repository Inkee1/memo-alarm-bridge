# Measured verification

Development preview, 2026-10-19. This report separates executed checks from targets and pending checks. Artifacts are not a claim of a working real-device release.

The public baseline `66384e0` was exercised in [CI run 39201127102](https://github.com/Inkee1/memo-alarm-bridge/actions/runs/39201127102). Linux race/vet, Windows Go checks, Node tests, Android JVM/build/lint and the isolated Windows install/start/CLI/stop/uninstall smoke ran successfully, but the workflow **failed** because an expected unsigned-APK verification exit code was mistaken for the later PowerShell smoke result. Six exit-status regressions now cover that false failure and preservation of actual runtime, metadata and signature errors. The scripts return zero only after their checks and cleanup succeed. See [current workflow runs](https://github.com/Inkee1/memo-alarm-bridge/actions/workflows/ci.yml) for the result of each exact source commit; individual success messages do not override a failed workflow.

| Check | Observed result |
|---|---|
| Go core/SQLite/TLS/CLI suites through conversation-link integration | Passed. Includes real loopback TLS, retry/restart/conflict/cancel cases and local user IPC. |
| Windows installer core | Earlier isolated tests passed: idempotency, staging interruption, hash/path/downgrade rejection, consistent backup/migration validation and data-preserving uninstall. |
| Local full Go suite after installer UI/CLI integration | **Failed to execute** install, IPC and web test binaries because local Windows Application Control blocked them. Hosted Windows Go checks ran successfully at the public baseline above. The local policy was not changed. |
| Go static verification | `go vet ./...`, Windows build and Linux cross-build passed. No Linux test execution is established by cross-compilation. |
| Android JVM | 75 tests passed, with zero failures/errors/skips. Covers recurrence, preparation/crash phases, playback decisions, sync conflicts, stale editor drafts and real JVM TLS pin checks. |
| Android build/lint | Debug and unsigned release APKs built; local lint completed with zero errors and 28 warnings; the public-baseline hosted run reported zero errors and 29 warnings. Instrumentation sources, including SQLite/restored-draft protection, compiled. Instrumentation was not device-executed. |
| Independent review regressions | Three Important findings addressed in one fix pass: captured draft versions prevent stale writes; recently due candidates survive recovery before delivery; conflict comparisons include time/zone/weekdays, enabled/silent state and recording identity. Eleven added JVM regressions pass. The same independent reviewer then checked the fixes on request and identified no remaining material defect within that scope; runtime limitations remain. |
| Browser state | Four Node tests passed. Actual browser rendering/clicks/microphone flow remain unexecuted because no supported browser surface was available. |
| Packaging | Notice-inclusive Windows ZIP hash/manifest/unsigned-label allowlist and Android identity/API bounds/signature-label checks passed in `scripts/verify.ps1 -StaticOnly`. Windows runtime was explicitly omitted. |
| Public source | Scanner rejection fixtures and exact staged index/history scan passed. The heuristic is supplemented by review and does not prove the absence of every possible secret. |
| Physical phone | Unexecuted. The authorized device connection was unavailable; no phone installation or new pairing was performed. |
| Remote CI/public release | [v0.1.0-dev](https://github.com/Inkee1/memo-alarm-bridge/releases/tag/v0.1.0-dev) is a published development preview at `66384e0`. Its first CI run failed on the exit-status bug described above. Existing release tags and files are retained; the script fix does not change application behavior or establish physical-device support. |
| Cloud dot ↔ self-host MCP | Unverified for this account; the implemented tested boundary is local JSON CLI. |

## Exact Windows blocker

At 2026-10-19 01:30:36–37 UTC, `go test ./... -timeout 60s` reached test execution and received **“An Application Control policy has blocked this file.”** The blocked generated files were `go-build1584903206/b273/test.test.exe` (install package), `b275/ipc.test.exe`, and `b283/web.test.exe` under the private test temporary directory. CodeIntegrity events 3033 and 3077 identify enterprise signing-level/policy rejection.

These are temporary **test binaries**, not the packaged `memo-alarm-bridge.exe`. Local distribution runtime was **not run** after this block. The later hosted CI built its own executable from the public source and completed the isolated installation/start/CLI/stop/uninstall smoke before the caller's exit-status bug failed the workflow. This does not establish that the local packaged executable was blocked, or that an arbitrary user PC will allow it.

No policy, allowlist, security exception, execution-control setting or alternate execution path was changed. A policy owner must decide any allowed development/signing route. Authenticode alone is not a guarantee: its signer/file must satisfy the active policy. This preview has no production Windows signing pipeline; a future signed package needs matching manifest/hash/signature support and verification. See Microsoft's [event explanations](https://learn.microsoft.com/en-us/windows/security/application-security/application-control/app-control-for-business/operations/event-id-explanations) and [trust rule documentation](https://learn.microsoft.com/en-us/windows/security/application-security/application-control/app-control-for-business/design/select-types-of-rules-to-create). No such security change or signing-key creation was performed here.

## First physical end-to-end test

Use synthetic content on an explicitly approved phone. Keep these rows pending until somebody observes them:

1. Install the development-only APK after checking its metadata/signature label. Grant exact alarms and notifications; select an installed offline voice. Test one-minute standalone playback, Stop and five-minute Snooze.
2. Explicitly enable a reachable PC connection, compare the full fingerprint, pair, then create a PC note and one near-future alarm. PC must initially show pending. After Sync now, require the phone's completed audio and actual future OS registration evidence before readiness.
3. Stop the PC and take the phone offline. Observe sound at the registered time, alarm-stream/DND behavior and Stop. Reconnect and inspect the reported outcome.
4. Change a rule while the phone is offline, edit it locally too, reconnect and resolve the conflict. Verify neither copy disappears silently. Keep a draft open (also across rotation), change/delete the item from the PC and sync, then attempt to save: require explicit review with the draft retained. Compare identical speech with different times/zones/weekdays and identical note text with different recordings. Test cancellation offline (still pending), then after delivery (no stale epoch playback).
5. Repeat daily/weekly schedules, DST gap/overlap fixtures, phone time-zone changes, reboot before/after unlock, permission denial/revocation, unavailable voice/storage and process interruption during replacement.
6. Record actual Android model/API, TTS engine/voice, app version, scheduled/observed times and output route. Check the saved conversation button and browser/app selection manually. Exclude private content and credentials from reports.

Before first unlock, only the saved next-two window is available with generic sound. JVM/protocol tests cannot prove OEM scheduling, acoustic output, permission dialogs or app-link behavior. A real-device pass must be recorded separately from a build pass.
