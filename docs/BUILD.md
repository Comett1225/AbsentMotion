# Building AbsentMotion

This guide takes you from a fresh checkout to a running Android app and the release files. The project is a
Kotlin/Compose user interface with a C++ engine on top of Skia. Skia is built from source and is the slow, disk-hungry
step.

> macOS builds and the Debian CI build are supported. A fresh checkout still needs the SDK, NDK, JDK and native libraries described below.

## 1. What you can build, and where

The Android app (`app/`) builds to an APK for `arm64-v8a` only, in two flavours: `play` and `foss` (section 6). The
maintainer builds it on macOS (Apple silicon).

The build scripts support macOS and Linux with the Android NDK. CI builds the unsigned `foss` APK on Debian. Windows hosts are not supported.

## 2. Disk space and time

Disk is the main constraint. As a rough estimate, keep **15 GB or more** free for everything. What the maintainer's
machine uses:

| What | Size |
|---|---|
| Skia checkout with its dependencies and the Android build output | about 1.4 GB |
| Gradle cache (`~/.gradle`) | several GB |
| Android SDK, NDK, CMake and an emulator image, if you use one | several GB |
| whisper.cpp and RNNoise sources (vendored in `third_party/`) | about 45 MB |

Time: the Android Skia build takes about 6 minutes on an M4 Mac mini once its dependencies are fetched. The first
fetch (about twenty git repositories) depends on your connection. Other times have not been measured on a clean
machine.

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

The Gradle wrapper downloads **Gradle 9.8.1** itself. The build files pin the Android Gradle Plugin 9.4.1, Kotlin 2.4.21
and the Compose BOM 2026.09.00.

**Homebrew packages that the maintainer's machine has** (install what you lack):

```
brew install openjdk@21 ninja
```

**JDK.** Build with JDK 21: set `JAVA_HOME` to it before running Gradle (a newer JDK is too new for the Android Gradle
plugin). The scripts in `tools/` source `tools/env.sh`, which uses `JAVA_HOME` if set and otherwise looks for a JDK 21
(including Homebrew's).

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

**Output:** `third_party/skia/out/android-arm64/` holds the static libraries the Android engine links
(`libskia.a`, `libpathops.a`, `libskparagraph.a`, `libskshaper.a`, `libskunicode_core.a`, `libskunicode_icu.a` and the
libraries of Skia's own dependencies). `app/src/main/cpp/CMakeLists.txt` reads them from there.

**The pin.** If the script stops because `third_party/skia` is at another commit, check out the pinned commit in it
(`git -C third_party/skia fetch --depth 1 origin 95ee33d7ec7791ab9c071675710a14fe6408ae4f` and
`git -C third_party/skia checkout FETCH_HEAD`) and run the script again.

*Not verified:* whether Skia's server allows fetching a commit by its hash. If it does not, the script falls back to the
tip of the branch, which can move, and stops if that is another commit.

*Not verified from a clean machine:* the whole of this section.

## 6. Android app

```
./gradlew :app:assemblePlayDebug     # debug APK, play flavour (background removal through Google Play services)
./gradlew :app:assembleFossDebug     # debug APK, free flavour: no Google libraries at all (what F-Droid builds)
./gradlew :app:assemblePlayRelease   # release APKs: assembleFossRelease for the free flavour
```

Gradle runs CMake for you (`externalNativeBuild`): it builds the engine (`libamengine.so`), the speech library
(`libwhisper_jni.so`, from `third_party/whisper.cpp`) and RNNoise (from `third_party/rnnoise`), for `arm64-v8a` only.
Skia from section 5 must be in place first.

Outputs: `app/build/outputs/apk/<flavour>/<type>/app-<flavour>-<type>.apk`, for example `app/build/outputs/apk/play/debug/app-play-debug.apk`. The free flavour has no background removal; the sources that differ are in `app/src/foss` and `app/src/play`.

- **Requirements of the app:** Android 14 (API 34) or newer (`minSdk = 34`; it builds and passes lint for that level, but it has only been run on Android 17), `targetSdk = 37`, a 64-bit ARM device,
  Vulkan 1.1.
- **Release signing:** `assemblePlayRelease` and `assembleFossRelease` sign with the key named in a git-ignored `signing.properties` in the project root
  (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without that file the release APK is left unsigned (`app-<flavour>-release-unsigned.apk`),
  and you sign it yourself with `apksigner`. Debug builds (`assembleFossDebug`) are signed with the debug key for your own phone. Make a release key once with
  `keytool -genkeypair -v -keystore release.jks -alias absentmotion -keyalg RSA -keysize 4096 -validity 10000`,
  keep it and its passwords offline (losing it means users can never update), and never commit it.
- Install on a device with `adb install -r <apk>`. An emulator has to meet the same requirements (arm64 image,
  Android 14 or newer, Vulkan 1.1); rendering on an emulator has not been checked.

## 7. Checking a change, and releases

`tools/check.sh` compiles both Android flavours and runs the unit tests.

`tools/release.sh [patch|minor|major]` bumps `version.properties` (with no argument it keeps the current version),
runs `tools/check.sh`, and builds the release APKs of both flavours (signed as described in section 6). Results:

- `build/release/AbsentMotion-<version>.apk` (the `play` flavour)
- `build/release/AbsentMotion-<version>-foss.apk` (the `foss` flavour)

## 8. CI and reproducible builds

`.github/workflows/build.yml` builds the unsigned `foss` release APK in a Debian trixie container on pull requests,
manual runs and version tags. It installs JDK 21 and the pinned Android SDK, NDK and CMake, builds Skia from source,
and uploads the `foss-apk` artifact. The workflow removes the Play sources and dependencies before building.

Two builds of the same commit must produce byte-identical unsigned APKs. Compare the downloaded artifacts with
`cmp first.apk second.apk` before a release. Keep the Gradle wrapper, Android plugin, Kotlin, NDK, Skia and native
source pins stable. The build disables VCS and dependency metadata and maps native source paths to stable values;
changes to those settings can break reproducibility. Release signing is a separate step.

Device scripts use only the device named by `ANDROID_SERIAL` (default `emulator-5554`). They are optional for the
build; use an emulator for development checks. RNNoise is still vendored and its upstream commit is not recorded.
