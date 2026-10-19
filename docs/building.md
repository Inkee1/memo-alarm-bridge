# Building and verification

End users do not need these build tools. The Windows ZIP contains a standalone executable. Android APK installation/signing and runtime permissions remain separate user actions.

## Toolchain

Use Go 1.27.2, Node.js 24, JDK 21, Android SDK platform 36 and build-tools 36.0.0. Gradle Wrapper pins 8.14.3, AGP 8.13.2 and Kotlin 2.3.21; Java/Kotlin bytecode targets 17. Set `JAVA_HOME` and `ANDROID_HOME` for the build process. No global PATH change is necessary when passing explicit tools to scripts.

On Windows with PowerShell:

```powershell
./scripts/verify.ps1
```

This runs Go tests/vet, JavaScript tests/syntax checks, Android JVM tests, instrumentation compilation, lint, APK builds, public scanner fixtures, index/history scan and packaging checks including a synthetic Windows install/start/CLI/uninstall smoke. Any failed command stops with failure. Instrumentation compilation does not execute it on a device. The runtime smoke uses isolated directories and minimal PATH; it is not a clean VM certification.

If organizational Application Control blocks generated executables, leave that policy intact. `./scripts/verify.ps1 -StaticOnly` performs a deliberately partial verification without Go test execution or the Windows runtime smoke. It prints the omitted checks and is not a full pass. Record the policy block in the result. Do not rerun blocked binaries from another path as a workaround.

The independent commands are:

```powershell
go -C bridge test ./... -timeout 60s
go -C bridge vet ./...
node --test bridge/internal/web/assets/app.test.mjs
cd android
./gradlew.bat :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
```

On Linux, the portable Go core can run `go test -race ./... -timeout 120s` from `bridge`. Windows CLI/IPC/installer/DPAPI checks are Windows-only. Android builds in this project's CI use Windows, matching the checked native build artifacts; no Linux Android support claim follows from the Go job.

## Packages and signing

```powershell
./scripts/package-windows.ps1
./scripts/package-android.ps1
./scripts/tests/packaging.tests.ps1
```

`dist` receives the unsigned Windows ZIP, development-only Android APK, unsigned release APK, APK metadata and SHA-256 files. Packaging verifies artifact identity and current signing labels. It does not launch a phone or grant permissions.

The debug key is generated only in `android/app/build/debug-signing`. It is disposable and not committed. Cleaning that directory changes the APK update identity. The release APK is intentionally unsigned; production signing, secure key storage, versionCode increments and an update policy require a separate owner decision. Never reuse another project's key or publish a keystore. Windows publisher signing likewise requires an explicit signing arrangement.

## Dependency changes

Go module checksums are in `go.sum`. Gradle locks and SHA-256 verification metadata pin resolved artifacts. Initial verification metadata was generated from the pinned resolved dependencies; it is not a cryptographic authorship audit. Check upstream provenance before accepting changed hashes. The wrapper JAR and Gradle distribution checksum were compared with Gradle's official checksum endpoints.

Generate Android runtime coordinates/hashes with `:app:runtimeDependencyInventory`; update [the public inventory](dependency-inventory.json), [notices](../THIRD_PARTY_NOTICES.md) and `LICENSES` after deliberately reviewing a dependency change. Do not silently regenerate verification metadata to bypass a mismatch. License files/notices are copied into the Windows ZIP and Android assets.

CI is prepared under `.github/workflows`, but a successful local build is not evidence that remote CI ran. The manual release workflow produces accurately labelled artifacts; publication is gated by explicit dispatch and an owner-selected draft release step. No production signing secret is requested by these workflows.
