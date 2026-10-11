# AndroidCompiler

On-device Android APK compiler: an Android app that builds Gradle-based Android projects directly
on a phone or tablet, with no PC, by bundling the OpenJDK 17 launcher and AAPT2 in its APK and
downloading the rest of the toolchain at runtime. No root and no Termux install required.

**Status:** **broken for new installs.** Termux replaced `openjdk-17` 17.0.19 with 17.0.20 (the
pinned 17.0.19 `.deb` returned 404 when checked on 2026-10-10), so a fresh install cannot finish
first-run setup. Devices that already downloaded 17.0.19 still work. Otherwise maintenance-only:
v1.1.10 (versionCode 12); the on-device Gradle (`assembleDebug`) path is the one that has been
exercised. No tests, no CI. Details and the fix steps:
[PROJECT-DOCUMENTATION.md](PROJECT-DOCUMENTATION.md).

## Quickstart

Requirements: JDK 21, Android SDK platform 35. Target device: arm64-v8a or x86_64 running
Android 12+ (API 31+). `minSdk` is 28, but Android 9–11 is expected to crash at launch and has not
been tested (see Known Issues in [PROJECT-DOCUMENTATION.md](PROJECT-DOCUMENTATION.md)).

```bash
gh repo clone SexualMoose/AndroidCompiler && cd AndroidCompiler
chmod +x gradlew               # gradlew is committed without the executable bit (or use: sh gradlew …)
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # gitignored
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # install on a connected device/emulator
```

On the device: first launch → **Download All Components** (the Setup screen says ~250 MB;
≈263 MB decimal) → **Compiler** tab → **Select ZIP** → **Compile** → **Install APK**.
**Currently broken on a fresh install:** the OpenJDK download fails with HTTP 404, so the app never
gets past the Setup screen until the JDK pin is bumped and the APK is rebuilt (see Known Issues
in [PROJECT-DOCUMENTATION.md](PROJECT-DOCUMENTATION.md)).

## License

No license has been chosen yet (no LICENSE file), so all rights are reserved by default. The
repository and APK bundle third-party binaries under their own licenses — including OpenJDK
launcher binaries under **GPLv2 with Classpath Exception** — see [NOTICE](NOTICE). The license
texts and source offer those licenses require are not yet included (see NOTICE).
