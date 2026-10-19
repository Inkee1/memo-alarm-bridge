# Third-party notices

New project code and the original code-generated `generic_alarm.wav` fallback tone are MIT licensed (see `LICENSE`). No sampled audio, external icon set or font is bundled.

The following inventory was derived from pinned Go module sources and resolved Android artifacts/POMs. Full upstream license/copyright texts are retained in `LICENSES`; the Windows ZIP and Android assets include these notices and texts. `docs/dependency-inventory.json`, Go sums and Gradle verification metadata record exact versions/checksums. Dependencies retain their own licenses; this project's MIT license does not replace them.

## Go runtime and modules

Go 1.27.2 standard library/runtime: BSD-3-Clause, `LICENSES/Go-LICENSE.txt`. Go includes IANA time-zone data; IANA tz data is public domain. Module license filenames below also preserve subsidiary notices (including modernc libc's musl copyright and vendored netdb license).

| Module | Version | Upstream license text(s) |
|---|---|---|
| github.com/dustin/go-humanize | v1.0.1 | [text](LICENSES/github.com_dustin_go-humanize-LICENSE.txt) |
| github.com/google/uuid | v1.6.0 | [text](LICENSES/github.com_google_uuid-LICENSE.txt) |
| github.com/mattn/go-isatty | v0.0.20 | [text](LICENSES/github.com_mattn_go-isatty-LICENSE.txt) |
| github.com/Microsoft/go-winio | v0.6.2 | [text](LICENSES/github.com_Microsoft_go-winio-LICENSE.txt) |
| github.com/ncruces/go-strftime | v0.1.9 | [text](LICENSES/github.com_ncruces_go-strftime-LICENSE.txt) |
| github.com/remyoudompheng/bigfft | v0.0.0-20230129092748-24d4a6f8daec | [text](LICENSES/github.com_remyoudompheng_bigfft-LICENSE.txt) |
| golang.org/x/exp | v0.0.0-20250620022241-b7579e27df2b | [text](LICENSES/golang.org_x_exp-LICENSE.txt) |
| golang.org/x/sys | v0.36.0 | [text](LICENSES/golang.org_x_sys-LICENSE.txt) |
| modernc.org/libc | v1.66.10 | [text](LICENSES/modernc.org_libc-COPYRIGHT-MUSL.txt), [text](LICENSES/modernc.org_libc-LICENSE.txt), [text](LICENSES/modernc.org_libc-LICENSE-GO.txt), [text](LICENSES/modernc.org_libc-honnef.co_go_netdb_LICENSE.txt) |
| modernc.org/mathutil | v1.7.1 | [text](LICENSES/modernc.org_mathutil-LICENSE.txt), [text](LICENSES/modernc.org_mathutil-mersenne_LICENSE.txt) |
| modernc.org/memory | v1.11.0 | [text](LICENSES/modernc.org_memory-LICENSE.txt), [text](LICENSES/modernc.org_memory-LICENSE-GO.txt), [text](LICENSES/modernc.org_memory-LICENSE-LOGO.txt), [text](LICENSES/modernc.org_memory-LICENSE-MMAP-GO.txt) |
| modernc.org/sqlite | v1.39.1 | [text](LICENSES/modernc.org_sqlite-LICENSE.txt) |

## Android runtime dependencies

Each coordinate below declares Apache-2.0 in its POM (Guava listenablefuture inherits it from guava-parent 26.0-android). See [Apache-2.0](LICENSES/Apache-2.0.txt) and preserved embedded notices in LICENSES. AndroidX is maintained by the Android Open Source Project; Gson, Error Prone and Guava by their Google/open-source contributors; Kotlin, coroutines and annotations by JetBrains/open-source contributors.

| Coordinate | Version | License |
|---|---|---|
| androidx.annotation:annotation-experimental | 1.4.1 | Apache-2.0 |
| androidx.annotation:annotation-jvm | 1.6.0 | Apache-2.0 |
| androidx.arch.core:core-common | 2.2.0 | Apache-2.0 |
| androidx.arch.core:core-runtime | 2.2.0 | Apache-2.0 |
| androidx.collection:collection | 1.0.0 | Apache-2.0 |
| androidx.concurrent:concurrent-futures | 1.1.0 | Apache-2.0 |
| androidx.concurrent:concurrent-futures-ktx | 1.1.0 | Apache-2.0 |
| androidx.core:core | 1.12.0 | Apache-2.0 |
| androidx.interpolator:interpolator | 1.0.0 | Apache-2.0 |
| androidx.lifecycle:lifecycle-common | 2.6.2 | Apache-2.0 |
| androidx.lifecycle:lifecycle-livedata | 2.6.2 | Apache-2.0 |
| androidx.lifecycle:lifecycle-livedata-core | 2.6.2 | Apache-2.0 |
| androidx.lifecycle:lifecycle-runtime | 2.6.2 | Apache-2.0 |
| androidx.lifecycle:lifecycle-service | 2.6.2 | Apache-2.0 |
| androidx.profileinstaller:profileinstaller | 1.3.0 | Apache-2.0 |
| androidx.room:room-common | 2.6.1 | Apache-2.0 |
| androidx.room:room-ktx | 2.6.1 | Apache-2.0 |
| androidx.room:room-runtime | 2.6.1 | Apache-2.0 |
| androidx.sqlite:sqlite | 2.4.0 | Apache-2.0 |
| androidx.sqlite:sqlite-framework | 2.4.0 | Apache-2.0 |
| androidx.startup:startup-runtime | 1.1.1 | Apache-2.0 |
| androidx.tracing:tracing | 1.2.0 | Apache-2.0 |
| androidx.tracing:tracing-ktx | 1.2.0 | Apache-2.0 |
| androidx.versionedparcelable:versionedparcelable | 1.1.1 | Apache-2.0 |
| androidx.work:work-runtime | 2.10.1 | Apache-2.0 |
| com.google.code.gson:gson | 2.13.1 | Apache-2.0 |
| com.google.errorprone:error_prone_annotations | 2.38.0 | Apache-2.0 |
| com.google.guava:listenablefuture | 1.0 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib | 2.3.21 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib-jdk7 | 1.8.22 | Apache-2.0 |
| org.jetbrains.kotlin:kotlin-stdlib-jdk8 | 1.8.22 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.7.3 | Apache-2.0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm | 1.7.3 | Apache-2.0 |
| org.jetbrains:annotations | 23.0.0 | Apache-2.0 |

## Source/build tooling

Gradle Wrapper 8.14.3 is Apache-2.0; the source distribution includes [Gradle license](LICENSES/Gradle-LICENSE.txt) and [notices](LICENSES/Gradle-NOTICE.txt). JDK, Android SDK, Go compiler, Node.js, JUnit and Android test tools are build/test prerequisites, not separately redistributed tool installations. Their own distribution terms apply when obtaining them. The development signing key is private build output and is never a source/distribution file.
