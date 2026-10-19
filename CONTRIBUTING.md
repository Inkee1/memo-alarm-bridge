# Contributing

Start with [building](docs/building.md) and the [protocol](docs/protocol.md). Small issues describing an observed failure and expected behavior are welcome. Use synthetic notes/recordings and include OS/API version, app version and reproducible steps. Never attach a private data directory, credentials or full diagnostics without reviewing them.

For behavior changes, add a regression that fails for the reported problem, then run the affected suites and the complete available verification. Preserve the distinction between pending delivery and phone-confirmed registration. Do not weaken TLS, cancellation, revision checks or silent/DND policy to make a test pass. Keep Windows/Android recurrence fixtures aligned.

Run `scripts/scan-public.ps1 -AllHistory` against the exact intended Git index before sharing. Keep build output, device data and signing material out of Git. New dependencies need a reason, pinned versions/checksums and updated notices. CI uses explicit action commit pins. Tests use temporary credentials and isolated data, never a contributor's real notes or paired device.

Physical phone tests, browser inspection and unsigned Windows runtime checks are explicit activities. Report unavailable checks as unexecuted; do not substitute JVM tests for acoustic or OS behavior. Changes are contributed under the project's MIT license, preserving third-party license obligations.
