# 0.1.0-dev — development preview

Local Windows and native Android notes, foreground voice recording, one-shot/daily/weekday schedules, Android pre-generated offline speech, optional pinned TLS sync, explicit conflicts/cancellation status and a local JSON CLI are implemented. Windows includes a user-area installer/update flow with retained data and backups. English/Korean installation and recovery guides are included.

Artifacts:

- Windows x64 ZIP: **unsigned**; no production publisher identity. Latest runtime smoke is unexecuted after Application Control blocked generated test binaries.
- Android debug APK: **development-only**, signed with a disposable project-local key. Cleaning the build can change its update identity.
- Android release APK: **unsigned and not directly installable**. Production signing is a separate owner decision.
- SHA-256 files and APK identity/signature metadata accompany the packages.

The final review fix pass protects open/restored Android drafts against concurrent sync changes, preserves eligible recently due alarms during crash recovery, and shows complete schedule/recording differences before conflict resolution. Stale drafts remain available; saving one as new retains the existing saved item.

Android JVM tests, lint/build and package checks pass as described in the [verification report](verification.md); physical-phone playback, DND/OEM/reboot behavior and UI flows remain unverified. Remote CI/publication must be observed separately. Cloud-dot/self-host MCP connectivity is not claimed. No automatic voice call, transcription, paid AI API, account or central server is required.

Use synthetic data while evaluating. Review [installation](installation.md), [security](security.md), [support](support.md) and [notices](../THIRD_PARTY_NOTICES.md). A pending PC save is not an armed phone alarm, and an offline phone may still ring until cancellation is received.
