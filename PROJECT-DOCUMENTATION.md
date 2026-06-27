# AndroidCompiler

On-device Android APK compiler: a native Android app that builds full Gradle-based
Android projects (and simple source projects) directly on the phone/tablet, with no PC,
by bundling the OpenJDK 17 launcher + AAPT2 inside its own APK and downloading the rest
of the toolchain at runtime.

## Overview / Purpose

AndroidCompiler is a Jetpack Compose application that turns an Android device into a
self-contained Android build environment. The user feeds it a project ZIP; the app
extracts it, detects whether it is a Gradle project or a plain-source project, provisions
the required toolchain (OpenJDK 17, AAPT2, Gradle wrapper, android.jar for the target API,
ecj/kotlinc/d8 for the simple path), runs `gradlew assembleDebug` (or its own
compile→dex→package→align→sign pipeline) on-device, signs the result with a generated
debug keystore, and hands the finished APK to the system package installer.

The core technical problem it solves: stock Android (ART) cannot run a normal JVM/Gradle
(URLClassLoader can't load `.class` from JARs, and `/data` is mounted `noexec` /
SELinux-restricted on modern targetSdk). The app works around this by shipping the JDK and
AAPT2 launcher ELF binaries as `lib*.so` files inside the APK so the installer extracts
them into `nativeLibraryDir` (the only exec-allowed location on targetSdk ≥ 29), then
exec'ing them via `/system/bin/sh`.

## Status

Working / actively developed (not abandoned, not a throwaway). Evidence:

- 32 commits with detailed, professional messages; latest is `266d099` (version 1.1.10,
  versionCode 12) dated 2026-06-25 — recent.
- The commit history is a coherent engineering narrative: scaffolding → pipeline →
  multiple rounds of fixing real on-device problems (SIGABRT/SELinux/noexec, AAPT2 .so
  extraction, JDK launcher↔libjvm.so patch matching, external-storage chmod failures,
  progress-bar wiring, save-crash fix).
- Working tree is clean; `main` is the default branch and is sensible.
- Code is mature, heavily commented, and defensive (self-recovery retries, zip-slip
  guards, fallback download resolution).

Remaining roughness: the README is a stub (one line), there is no LICENSE file, and there
are several stale unmerged `auto-fixes/icon-*` branches. These are housekeeping issues, not
functional ones.

## Technical Requirements

Build host (to compile the app itself):
- JDK 17 (Gradle toolchain + `sourceCompatibility`/`jvmTarget` = 17).
- Android SDK with `compileSdk` / `targetSdk` = 35 (Android 15) and Build-Tools 35.
- Android Gradle Plugin 8.7.3, Kotlin 2.1.0, KSP 2.1.0-1.0.29, Gradle 8.x (wrapper
  bundled; `gradle/wrapper/gradle-wrapper.jar` is committed).
- Network access at build time to download Termux OpenJDK 17 / AAPT2 `.deb` packages
  **unless** the committed prebuilts in `prebuilts/native-jniLibs/` satisfy the binaries
  (they do, for arm64-v8a and x86_64 — the build is offline-first).

Target device (to run the app and compile projects on-device):
- Android 9 (API 28, `minSdk`) or newer; arm64-v8a or x86_64 ABI.
- ~200 MB+ free storage for the downloaded JDK and toolchain components.
- Internet access on first run to download the runtime JDK and toolchain JARs.
- "Install unknown apps" permission for the app (to install built APKs).
- No root and no Termux required (Termux's JDK is used if present, but the app bundles its
  own launchers otherwise).

Accounts/keys: none required to use the app. Publishing a signed *release* of
AndroidCompiler itself uses an external "fleet" release key (see Configuration & Secrets);
no key material is in the repo.

## Dependencies (key libraries + licenses)

Declared in `gradle/libs.versions.toml`:

- Jetpack Compose (BOM 2024.12.01), Material3 1.3.1 — Apache-2.0
- AndroidX activity-compose, core-ktx, lifecycle, navigation-compose, datastore-preferences,
  documentfile — Apache-2.0
- Hilt / Dagger 2.53.1 (+ hilt-navigation-compose 1.2.0) — Apache-2.0
- KSP 2.1.0-1.0.29 — Apache-2.0
- kotlinx-coroutines 1.9.0, kotlinx-serialization-json 1.7.3 — Apache-2.0
- OkHttp 4.12.0 — Apache-2.0
- Coil 3.0.4 (declared; image loading) — Apache-2.0
- `com.android.tools.build:apksig` 8.7.3 — Apache-2.0
- `org.tukaani:xz` 1.10 (XZ decompression for Termux `.deb` payloads) — Public Domain
- Android Gradle Plugin 8.7.3, Kotlin Gradle Plugin / compose-compiler 2.1.0 (build-logic)

Vendored binaries (committed under `prebuilts/native-jniLibs/{arm64-v8a,x86_64}/`):
- OpenJDK 17 launcher ELFs: `libjava.so`, `libjli.so`, `libjavac.so`, `libjar.so`,
  `libjdeps.so`, `libjlink.so`, `libjmod.so`, `libkeytool.so` (from Termux `openjdk-17`
  `.deb`) — GPLv2 with Classpath Exception.
- `libaapt2.so` (Android AAPT2, from Termux `aapt2` `.deb`) — Apache-2.0.

Runtime-downloaded toolchain (not committed; see `ToolchainRegistry.kt`): ecj
(Eclipse compiler, EPL-2.0), kotlin-compiler-embeddable / kotlin-stdlib /
kotlin-script-runtime (Apache-2.0), R8/d8 (`com.android.tools.r8`, BSD-style/Google),
Gradle distribution (Apache-2.0), Android platform `android.jar` for the target API.

## Setup Instructions

```bash
# 1. Clone
gh repo clone SexualMoose/AndroidCompiler
cd AndroidCompiler

# 2. Ensure JDK 17 + Android SDK (compileSdk 35 / build-tools 35) are installed.
#    Point ANDROID_HOME / local.properties at your SDK if not already configured:
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # (gitignored)

# 3. (Optional) The committed prebuilts let the native-binary step run offline.
#    If you want fresh launchers, delete app/build/native-cache to force a re-download
#    from https://packages.termux.dev (requires network).
```

## Build & Run

```bash
# Assemble a debug APK of AndroidCompiler itself:
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Install onto a connected device/emulator:
./gradlew installDebug

# Release build (minify + shrink enabled; signing is external/CI):
./gradlew assembleRelease
```

The custom Gradle convention plugin `androidcompiler.native.binaries`
(`build-logic/convention/.../AndroidNativeBinariesConventionPlugin.kt` +
`PrepareNativeBinariesTask.kt`) runs during the build: it seeds the `lib*.so` launchers
from `prebuilts/native-jniLibs/` and, only if any are missing, downloads the Termux
`openjdk-17` / `aapt2` `.deb` packages and extracts the launchers into the app's jniLibs.

## Usage (on-device)

1. Install the built `app-debug.apk` on an Android 9+ device.
2. Open the app. Four tabs: **Compiler**, **Monitor**, **Components**, **Settings**.
3. **Components** tab: download the runtime toolchain (OpenJDK 17 Termux/Bionic, ~200 MB,
   plus android.jar / Gradle / kotlinc as needed). First run only.
4. **Compiler** tab: pick a project ZIP; the app extracts, analyzes (Gradle vs simple),
   compiles (`assembleDebug` for Gradle projects), and produces a signed debug APK.
5. Tap **Install** to hand the APK to the system package installer (via FileProvider).
6. **Monitor** tab: live CPU/RAM/network metrics during the build.
7. **Settings** tab: theme, import a custom keystore, check for toolchain-component updates.

## Architecture (components + data flow)

Multi-module Gradle project (Hilt DI, MVVM + Compose):

- `app/` — Application, MainActivity, MainViewModel, `AppNavHost` (Compose Navigation),
  AndroidManifest, resources/icons. Hosts the 4 feature screens.
- `core/common/` — domain models (`CompilationState`, `ToolchainComponent`, `Settings`,
  `ThemeMode`, `HardwareMetrics`) and `UriPathResolver`.
- `core/data/` — `SettingsRepository` (DataStore-backed) + Hilt `DataModule`.
- `core/ui/` — Compose theme (Color/Shape/Spacing/Type/Theme).
- `feature/compiler/` — `CompilerScreen` + `CompilerViewModel` + `CompilationService`
  (foreground service that runs the build); "Install APK" intent lives here.
- `feature/components/` — toolchain download/setup UI.
- `feature/monitor/` — device metrics UI/VM.
- `feature/settings/` — settings + keystore import + update check UI/VM.
- `network/` — `ChunkedDownloader` (OkHttp, parallel range requests across networks) +
  `MultiNetworkManager`.
- `toolchain/` — the build engine:
  - `registry/ToolchainRegistry` — component definitions, download sources, file layout,
    API→platform-zip URL map, Gradle distribution URLs.
  - `download/ComponentDownloadManager` — downloads + extracts JDK/AAPT2/android.jar/
    Gradle, sets exec bits, handles Termux `.deb` (ar→tar.xz) and symlinks.
  - `jdk/TermuxJdkInstaller` — installs the Bionic-linked OpenJDK 17.
  - `pipeline/` — `CompilationPipeline` orchestrator, `ProjectExtractor`/`ProjectAnalyzer`,
    `GradleCompiler` (the on-device `gradlew assembleDebug` runner), `PipelineSteps`
    (resource/source/dex/package/align/sign steps for the simple path), `BuildDiagnostics`.
  - `signing/KeystoreManager` — generates a debug keystore, builds a self-signed X.509 cert
    by hand (DER), signs APKs via `apksig` (v1/v2/v3).
  - `classloader/ToolchainClassLoaderFactory` — URLClassLoader for downloaded compiler JARs.
  - `compute/` — `GpuHasher`, `PerformanceHintHelper` (big.LITTLE hints),
    `BundledBinariesValidator`.
  - `update/ComponentUpdateChecker` — checks Maven Central / Google Maven for newer toolchain
    versions.
- `build-logic/convention/` — custom Gradle convention plugins (Compose/Feature/Hilt/Library/
  NativeBinaries) + `PrepareNativeBinariesTask`.

Data flow: project ZIP → `ProjectExtractor` (zip-slip-guarded, name sanitized) →
`ProjectAnalyzer` (Gradle vs simple, required Gradle/SDK versions) → either
`GradleCompiler` (exec bundled `java` running `GradleWrapperMain assembleDebug`, with build
outputs redirected to internal ext4 storage) or the simple `PipelineSteps` chain → signed
APK → FileProvider → system installer.

## Integrations & Interconnects

- **Termux package repository** (`https://packages.termux.dev/apt/termux-main`) — source of
  the Bionic-linked OpenJDK 17 and AAPT2 `.deb` packages (both build-time prebuilt
  extraction and runtime JDK/AAPT2 install).
- **Maven Central** (`repo1.maven.org`) — ecj, kotlinc, kotlin-stdlib, R8/d8.
- **Google Maven / dl.google.com** — R8, Android platform ZIPs (`android.jar`).
- **Gradle services** (`services.gradle.org`, `raw.githubusercontent.com/gradle/gradle`) —
  Gradle distribution + `gradle-wrapper.jar`.
- **Eclipse repo** (`repo.eclipse.org`) — ecj fallback mirror.
- **Termux app on device** (optional) — if installed, its JDK/SDK at
  `/data/data/com.termux/files/usr` is reused.
- **Android system PackageInstaller** — receives the built APK via FileProvider.
- Owner context: this repo belongs to the user's "fleet" tooling (release built/signed by an
  external fleet key + CI per commit `266d099`); no other sibling repo is referenced in code.

## Configuration & Secrets

- No secrets are committed. No API keys, tokens, `.env`, `google-services.json`, keystore
  files, or `local.properties` exist in the tree or history.
- `local.properties` (SDK path) is gitignored and must be supplied locally.
- The on-device debug keystore is **generated at runtime** in app internal storage
  (`filesDir/keystores/debug.keystore`) using the standard well-known Android debug
  credentials (alias `androiddebugkey`, store/key password `android`). These are the public
  Android debug-keystore conventions, not a leaked secret — debug-signed APKs cannot be
  published to Google Play.
- Users may import their own keystore (Settings → import); the password is entered at
  runtime and the file is copied into app-internal `filesDir/keystores/`.
- Release signing of AndroidCompiler itself uses an external "fleet release key" (CI),
  configured outside this repo. `app/build.gradle.kts` contains no `signingConfig` block and
  no key material.

## Testing

- No unit/instrumentation test sources are present (no `src/test` or `src/androidTest`
  directories tracked). `testInstrumentationRunner = androidx.test.runner.AndroidJUnitRunner`
  is declared but unused.
- Verification today is manual/on-device, aided by extensive `BuildDiagnostics` logging
  (logcat tag `ACBuild`, persistent `last_compile.log` / `gradle_build_output.txt`).
- Recommendation: add tests for the pure-logic units (TAR/`.deb`/ZIP parsing, DER cert
  generation in `CertificateGenerator`, project analysis, URL resolution).

## Known Issues / TODO

- README is a stub (single `# AndroidCompiler` line) — no setup/usage docs in-repo.
- No LICENSE file despite vendoring GPLv2-with-Classpath-Exception (JDK) and Apache-2.0
  (AAPT2) binaries.
- No automated tests.
- Downloaded/bundled executables and JARs are **not integrity-verified** (no checksum or
  signature check) before being marked executable and run; transport is HTTPS but there is
  no pinning. A compromised mirror or MITM-on-a-broken-TLS-stack could substitute binaries.
- Four stale `auto-fixes/icon-*` branches (icon art v3–v6) are unmerged and superseded by
  main's v11 icon.
- The on-device Gradle path is inherently fragile across Android OEM/SELinux variations
  (the commit history is largely a sequence of fixes for exactly this); robustness depends
  on launcher↔libjvm.so version pinning staying in sync.

## Third-party & Licensing notes

- **No LICENSE file** — the project's own license is unspecified. Add one.
- **Vendored OpenJDK 17 launcher ELFs** (`prebuilts/native-jniLibs/**/lib{java,jli,javac,
  jar,jdeps,jlink,jmod,keytool}.so`) are **GPLv2 + Classpath Exception** (OpenJDK). Shipping
  them in a published APK requires honoring those terms (notice + the Classpath Exception
  covers linking; the binaries themselves remain GPLv2). A clean-room build would download
  them at runtime instead of committing them.
- **Vendored AAPT2** (`prebuilts/native-jniLibs/**/libaapt2.so`) is **Apache-2.0** (Android
  build-tools) — attribution/NOTICE expected.
- Runtime-downloaded ecj is **EPL-2.0**; kotlinc/kotlin-stdlib/d8/Gradle are Apache/BSD.
- **Trademark/brand usage:** the app name and `applicationId` are the generic
  "AndroidCompiler" / `com.androidcompiler`. "Android" is a Google trademark; using it
  prominently in an app name/package and shipping Google's AAPT2 + Android platform jars can
  raise trademark/branding concerns if distributed publicly (Google restricts use of the
  "Android" name on app titles in the Play Store). "Termux" and "Gradle"/"Kotlin"/"Eclipse"
  are referenced as the upstream sources of downloaded artifacts (descriptive use). Not a
  fork of another project — first commit `dcc407a` is original scaffolding.

## Security notes

Overall posture is good for a personal tool: no secrets, HTTPS everywhere, default (secure)
OkHttp TLS (no disabled cert validation), zip-slip guard, sanitized project names, no
WebView/JS bridge, no exported components beyond the launcher activity, narrow FileProvider
scope. Key residual risks:

- **No integrity verification of downloaded/bundled toolchain** (medium): JDK/AAPT2/Gradle/
  compiler JARs are fetched and exec'd without checksum/signature checks; no TLS pinning.
- **Broad executable-binary provisioning** is inherent to the app's function (it exists to
  exec a JDK and run arbitrary Gradle builds). The `REQUEST_INSTALL_PACKAGES` permission and
  the practice of building+running arbitrary project code on-device are by-design but expand
  the attack surface if a malicious project ZIP is compiled.
- Shell command in `GradleCompiler` is assembled by interpolating app-owned internal paths
  into a `/system/bin/sh -c` string; user-influenced segments (project name) are sanitized
  to `[A-Za-z0-9_-]` and the base path is app-internal, so injection risk is low.
