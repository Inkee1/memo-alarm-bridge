# Support and limits

This is a development preview. A target platform is not a claim that a particular device or clean installation has passed testing. See [measured evidence](verification.md).

| Area | Current scope |
|---|---|
| Windows | Windows 10/11 x64 distribution target; user-area installer, standalone local UI and JSON CLI. Latest runtime/installer integration is blocked on the development host by Application Control. |
| Android | API 31+; compiled against/targeting API 36. Native text/voice notes and local alarms. No physical-device validation yet. |
| Linux/macOS | Portable Go core is built/tested by the planned CI. Windows IPC/installer/DPAPI have no non-Windows runtime implementation. No desktop release for these systems. |
| Recurrence | One-shot, daily, selected weekdays; fixed IANA zone. DST gaps shift forward, overlaps use the earlier offset. Phone time-zone changes do not redefine the chosen zone. |
| Scheduling window | At most 50 active rules, next two future occurrences per rule. The phone replenishes while operating/unlocked. Before first unlock after reboot only the saved finite window is available. |
| Playback | Prepared offline speech; `USAGE_ALARM` / speech content type; ordered playback, per-occurrence Stop and five-minute Snooze; two-minute session cap. More than five minutes late is recorded missed. |
| Permissions/policy | Exact-alarm access, notification state, installed offline voice and available storage matter. Optional respect-silent setting, alarm stream volume and system DND policy apply. No forced volume, DND-policy override or full-screen intent. |
| Sync | Explicit pinned TLS pairing, manual sync and best-effort WorkManager periodic work. PC/phone connectivity is required for changes, not for already prepared phone alarms. |
| Offline cancellation | Remains pending until each relevant phone processes it. Revocation/unpairing does not cancel a disconnected phone's alarms. |
| Content limits | Text note 32,768 characters; speech 2,000; audio blob 25 MiB; foreground note recording up to five minutes. No automatic transcription. |
| Data portability | PC JSON export excludes blobs/secrets; stopped full-folder backup retains them. Android cloud backup/transfer disabled; no Android backup/restore UI. |
| Dot | Local JSON CLI and optional user-saved conversation link. Cloud-dot connection to self-host MCP/tunnel is unverified and is not bundled. No automatic voice call. |

Force-stop can prevent alarms until the user opens the app again. Permission revocation, OEM battery restrictions, audio focus, missing voices, clock changes and physical output devices require device testing. A previous working alarm can remain scheduled if its replacement fails; inspect the separately displayed previous-version evidence. Do not use this preview as the only alarm for a safety-critical event.

The manual actions are: approve local setup; optionally select startup; explicitly install a phone APK; grant exact-alarm/notification access; choose/download an offline voice when needed; optionally enable a reachable PC connection and approve any separate network policy; compare the invitation fingerprint and pair. Microphone access is needed only for recording. No account registration or paid service is required by this app.

한국어 설치·복구 절차와 수동 승인 단계는 [설치 안내](installation.ko.md)에 있습니다. 현재 Windows 실행 정책 및 Android 실기 검증의 제한은 이 문서와 검증 보고서에 공개합니다.
