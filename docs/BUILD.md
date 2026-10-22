# Building AbsentMotion

From a fresh checkout to a running Android app and the release files. The project is a Kotlin/Compose user interface
with a C++ engine on top of Skia, which is built from source.

> macOS builds and the Debian CI build are supported. A fresh checkout still needs the SDK, NDK, JDK and native libraries described below.

## 1. What you can build, and where

The Android app (`app/`) builds to an APK for `arm64-v8a` only, in three flavours: `play`, `foss` and `compat` (section 6). The
maintainer builds it on macOS (Apple silicon).

The build scripts support macOS and Linux with the Android NDK. CI builds the unsigned `foss` APK on Debian. Windows hosts are not supported.

## 2. Disk space

About 15 GB free (Skia with its dependencies, the Gradle cache, the Android SDK and NDK).

## 3. Tools to install

| Tool | Version | Used for |
|---|---|---|
| JDK | **21** | Gradle and Kotlin. A newer JDK (Homebrew's default is 27) does not work with the Android Gradle Plugin. |
| Android SDK | platform **37** (`android-37.0`), build-tools **37.0.0** | The Android app (`compileSdk = 37`). Install with Android Studio's SDK Manager or the command-line SDK tools. |
| Android NDK | **30.0.16248370** (r30) | The engine for Android (`ndkVersion` in `app/build.gradle.kts`). |
| CMake (Android SDK package) | **3.31.6** | Pinned in `app/build.gradle.kts` for the Android engine. |
| Ninja | any recent (1.13 used) | Skia (`ninja` must be on `PATH`). |
| Python | 3 | The Skia dependency sync and the helper scripts in `tools/`. |
| git | system | Fetching Skia and its dependencies. |

The Gradle wrapper downloads **Gradle 9.8.2** itself. The build files pin the Android Gradle Plugin 9.4.1, Kotlin 2.4.21
and the Compose BOM 2026.09.00.

**Homebrew:**

```
brew install openjdk@21 ninja
```

**JDK.** Set `JAVA_HOME` to JDK 21. The scripts in `tools/` source `tools/env.sh`, which uses `JAVA_HOME` if set and
otherwise looks for a JDK 21 (including Homebrew's).

**Android SDK location.** Create `local.properties` in the project root (it is git-ignored):

```
sdk.dir=/path/to/Android/sdk
```

Set `ANDROID_NDK` to the NDK directory if it is not at `~/Library/Android/sdk/ndk/30.0.16248370`.

## 4. Third-party sources in `third_party/`

| Directory | What | State |
|---|---|---|
| `third_party/whisper.cpp` | whisper.cpp / ggml **1.9.5** (MIT), for on-device captions | Pinned git submodule; initialise it with `git submodule update --init --recursive`. |
| `third_party/rnnoise` | RNNoise (BSD-3-Clause), for noise reduction | Vendored copy in the tree. Meant to become a pinned submodule; the upstream commit is not recorded yet. |
| `third_party/skia` | Skia | **Not in the repository** (git-ignored). Fetched by `tools/build_skia.sh`. |

Nothing else needs to be fetched by hand. The Whisper speech models are not part of the build: the app downloads one
the first time captions are generated.

## 5. Skia

Skia is pinned to **branch `chrome/m156`, commit `95ee33d7ec7791ab9c071675710a14fe6408ae4f`**, plus one local patch
(`tools/skia-patches/vulkan-dst-read-copy.patch`, which changes the Vulkan back end to copy the destination for
blend modes).

```
tools/build_skia.sh
```

What the script does, in order:

1. Fetches Skia into `third_party/skia` (if the directory is absent): a shallow fetch of the pinned commit or, if that
   fails, a shallow clone of the branch `chrome/m156`. It stops with an error if `third_party/skia` is not at the
   pinned commit.
2. Writes `DEPS.filtered`: Skia's dependency list without the parts an Android Vulkan build does not use (Dawn,
   ANGLE, SwiftShader, emsdk and others).
3. Fetches the pinned source dependencies with `tools/skia_sync.py`. Builds the pinned `gn` tool from source with `tools/build_gn.sh`.
4. Applies the patches in `tools/skia-patches/` if they are not applied yet.
5. Writes `third_party/skia/out/android-arm64/args.gn` (the NDK path plus `tools/skia-args.gn`), runs `gn gen` and
   `ninja`, then packs the path-operation objects into `libpathops.a` with the NDK's `llvm-ar` (from the host's `darwin-x86_64` or `linux-x86_64`
   directory).

`tools/build_skia.sh compat` builds the same Skia a second time for Android 9 (`ndk_api = 28` instead of the value in
`tools/skia-args.gn`) into `third_party/skia/out/android-arm64-compat/` (about 180 MB), which the `compat` flavour links.

**Output:** `third_party/skia/out/android-arm64/` holds the static libraries the Android engine links
(`libskia.a`, `libpathops.a`, `libskparagraph.a`, `libskshaper.a`, `libskunicode_core.a`, `libskunicode_icu.a` and the
libraries of Skia's own dependencies). `app/src/main/cpp/CMakeLists.txt` reads them from there.

**The pin.** If the script stops because `third_party/skia` is at another commit, check out the pinned commit in it
(`git -C third_party/skia fetch --depth 1 origin 95ee33d7ec7791ab9c071675710a14fe6408ae4f` and
`git -C third_party/skia checkout FETCH_HEAD`) and run the script again.

If Skia's server refuses to fetch the commit by hash, the script falls back to the tip of the branch and stops if that
is another commit.

## 6. Android app

```
./gradlew :app:assemblePlayDebug     # debug APK, play flavour (background removal through Google Play services)
./gradlew :app:assembleFossDebug     # debug APK, free flavour: no Google libraries at all (what F-Droid builds)
./gradlew :app:assemblePlayRelease   # release APKs: assembleFossRelease for the free flavour
./gradlew :app:assembleCompatDebug   # the Compatible build for Android 9 to 12 (needs tools/build_skia.sh compat first)
```

Gradle runs CMake for you (`externalNativeBuild`): it builds the engine (`libamengine.so`), the speech library
(`libwhisper_jni.so`, from `third_party/whisper.cpp`) and RNNoise (from `third_party/rnnoise`), for `arm64-v8a` only.
Skia from section 5 must be in place first.

Outputs: `app/build/outputs/apk/<flavour>/<type>/app-<flavour>-<type>.apk`, for example `app/build/outputs/apk/play/debug/app-play-debug.apk`. The foss flavour adds its own offline background remover (`app/src/foss`); the play flavour uses Google ML Kit through Google Play services (`app/src/play`).

- **Requirements of the app:** Android 13 (API 33) or newer (`minSdk = 33`, tested on Android 16 and 17 and on emulators from Android 13), `targetSdk = 37`,
  a 64-bit ARM device, Vulkan 1.1 (`tools/api_floor.py` lists the Android version the native libraries need).
- **Release signing:** `assemblePlayRelease` and `assembleFossRelease` sign with the key named in a git-ignored `signing.properties` in the project root
  (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without that file the release APK is left unsigned (`app-<flavour>-release-unsigned.apk`),
  and you sign it yourself with `apksigner`. Debug builds (`assembleFossDebug`) are signed with the debug key for your own phone. Make a release key with
  `keytool -genkeypair -v -keystore release.jks -alias absentmotion -keyalg RSA -keysize 4096 -validity 10000` and never
  commit it.
- **The Compatible build (`compat`):** the foss build for Android 9 to 12 (`minSdk = 28`), with the same app id, version and signing key, so a
  phone that later gets Android 13 can install the Latest build over it and keep its projects. It uses the foss sources and assets, links the API 28
  Skia (CMake option `AM_COMPAT`), and gates the features it leaves out with `Tier` (`app/src/main/kotlin/app/absentmotion/Tier.kt`: every `Feature`
  says whether it is in Compatible; left-out ones are shown locked with "Needs Android 13 (Latest version)"). Below Android 13 video export renders
  into its own GPU buffer and copies it into the encoder with OpenGL ES (`encoder_copy.cpp`), because the encoder's input surface cannot always be a
  Vulkan swapchain there. `tools/compat/build_compat.sh` builds it, `tools/compat/compat_check.sh` checks it (native API floor 28, lint at minSdk 28,
  `tools/compat/tier_check.py`, publish check), and `tools/compat/compat_emulators.sh [28 30 32]` installs one arm64 emulator image at a time, runs
  `tools/smoke_check.py` and `tools/compat/export_smoke.py` (GIF and MP4) on it and deletes it again.
- Install with `adb install -r <apk>`. Emulators work for development (arm64 image, Android 13 or newer; `tools/smoke_check.py` renders a still on whichever device `ANDROID_SERIAL` names).

## 7. Checking a change, and releases

`tools/check.sh` compiles the Android flavours and runs the unit tests (`--full` also lints foss and compat and checks the native API floor of both).

`tools/release.sh [patch|minor|major]` bumps `version.properties` (with no argument it keeps the current version),
runs `tools/check.sh`, and builds the release APKs of all three flavours (signed as described in section 6). Results:

- `build/release/AbsentMotion-<version>.apk` (the `play` flavour)
- `build/release/AbsentMotion-<version>-foss.apk` (the `foss` flavour)
- `build/release/AbsentMotion-<version>-compatible.apk` (the `compat` flavour, Android 9 to 12)

## 8. CI and reproducible builds

`.github/workflows/build.yml` builds the unsigned `foss` release APK in a Debian trixie container on pull requests,
manual runs and version tags. It installs JDK 21 and the pinned Android SDK, NDK and CMake, builds Skia from source,
and uploads the `foss-apk` artifact; a second job builds the API 28 Skia and the unsigned `compat` APK (`compat-apk` artifact). The workflow
removes the Play sources and dependencies before building.

Two builds of the same commit must produce byte-identical unsigned APKs. Compare the downloaded artifacts with
`cmp first.apk second.apk` before a release. Keep the Gradle wrapper, Android plugin, Kotlin, NDK, Skia and native
source pins stable. The build disables VCS and dependency metadata and maps native source paths to stable values;
changes to those settings can break reproducibility. Release signing is a separate step.

Device scripts use only the device named by `ANDROID_SERIAL` (default `emulator-5554`). They are optional for the
build; use an emulator for development checks. RNNoise is still vendored and its upstream commit is not recorded.
