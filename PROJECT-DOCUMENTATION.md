# AndroidCompiler — Project Documentation

On-device Android APK compiler: a native Android app that builds Gradle-based Android projects
(and, experimentally, simple source-only projects) directly on a phone or tablet, with no PC, by
bundling the OpenJDK 17 launcher binaries and AAPT2 inside its own APK and downloading the rest of
the toolchain at runtime.

_Last audited: 2026-10-10 against main @ 94bab3a (review fixes 2026-10-11)._

## Overview / Purpose

AndroidCompiler is a Jetpack Compose application that turns an Android device into a
self-contained Android build environment. The user picks a project ZIP; the app extracts it,
detects whether it is a Gradle project or a plain-source project, uses the toolchain it has
provisioned (OpenJDK 17, AAPT2, a Gradle wrapper, `android.jar` for the target API, plus
ecj/kotlinc/d8 for the simple path), runs `gradlew assembleDebug` on-device (or its own
compile → dex → package → align → sign pipeline for simple projects), and hands the finished
debug-signed APK to the system package installer.

The core technical problem it solves: stock Android (ART) cannot run a normal JVM/Gradle (ART's
`URLClassLoader` cannot load JVM `.class` files from JARs, and app data directories are not
executable for modern `targetSdk` levels). The app works around this by shipping the JDK and
AAPT2 launcher ELF binaries as `lib*.so` files inside the APK, so the installer extracts them into
`nativeLibraryDir` (the exec-allowed location for `targetSdk` ≥ 29), and then exec'ing them via
`/system/bin/sh`. The heavy JDK runtime (`libjvm.so`, modules) is a Bionic-linked OpenJDK 17
downloaded from the Termux package repository; Termux itself does not need to be installed.

## Status

**Broken for new installs: Termux replaced `openjdk-17` 17.0.19 with 17.0.20 (verified 404 on
2026-10-10); devices that already downloaded 17.0.19 still work.** Otherwise maintenance-only (no
commits since 2026-06-27). Evidence (as of 2026-10-10):

- **Runtime JDK download is dead.** `…/pool/main/o/openjdk-17/openjdk-17_17.0.19_{aarch64,x86_64}.deb`
  returns HTTP 404; the Termux pool and `Packages` index now carry only 17.0.20.
  `TermuxJdkInstaller` always downloads the pinned URL (`pinnedJdkUrl()`, no fallback), and
  `ChunkedDownloader` fails on a non-2xx response, so the JDK never reaches *Installed*. A fresh
  install therefore stays on the Setup screen and Compile refuses to run; `GradleCompiler`'s
  self-repair retry re-downloads the same dead URL. Fix steps are under Known Issues.
- `main` @ `94bab3a` (2026-06-27, docs commit). Latest code change is `266d099` (2026-06-25):
  version **1.1.10**, `versionCode` **12**. 33 commits in total, first on 2026-04-13.
- History is a coherent engineering narrative: scaffolding → pipeline → repeated rounds of fixing
  real on-device problems (SIGABRT/SELinux/noexec, AAPT2 `.so` extraction, launcher↔`libjvm.so`
  patch matching, external-storage chmod failures, progress-bar wiring, APK-save crash).
- The Gradle (`assembleDebug`) path is the one the commit history shows being exercised and fixed
  on-device. The simple (non-Gradle) path has not been verified in this audit and is likely
  non-functional on ART (see Known Issues). On-device behaviour was **not** re-tested in this audit.
- Branches/tags: only `main` exists on the remote and nothing is unmerged. The four former
  `auto-fixes/icon-*` branches (superseded launcher-icon experiments) now exist only as archive
  tags `archived/auto-fixes/icon-androidcompiler-*`.
- No CI: there is no `.github/workflows/` directory.

## Technical Requirements

Build host (to compile the app itself):

- **JDK 21.** `build-logic/convention` declares `kotlin { jvmToolchain(21) }` and no toolchain
  auto-provisioning plugin is configured, so a local JDK 21 must be installed (running Gradle on
  JDK 21 is simplest). App modules compile to Java 17 bytecode (`sourceCompatibility`/`jvmTarget`
  = 17).
- **Gradle 8.11.1** via the committed wrapper (`gradle/wrapper/gradle-wrapper.properties`).
- **Android Gradle Plugin 8.7.3**, **Kotlin 2.1.0**, **KSP 2.1.0-1.0.29**
  (`gradle/libs.versions.toml`).
- **Android SDK** with platform **35** (`compileSdk = 35`, `targetSdk = 35`). Build-Tools are not
  pinned in the build files (AGP's default is used). No NDK is needed — no native code is
  compiled; the native binaries are prebuilt.
- Network at build time for Maven/Google dependency resolution. The native launcher step is
  offline-first: the committed prebuilts in `prebuilts/native-jniLibs/` cover both ABIs, so Termux
  is only contacted if a prebuilt is missing.

Target device (to run the app and compile projects on-device):

- `minSdk` is 28, but only **Android 12+ (API 31+)** is expected to launch: `MainActivity.kt:28`
  references `PerformanceHintManager` (an API 31 class) without a version check. Android 9–11 has
  not been tested. **arm64-v8a or x86_64** only (the ABIs packaged by `abiFilters`).
- Free storage for the toolchain: the registry's size estimates for the base components add up to
  262,957,325 bytes, shown on the Setup screen as "~250 MB" (≈263 MB decimal): OpenJDK ~97 MB,
  kotlinc ~68 MB, `android.jar` ~63 MB, d8 ~22 MB, AAPT2 ~8 MB, ecj ~3 MB, and so on. The
  toolchain takes more space once extracted, and Gradle builds also download Gradle distributions
  and project dependencies.
- Internet access for the first-run toolchain download and for Gradle builds.
- The "Install unknown apps" permission for AndroidCompiler, to install the APKs it builds.
- No root and no Termux required.

Accounts/keys: none required to build or use the app.

## Dependencies

Declared in `gradle/libs.versions.toml` (all used by at least one module unless noted):

| Library | Version | License |
|---|---|---|
| Jetpack Compose BOM (`ui`, `ui-graphics`, `ui-tooling(-preview)`, `material3`, `material-icons-extended`) | BOM 2024.12.01 | Apache-2.0 |
| AndroidX activity-compose | 1.9.3 | Apache-2.0 |
| AndroidX core-ktx | 1.15.0 | Apache-2.0 |
| AndroidX lifecycle-runtime-compose / lifecycle-viewmodel-compose | 2.8.7 | Apache-2.0 |
| AndroidX navigation-compose | 2.8.5 | Apache-2.0 |
| AndroidX datastore-preferences | 1.1.1 | Apache-2.0 |
| AndroidX documentfile | 1.0.1 | Apache-2.0 |
| Hilt / Dagger (`hilt-android`, `hilt-compiler` via KSP) | 2.53.1 | Apache-2.0 |
| AndroidX hilt-navigation-compose | 1.2.0 | Apache-2.0 |
| kotlinx-coroutines (core, android) | 1.9.0 | Apache-2.0 |
| kotlinx-serialization-json | 1.7.3 | Apache-2.0 |
| OkHttp | 4.12.0 | Apache-2.0 |
| `com.android.tools.build:apksig` | 8.7.3 | Apache-2.0 |
| `org.tukaani:xz` (XZ for Java; Termux `.deb` payloads) | 1.10 | 0BSD (versions before 1.10 were public domain) |

Build-time only: Android Gradle Plugin 8.7.3, Kotlin Gradle Plugin + Compose compiler plugin
2.1.0, KSP 2.1.0-1.0.29, kotlinx-serialization plugin, Hilt Gradle plugin — all Apache-2.0.
The catalog also lists `coil = "3.0.4"` and `material3 = "1.3.1"` version entries that no library
alias references (Material3's version comes from the Compose BOM); Coil is **not** a dependency.

Vendored / bundled in the repo and APK (details and obligations in [NOTICE](NOTICE)):

- `prebuilts/native-jniLibs/{arm64-v8a,x86_64}/` — OpenJDK **17.0.19** launcher ELFs from the
  Termux `openjdk-17` package (`libjava.so`, `libjli.so`, `libjavac.so`, `libjar.so`,
  `libjdeps.so`, `libjlink.so`, `libjmod.so`, `libkeytool.so`) — **GPLv2 with Classpath
  Exception**; and `libaapt2.so` (Android AAPT2 from the Termux `aapt2` 13.0.0.6 package) —
  **Apache-2.0**.
- `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` — Gradle wrapper, Apache-2.0.

Downloaded on the device at runtime (not committed; see `ToolchainRegistry.kt`,
`TermuxJdkInstaller.kt`, `ComponentDownloadManager.kt`):

- ecj 3.39.0 (EPL-2.0), kotlin-compiler-embeddable / kotlin-stdlib / kotlin-script-runtime 2.1.0
  (Apache-2.0), R8/d8 8.9.35 (BSD-3-Clause), Gradle wrapper/distributions (Apache-2.0).
- Android platform-34 ZIP (`android.jar`; code to download API 29–35 variants exists but is never
  called) — Android SDK terms.
- The Termux `openjdk-17` runtime, pinned to 17.0.19 (GPLv2 + Classpath Exception). The pinned
  file now returns 404; see Known Issues.
- The Termux `aapt2` and `aapt` packages themselves, at whatever version the live index lists
  (currently 16.0.0.4-2; Apache-2.0). The app still runs its bundled 13.0.0.6 `libaapt2.so`, but
  loads it against these packages' libraries (see Known Issues).
- Termux library dependencies, resolved from the live `Packages` index, so the set and versions can
  change. For the JDK the set is currently `libandroid-shmem`, `libandroid-spawn`, `libiconv`,
  `libjpeg-turbo`, `zlib`, `littlecms` (MIT) and `alsa-plugins` (plus one level of their own
  dependencies, currently `alsa-lib`, `pulseaudio` and `libandroid-sysv-semaphore`), and `libc++`
  is always added. For AAPT2 it is `abseil-cpp`, `libprotobuf`, `fmt`, `libexpat`, `libpng`,
  `libzopfli`, `zlib` and `libc++`. The licenses of these Termux libraries vary and have not been
  verified here.

## Setup Instructions

```bash
# 1. Clone. gradlew is committed without the executable bit (mode 100644), so
#    make it executable (or run every command below as `sh gradlew …`):
gh repo clone SexualMoose/AndroidCompiler
cd AndroidCompiler
chmod +x gradlew

# 2. Install JDK 21 and the Android SDK (platform 35). Point local.properties
#    (gitignored) at your SDK if ANDROID_HOME is not set:
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 3. (Usually skip) Refresh the bundled native launchers from Termux. The
#    committed prebuilts in prebuilts/native-jniLibs/ always take priority, and
#    files already in app/build/generated/native-jniLibs/ are never overwritten.
#    To force a download you must remove the prebuilt(s) you want refreshed,
#    app/build/generated/native-jniLibs/ and app/build/native-cache/ (or run
#    `./gradlew clean`); this needs network access to packages.termux.dev.
#    WARNING: the pinned 17.0.19 .deb is gone, so a refresh now automatically
#    falls back to the newest pool package (17.0.20). Those launchers do not match
#    the 17.0.19 runtime pin, and every on-device Gradle build would fail.
#    Only refresh as part of a full pin bump: change BOTH pins to the new
#    version (see Configuration), then refresh (see the fix steps in Known Issues).
```

## Build & Run

```bash
# Debug APK of AndroidCompiler itself
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Install onto a connected device/emulator (arm64-v8a or x86_64)
./gradlew installDebug

# Release build (R8 minify + resource shrinking). No signingConfig exists in
# this repo, so the output is unsigned; sign it yourself before installing.
./gradlew assembleRelease

# Run only the native-launcher preparation step
./gradlew :app:prepareNativeBinaries
```

The custom convention plugin `androidcompiler.native.binaries`
(`build-logic/convention/src/main/kotlin/AndroidNativeBinariesConventionPlugin.kt` +
`PrepareNativeBinariesTask.kt`) registers `prepareNativeBinaries` on `:app`. It seeds the
`lib*.so` launchers from `prebuilts/native-jniLibs/` into
`app/build/generated/native-jniLibs/` (wired as a jniLibs source dir). Only if a binary is still
missing does it download the pinned Termux `openjdk-17` / `aapt2` `.deb` packages into
`app/build/native-cache/` and extract them. If a pinned URL fails, it falls back to the newest
`.deb` in the Termux pool listing. That fallback does not check the version: today it would
supply 17.0.20 launchers against the 17.0.19 runtime pin. Copying and extraction both skip files
that already exist. With the committed prebuilts in place (the normal case) no download happens.
`useLegacyPackaging = true` must stay set so the installer extracts these binaries to
`nativeLibraryDir`.

## Usage

1. Install AndroidCompiler on an arm64-v8a/x86_64 device running Android 12+ (see Technical
   Requirements).
2. First launch shows a **Setup** screen whenever the base toolchain is incomplete — tap
   **Download All Components** (ecj, kotlinc, kotlin-stdlib, kotlin-script-runtime, AAPT2, d8,
   OpenJDK 17, Gradle wrapper, `android.jar` API 34). The **Compile** action refuses to run until
   *all* base components are installed, even for Gradle projects. **Currently the OpenJDK 17
   download fails (HTTP 404, see Status), so a fresh install never leaves this screen.**
3. Four tabs: **Compiler**, **Monitor**, **Components**, **Settings**.
4. **Compiler**: **Select ZIP** → **Compile**. Once a project is loaded, a "Build versions" card
   lets you override the Gradle version (7.6.4 – 8.13; default 8.11.1) and compile SDK (API 29–35;
   default 34). The chosen Gradle version is written into the project's
   `gradle-wrapper.properties` and the wrapper downloads that distribution itself; for the compile
   SDK only the base API 34 `android.jar` is actually provisioned (see Known Issues). Gradle
   projects run `gradlew assembleDebug --no-daemon` on-device, with build outputs redirected to
   internal storage.
5. On success, Gradle builds write the APK to the app's external-files `output/` directory (plus
   a best-effort copy into the output folder chosen in Settings); simple builds write it straight to
   the output folder. Tap **Install APK** to hand it to the system installer via `FileProvider`.
   If a simple build used a custom output folder, **Install APK** crashes the app (see Known
   Issues). On failure, **Generate Claude Fix Prompt** copies a prompt containing the errors and
   source context to the clipboard (nothing is sent anywhere).
6. **Components**: per-component status/progress with a Download action, Download All, Refresh,
   and a **Gradle Build Cache → Clear** action (clears the downloaded AGP/KSP/Hilt
   artifacts used by on-device Gradle builds). The screen has an Update button, but it never
   appears because no code produces `ComponentStatus.UpdateAvailable`. Toolchain updates are
   applied from **Settings** instead.
7. **Monitor**: live CPU, RAM and network-throughput readouts (GPU status is shown but never
   becomes active — see Known Issues).
8. **Settings**: theme (Light/Dark/OLED), default output folder, incremental output file names
   (`name_v2.apk`, …; simple path only), create a debug keystore, import a keystore
   (debug-password keystores only; see Known Issues), and check for/apply toolchain updates (ecj,
   kotlinc/kotlin-stdlib/kotlin-script-runtime, d8). Core-type, network-type and
   default-input-folder settings are stored but currently have no effect.

Diagnostics: logcat tag `ACBuild`; persistent `last_compile.log` (rotated at 1 MB) and
`gradle_build_output.txt` in the app's private files directory.

## Architecture

Multi-module Gradle project (Hilt DI, MVVM + Compose). Modules from `settings.gradle.kts`:

- `app/` — `AndroidCompilerApplication`, `MainActivity`, `MainViewModel`, `navigation/AppNavHost`
  (Setup route + 4 bottom-bar tabs), manifest, resources/icons.
- `core/common/` — models (`CompilationState`, `ToolchainComponent`, `Settings`, `ThemeMode`,
  `HardwareMetrics`) and `UriPathResolver` (SAF tree URI → file path).
- `core/data/` — `SettingsRepository` / `DataStoreSettingsRepository` (DataStore `settings`) +
  Hilt `DataModule`.
- `core/ui/` — Compose theme (Color/Shape/Spacing/Type/Theme).
- `feature/compiler/` — `CompilerScreen`, `CompilerViewModel` (runs the pipeline in its own
  coroutine scope) and `CompilationService` (a foreground service declared in the manifest but not
  currently started by any code).
- `feature/components/` — `SetupScreen`, `ComponentsScreen`, `ComponentsViewModel`.
- `feature/monitor/` — `MonitorScreen`, `MonitorViewModel` (CPU and network from `/proc`, RAM from
  `ActivityManager`).
- `feature/settings/` — `SettingsScreen`, `SettingsViewModel`.
- `network/` — `ChunkedDownloader` (OkHttp parallel HTTP range requests, single-stream fallback)
  and `MultiNetworkManager` (intended to spread chunks across Wi-Fi/cellular).
- `toolchain/` — the build engine:
  - `registry/ToolchainRegistry` — component definitions, download sources, file layout,
    API → platform-ZIP map, supported Gradle versions, bundled-binary locations.
  - `download/ComponentDownloadManager` — downloads/extracts components; Termux `.deb`
    (ar → tar.xz) handling, symlinks, exec bits.
  - `jdk/TermuxJdkInstaller` — installs the Bionic-linked OpenJDK 17 runtime, pinned to
    `BUNDLED_JDK_VERSION`.
  - `pipeline/` — `CompilationPipeline` (orchestrator), `ProjectAnalyzer`, `GradleCompiler`
    (on-device `assembleDebug` runner, with one one-shot retry: either a JVM-init self-repair that
    re-downloads the pinned JDK, or a dependency-cache reset that wipes Gradle's
    `caches/modules-2`, `transforms-4` and `jars-9`; plus storage-permission diagnostics),
    `PipelineSteps.kt` (`ProjectExtractor`, `ResourceCompiler`, `SourceCompiler`, `DexCompiler`,
    `ApkPackager`, `ApkAligner`, `ApkSigner`), `BuildDiagnostics`.
  - `signing/KeystoreManager` (+ internal `CertificateGenerator`) — generates the debug keystore
    with a hand-built self-signed X.509 cert and signs APKs via `apksig` (v1/v2/v3).
  - `classloader/ToolchainClassLoaderFactory` — `URLClassLoader` for downloaded compiler JARs.
  - `compute/` — `PerformanceHintHelper` (PerformanceHintManager sessions),
    `BundledBinariesValidator` (unused: nothing references it), `GpuHasher` (CPU SHA-256; Vulkan
    path is a stub).
  - `update/ComponentUpdateChecker` — queries Maven Central search and Google Maven metadata for
    newer ecj/Kotlin/R8.
- `build-logic/convention/` — convention plugins `androidcompiler.android.library`,
  `.android.feature`, `.android.compose`, `.android.hilt`, `androidcompiler.native.binaries`, and
  `PrepareNativeBinariesTask`.

Data flow: project ZIP (SAF) → copied to cache → `ProjectExtractor` (zip-slip-guarded). The
project is extracted to `<output folder>/build/project`, where `<output folder>` is the folder set
in Settings, or the app's external-files `output/` if none is set. `<output folder>/build` is
deleted at the start of each compile. Then `ProjectAnalyzer` (Gradle vs simple; Gradle/SDK
versions) → either `GradleCompiler` or the simple `PipelineSteps` chain:

- `GradleCompiler` execs the bundled `java` launcher, which runs `GradleWrapperMain assembleDebug`
  against the downloaded JDK, with an app-private Gradle user home, a synthetic on-device SDK, and
  an init script that redirects build dirs to internal storage. AGP signs with its standard debug
  keystore. The APK goes to the app's external-files `output/`, with a best-effort copy to the
  Settings folder.
- The simple `PipelineSteps` chain signs with `KeystoreManager` and writes the APK to the output
  folder.

The APK is then handed through `FileProvider` to the system installer.

## Integrations & Interconnects

- **Termux package repository** (`packages.termux.dev/apt/termux-main`) — Bionic-linked OpenJDK 17
  and AAPT2 `.deb` packages and their library dependencies. At build time it is used only if
  prebuilts are missing. At runtime it supplies the JDK and AAPT2 installs. The JDK package is
  pinned to 17.0.19, which now returns 404. The `aapt2` / `aapt` packages and all library
  dependencies come from the live `Packages` index, unpinned.
- **Maven Central** (`repo1.maven.org`, `search.maven.org`) — ecj, kotlinc, kotlin-stdlib,
  kotlin-script-runtime, R8 fallback; update checks.
- **Google** (`dl.google.com`) — R8 from Google Maven (+ `maven-metadata.xml` update checks) and
  Android platform ZIPs (`android.jar`).
- **Gradle** (`services.gradle.org`, `raw.githubusercontent.com/gradle/gradle`) — Gradle
  distributions and `gradle-wrapper.jar`. On-device Gradle builds also fetch whatever the
  compiled project's own repositories/dependencies require.
- **Eclipse** (`repo.eclipse.org`) — ecj fallback mirror.
- **Termux app** — `GradleCompiler.findJavaHome()` / `findAndroidSdk()` probe the standard Termux
  JDK/SDK install paths in code. The JDK probe is never reached, because Compile first requires the
  app's own version-matched JDK (`ToolchainRegistry.getComponentStatus`). An installed Termux JDK
  therefore cannot work around the dead JDK download.
- **Android system** — `PackageInstaller` (via `FileProvider`), Storage Access Framework pickers,
  clipboard, `PerformanceHintManager`, `ConnectivityManager`.
- **Portfolio repos:** none referenced in code. The app contains no fleet-server client,
  self-update or telemetry code. Release builds for the owner's own devices are produced and signed
  by separate, self-hosted release tooling outside this repo.
- No cloud AI API is called; the "Claude Fix Prompt" feature only copies text to the clipboard.

## Configuration & Secrets

- **No secrets are required or committed.** No API keys, tokens, `.env`, `google-services.json`,
  keystore files or `local.properties` are tracked (`.gitignore` excludes `local.properties`,
  `*.jks`, `*.keystore`, build outputs).
- `local.properties` → `sdk.dir` (gitignored; supply locally) or `ANDROID_HOME`.
- **JDK version pin** — two literals that must stay identical:
  - `bundledJdkVersion = "17.0.19"` in `app/build.gradle.kts`. It sets
    `:app` `BuildConfig.BUNDLED_JDK_VERSION`, and it drives only the Termux `.deb` URLs used when a
    prebuilt is missing. The committed prebuilts always take priority, so a version bump must also
    replace the prebuilt files.
  - `BUNDLED_JDK_VERSION` in `toolchain/build.gradle.kts`, which drives the runtime JDK download.

  A launcher ↔ `libjvm.so` patch mismatch makes every Gradle build fail. Both are still 17.0.19,
  which Termux no longer serves (see Known Issues).
- Termux `.deb` URLs for the native-binary step: `nativeBinaries { packagesByAbi … }` in
  `app/build.gradle.kts`.
- On-device debug keystore: generated at runtime in app-private storage (`keystores/`) using the
  standard, publicly documented Android debug-keystore alias/password conventions (hard-coded in
  `KeystoreManager`) — a convention, not a secret; debug-signed APKs are for sideloading only.
- Imported keystores: there is no password prompt. Import always uses the standard Android
  debug-keystore password (`SettingsScreen.kt:73`), so a keystore with any other password fails to
  import. A successful import is copied to app-private `keystores/import_keystore.tmp`, replacing
  any earlier import. (Imported keystores are not currently used for signing; see Known Issues.)
- App settings: AndroidX DataStore `settings` (theme, output/input folders, toggles), app-private.
- Runtime environment variables read by `GradleCompiler` if set: `ANDROID_HOME`,
  `ANDROID_SDK_ROOT`, `JAVA_HOME` (normally unset for an app process).
- Release signing of AndroidCompiler itself is configured outside this repo; `app/build.gradle.kts`
  has no `signingConfig` and no key material.

## Testing

- No unit or instrumentation tests exist (no `src/test` / `src/androidTest` directories, no test
  dependencies). `testInstrumentationRunner = androidx.test.runner.AndroidJUnitRunner` is declared
  but unused. No CI.
- Verification is manual/on-device, aided by `BuildDiagnostics` (logcat tag `ACBuild`,
  `last_compile.log`, `gradle_build_output.txt`).
- Suggested first tests: TAR/`.deb`/ZIP parsing, DER certificate generation
  (`CertificateGenerator`), `ProjectAnalyzer` detection, version/URL resolution in
  `ToolchainRegistry`.

## Known Issues / TODO

- **Simple (non-Gradle) path is likely non-functional on-device:** `SourceCompiler`/`DexCompiler`
  run ecj, kotlinc and d8 in-process through `URLClassLoader`, which (per the project's own commit
  history) ART cannot use to load JVM `.class` files. Unverified on a device in this audit. It
  also passes `--min-api 35` to d8, so APKs from that path would target Android 15+ only.
- **Compile requires the whole base toolchain**, including ecj/kotlinc/d8, even for Gradle
  projects that do not use them.
- **Broken for new installs — runtime JDK download returns 404.** The runtime JDK is pinned to
  Termux `openjdk-17` 17.0.19. The Termux pool keeps only the latest patch, and it now carries only
  17.0.20. `curl -I …/pool/main/o/openjdk-17/openjdk-17_17.0.19_aarch64.deb` (and the x86_64 file)
  returned **404** on 2026-10-10, re-checked 2026-10-11. `TermuxJdkInstaller` has no fallback for
  this file, so the JDK never installs: a fresh install is stuck on Setup and Compile refuses to
  run. Devices that already have 17.0.19 keep working. However, on those devices a JVM-init
  failure triggers `GradleCompiler`'s self-repair, which deletes the JDK before trying the same
  dead URL, leaving them broken too. Fix (code/build change, owner action):
  1. Bump `bundledJdkVersion` in `app/build.gradle.kts` (line 21) **and** the
     `BUNDLED_JDK_VERSION` literal in `toolchain/build.gradle.kts` (line 18) to `17.0.20`.
  2. Replace `prebuilts/native-jniLibs/{arm64-v8a,x86_64}/lib{java,javac,jar,jdeps,jlink,jmod,keytool,jli}.so`
     with the 17.0.20 binaries. One way: delete those 16 files, run
     `./gradlew clean :app:prepareNativeBinaries` (it downloads the now-valid 17.0.20 `.deb`s), then
     copy the same 16 files from `app/build/generated/native-jniLibs/<abi>/` back into
     `prebuilts/`.
  3. Rebuild and reinstall the APK. On devices that hold 17.0.19, the readiness gate then reports
     the JDK as not installed, and Setup downloads the matching 17.0.20.

  The same breakage will recur at every future Termux `openjdk-17` patch bump.
- **Android 9–11 (API 28–30) is expected to crash at launch:** `MainActivity.kt:28` calls
  `getSystemService(PerformanceHintManager::class.java)` with no `SDK_INT` check, and
  `PerformanceHintManager` only exists from API 31. Only `PerformanceHintHelper`'s own calls are
  guarded. Not tested on Android 9–11; `minSdk` 28 is therefore misleading.
- **Simple path + custom output folder: Install APK crashes.** Simple builds write the APK straight
  into the Settings output folder, which lies outside the `FileProvider` roots in
  `res/xml/file_paths.xml`. `CompilerScreen` calls `FileProvider.getUriForFile` without a
  try/catch, so it throws `IllegalArgumentException` and the app crashes. Gradle builds are not
  affected, because their primary APK is in the app's external-files `output/`.
- **Data-loss risk in the output folder:** every compile deletes `<output folder>/build`
  recursively before extracting the project to `<output folder>/build/project`. If the Settings
  output folder is a user folder that already has a `build/` directory, that directory is wiped.
- **AAPT2 version skew (unpinned):** the bundled AAPT2 is 13.0.0.6, but it runs against
  `libandroid-fw`/`-base`/`-utils`/`-ziparchive`, `libprotobuf` and `abseil` libraries from the
  live-index `aapt`/`aapt2` packages (currently 16.x), loaded via `LD_LIBRARY_PATH`. ABI
  compatibility has not been verified. This is the same kind of risk as the JDK pin.
- `CompilationService` (foreground service) is declared but never started; builds run in the
  `CompilerViewModel` scope and can be lost if the app process is killed in the background.
- Imported custom keystores are stored but never used: the simple path always signs with the
  generated debug keystore, and Gradle builds use AGP's debug signing. Import itself is also
  limited: there is no password prompt and the standard debug-keystore password is always used
  (`SettingsScreen.kt:73`), so only keystores with that password load. The keystore is opened as
  the platform default type (`KeyStore.getDefaultType()`); which formats that accepts on-device has
  not been tested. Every successful import is saved as `keystores/import_keystore.tmp`, replacing
  the previous one.
- Settings → Core Types, Network Types and Default Input Folder are persisted but not read by any
  code. `MultiNetworkManager.startMonitoring()` is never called, so downloads use only the default
  network.
- Per-API `android.jar` variants and per-version Gradle distributions have download functions in
  `ComponentDownloadManager` (`downloadAndroidJarForApi`, `downloadGradleDistribution`) but nothing
  calls them, so the compile-SDK override falls back to the base API 34 `android.jar`.
- `GpuHasher`'s Vulkan path is a TODO stub and nothing calls the hasher, so the Monitor tab's GPU
  status never changes.
- Dead code: `BundledBinariesValidator` (`toolchain/compute/`) is referenced nowhere outside its
  own file. No code produces `ComponentStatus.UpdateAvailable`, so the Components screen's Update
  button never appears; updates are only applied from Settings.
- The Termux JDK fallback in `GradleCompiler.findJavaHome()` is unreachable, because Compile
  first requires the app's own version-matched JDK.
- `gradlew` is committed without the executable bit (mode `100644`), so `./gradlew` fails with
  "permission denied" on macOS/Linux until you run `chmod +x gradlew` (or use `sh gradlew`).
- Settings → About shows a hard-coded "v1.0.0" (actual `versionName` is 1.1.10).
- `app/src/main/res/drawable-nodpi/splash.png` (~1 MB) is not referenced anywhere.
- Toolchain-download integrity hardening is an open TODO (see Security notes).
- No automated tests; no CI; no LICENSE (see below).
- **Compliance gap:** the GPLv2 + Classpath Exception text, the Apache-2.0 text and a
  corresponding-source offer are not yet included in the repo or in built APKs. The source offer
  would cover the OpenJDK 17.0.19 sources and the Termux `openjdk-17`/`aapt2` build recipes at the
  exact revisions used. `NOTICE` is not packaged into the APK either. See Third-party & Licensing.
- `PROJECT-DOCUMENTATION.docx` is out of date with this file until it is regenerated.
- The on-device Gradle path is inherently sensitive to OEM/SELinux variation; most of the commit
  history is fixes for exactly this.

## Third-party & Licensing

- **Project license: none chosen yet.** There is no LICENSE file, so all rights are reserved by
  default even though the repository is public. Choosing a license is an owner decision.
- **Copyleft — bundled OpenJDK binaries (GPLv2 with Classpath Exception).** The repo and every
  APK built from it redistribute the OpenJDK 17.0.19 launcher ELFs and `libjli.so` (Termux
  builds). They are separate executables that the app execs as processes, so the app's own code
  is not linked against them, but GPLv2 terms still apply to the binaries themselves when the
  repo or APK is distributed: include the license text and make the corresponding source
  available (OpenJDK 17u source plus Termux's build recipe/patches). See [NOTICE](NOTICE).
- **Not yet done (owner action):** add the GPLv2 + Classpath Exception and Apache-2.0 license texts
  (for example under `licenses/`), and a written source offer or link for the OpenJDK 17.0.19 and
  Termux recipe sources (the `openjdk-17` and `aapt2` recipes at the exact revisions used). Today
  the repo and the APK contain none of these, and `NOTICE` is not packaged into the APK, so the
  obligations above are not met.
- **AAPT2 (`libaapt2.so`)** — Apache-2.0 (AOSP build tools, Termux build); attribution and the
  Apache-2.0 license text should accompany distribution.
- **Gradle wrapper** (`gradle-wrapper.jar`, `gradlew`, `gradlew.bat`) — Apache-2.0.
- **Runtime downloads** are fetched by the end user's device directly from upstream and are not
  redistributed by this repo: ecj (EPL-2.0, weak copyleft), Kotlin (Apache-2.0), R8/d8
  (BSD-3-Clause), Gradle (Apache-2.0), the Android platform-34 ZIP (Android SDK terms), the Termux
  OpenJDK runtime (GPLv2 + CPE), the Termux `aapt2`/`aapt` packages (Apache-2.0) and Termux
  libraries. The libraries' licenses vary: they include LGPL `libiconv` and MIT `littlecms`, and
  the set follows the live Termux index (see Dependencies).
- **Android SDK license acceptance:** to make AGP accept its synthetic on-device SDK,
  `GradleCompiler` writes the Android SDK license-acceptance marker files itself; the user is never
  shown the Android SDK License Agreement. Worth a conscious decision before any wider
  distribution.
- **Trademarks:** the app name / `applicationId` (`AndroidCompiler` / `com.androidcompiler`) leads
  with Google's "Android" trademark, which Google's brand guidelines restrict in app names (a
  concern only for public/Play distribution). "Claude" (Anthropic) appears in the "Generate Claude
  Fix Prompt" button label; "Termux", "Gradle", "Kotlin" and "Eclipse" are referenced descriptively
  as upstream sources.
- **Own assets:** the launcher icon and `splash.png` are AI-generated app art produced for this
  project (per commit history: "Restore AI launcher icon", "regenerate app art (v11)"); not
  third-party. Not a fork — the first commit (`dcc407a`) is original scaffolding.

## Security notes

- **Trust boundaries:** the app downloads and executes toolchain binaries and JARs fetched over
  HTTPS from public package repositories (Termux, Maven Central, Google, Gradle, Eclipse), and it
  runs the build scripts of whatever project ZIP the user selects — a Gradle build is arbitrary
  code running with the app's permissions (including `REQUEST_INSTALL_PACKAGES` and `INTERNET`).
  Only compile projects you trust.
- **Auth model:** none — there are no accounts, servers or API keys. The only signing material is
  the on-device debug keystore (public debug conventions) and any keystore the user imports, both
  in app-private storage.
- **Secrets:** none in the repo; release-signing keys for AndroidCompiler itself live outside the
  repo.
- **Posture:** default OkHttp TLS validation, a zip-slip guard on extraction, no WebView/JS
  bridge, only the launcher activity exported, `FileProvider` not exported and limited to the
  app's own `toolchain/`, `compiled/` and `output/` directories.
- **Backup:** `AndroidManifest.xml` sets `android:allowBackup="true"` and defines no backup rules.
  App-private data, including the generated debug keystore and the most recently imported
  keystore in `keystores/`, can therefore be included in Android backup / device-transfer.
- **Known risk categories** (detail kept in the owner's private audit notes, not in this public
  repo): supply-chain integrity of runtime-downloaded toolchain components; executing untrusted
  project build logic on-device; general hardening of the build-process launch path.
