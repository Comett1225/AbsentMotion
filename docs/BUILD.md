# Building AbsentMotion

From a fresh checkout to a running Android app and the release files. The project is a Kotlin/Compose user interface
with a C++ engine on top of Skia, which is built from source.

> macOS builds and the Debian CI build are supported. A fresh checkout still needs the SDK, NDK, JDK and native libraries described below.

## 1. What you can build, and where

The Android app (`app/`) builds to APKs for `arm64-v8a` in three flavours, `play`, `foss` and `compat`, plus `compat32`, the Compatible build for
32-bit ARM (`armeabi-v7a`) (section 6). The
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
| `third_party/ncnn` | ncnn **20261020** (BSD-3-Clause) with its glslang copy, the on-device AI runtime | Pinned git submodule with a nested glslang submodule; `git submodule update --init --recursive` fetches both. |
| `third_party/rife_warp` | The `rife.Warp` layer from rife-ncnn-vulkan (MIT) | Vendored copy, unchanged, with its licence. |
| `third_party/rnnoise` | RNNoise (BSD-3-Clause), for noise reduction | Vendored copy in the tree. Meant to become a pinned submodule; the upstream commit is not recorded yet. |
| `third_party/skia` | Skia | **Not in the repository** (git-ignored). Fetched by `tools/build_skia.sh`. |

Nothing else needs to be fetched by hand. The Whisper speech models and the AI Enhance and AI Smooth models are not part
of the build: the app downloads each one the first time it is used.

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

`tools/build_skia.sh compat` builds the same Skia a second time with `ndk_api = 28` instead of the value in
`tools/skia-args.gn` into `third_party/skia/out/android-arm64-compat/` (about 180 MB), which the `compat` flavour links. The symbols it uses
only need Android 8, which `tools/compat/compat_check.sh` checks with `tools/api_floor.py --min 26`. `tools/build_skia.sh compat32` builds it for
32-bit ARM (`target_cpu = "arm"`, `ndk_api = 26`) into `third_party/skia/out/android-arm-compat/`, which the `compat32` flavour links.

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
./gradlew :app:assembleCompatDebug   # the Compatible build for Android 8 to 12 (needs tools/build_skia.sh compat first)
./gradlew :app:assembleCompat32Release   # the Compatible build for 32-bit ARM phones (needs tools/build_skia.sh compat32 first)
```

Gradle runs CMake for you (`externalNativeBuild`): it builds the engine (`libamengine.so`), the speech library
(`libwhisper_jni.so`, from `third_party/whisper.cpp`), the AI runtime (`libabsent_ai.so`, ncnn from `third_party/ncnn`
with the layer in `third_party/rife_warp`) and RNNoise (from `third_party/rnnoise`), for `arm64-v8a` (`armeabi-v7a` in `compat32`).
Skia from section 5 must be in place first.

Outputs: `app/build/outputs/apk/<flavour>/<type>/app-<flavour>-<type>.apk`, for example `app/build/outputs/apk/play/debug/app-play-debug.apk`. The foss flavour adds its own offline background remover (`app/src/foss`); the play flavour uses Google ML Kit through Google Play services (`app/src/play`).

- **Requirements of the app:** Android 13 (API 33) or newer (`minSdk = 33`, tested on Android 16 and 17 and on emulators from Android 13), `targetSdk = 37`,
  a 64-bit ARM device, Vulkan 1.1 or OpenGL ES 3.0 (`tools/api_floor.py` lists the Android version the native libraries need).
- **Release signing:** `assemblePlayRelease` and `assembleFossRelease` sign with the key named in a git-ignored `signing.properties` in the project root
  (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without that file the release APK is left unsigned (`app-<flavour>-release-unsigned.apk`),
  and you sign it yourself with `apksigner`. Debug builds (`assembleFossDebug`) are signed with the debug key for your own phone. Make a release key with
  `keytool -genkeypair -v -keystore release.jks -alias absentmotion -keyalg RSA -keysize 4096 -validity 10000` and never
  commit it.
- **The Compatible build (`compat`):** the foss build for Android 8 to 12 (`minSdk = 26`), with the same app id, version and signing key, so a
  phone that later gets Android 13 can install the Latest build over it and keep its projects. It uses the foss sources and assets, links the API 28
  Skia (CMake option `AM_COMPAT`), and gates the features it leaves out with `Tier` (`app/src/main/kotlin/app/absentmotion/Tier.kt`: every `Feature`
  says whether it is in Compatible; left-out ones are shown locked with "Needs Android 13 (Latest version)"). Below Android 13 video export renders
  into its own GPU buffer and copies it into the encoder with OpenGL ES (`encoder_copy.cpp`), because the encoder's input surface cannot always be a
  Vulkan swapchain there; with the OpenGL back end the engine draws into the encoder's surface directly at every Android version. Below Android 9
  `Image.getHardwareBuffer` does not exist, so the video decoder gets its frames from the NDK `AImageReader` (`cpp/ndk_images.cpp`,
  `media/DecodedFrames.kt`), whose `AImage_getHardwareBuffer` works from Android 8; Vulkan 1.1 functions are looked up at run time so the engine
  still loads where `libvulkan` is older. `tools/compat/build_compat.sh` builds it, `tools/compat/compat_check.sh` checks it (native API floor 26, lint at minSdk 26,
  `tools/compat/tier_check.py`, publish check), and `tools/compat/compat_emulators.sh [28 30 32]` installs one arm64 emulator image at a time, runs
  `tools/smoke_check.py` and `tools/compat/export_smoke.py` (GIF and MP4) on it and deletes it again.
- **The 32-bit Compatible build (`compat32`):** the `compat` build for 32-bit ARM (`armeabi-v7a`, `minSdk = 26`), for phones with a 32-bit system
  such as many Android Go phones, on any Android from 8. Same sources, features, app id and key; the updater names its file
  `AbsentMotion-<version>-compatible-32bit.apk`. CMake links `out/android-arm-compat` when `ANDROID_ABI` is `armeabi-v7a`; the NEON paths of
  `absent_bg` and the faster caption library are arm64 only, so 32-bit runs the portable code. Apple silicon cannot run 32-bit ARM, so emulators
  there cannot run it: check it with `tools/api_floor.py --abi armeabi-v7a --min 26` and `tools/apk_check.py`, and test it on a real 32-bit phone.
- **Drawing back ends:** the engine (`app/src/main/cpp/engine.cpp`) starts Skia's Graphite on Vulkan 1.1. When Vulkan cannot start, or when
  **Settings**, **Graphics** is set to **OpenGL**, it uses Skia's Ganesh on OpenGL ES 3.0 instead (`Backend::kGaneshGL`: an EGL context with a
  1 x 1 pbuffer, window surfaces for the preview and the encoder, `eglPresentationTimeANDROID` for export timestamps, and
  `SkSurfaces::WrapAndroidHardwareBuffer` for encoder buffers). `EngineThread.startEngine` tries the preferred back end first and the other second, and
  the setting restarts the engine through the same path as GPU-loss recovery. Optical flow, Smooth, motion blur and Datamosh take a `GpuContext`
  (`interpolation.h`) and run on either back end. On OpenGL `Tier.openGl` locks AI Smooth, AI Enhance, AI background removal 2.0 and Optical flow
  High ("Needs a phone with Vulkan graphics"), and the AI models run on the CPU. Debug builds take `--ez gl true` on the launch intent to force OpenGL
  on a phone or emulator that has Vulkan. Test OpenGL on an emulator started with `-feature -Vulkan`; `python3 tools/gl_compare.py --work <folder>
  [--motions <folder of scene XML>]` renders every effect, text preset, test scene, extra scene and blend mode with both back ends on one emulator
  (debug build, Vulkan available) and writes `report.json` and `worst.png` with the pairs that differ. Known differences: the ray-marched 3D effects
  (Star Prism, Hex Prism) and noise effects such as VHS differ slightly between GPUs, and the emulators' own drivers get some video colours wrong
  (OpenGL: BT.709 decoded as BT.601; Vulkan on some images: swapped chroma), which `tools/video_color_check.py` shows.
- Install with `adb install -r <apk>`. Emulators work for development (arm64 image, Android 13 or newer for the Latest build; the Compatible build also
  runs on Android 8 to 12 images, through OpenGL when the image has no working Vulkan; `tools/smoke_check.py` renders a still on whichever device `ANDROID_SERIAL` names).

The signing helper uses each APK's manifest minimum Android version and build-tools 37.0.0. `python3 tools/apk_check.py <apk> [<apk> ...]` checks signatures at every supported API through 37, resource-table and native-library packaging, ELF alignment (16 KB pages on arm64, 4 KB on 32-bit ARM), and matching app IDs, versions and certificates across the supplied APKs. Use `--unsigned` for unsigned CI output and `--libraries <folder>` to check only the 16 KB alignment of built `.so` files. Signed APKs must carry the release certificate pinned as `RELEASE_SIGNER` in `UpdateSource.kt`, the key the in-app updater accepts. This validates packaging; installation and runtime testing on emulators and real devices remain necessary.

## 7. Checking a change, and releases

`tools/check.sh` compiles the Android flavours and runs the unit tests (`--full` also lints foss and compat and checks the native API floor and 16 KB page alignment of both).

`tools/release.sh [patch|minor|major]` bumps `version.properties` (with no argument it keeps the current version),
runs `tools/check.sh`, and builds the release APKs of every flavour (signed as described in section 6). Results:

- `build/release/AbsentMotion-<version>.apk` (the `play` flavour)
- `build/release/AbsentMotion-<version>-foss.apk` (the `foss` flavour)
- `build/release/AbsentMotion-<version>-compatible.apk` (the `compat` flavour, Android 8 to 12)
- `build/release/AbsentMotion-<version>-compatible-32bit.apk` (the `compat32` flavour, phones with a 32-bit system, Android 8 or newer)

## 8. CI and reproducible builds

`.github/workflows/build.yml` builds the unsigned `foss` release APK in a Debian trixie container on pull requests,
manual runs and version tags. It installs JDK 21 and the pinned Android SDK, NDK and CMake, builds Skia from source,
and uploads the `foss-apk` artifact; a second job builds the compat Skia for 64-bit and 32-bit ARM and the unsigned `compat` and `compat32` APKs (`compat-apk` artifact). The workflow
removes the Play sources and dependencies before building.

Two builds of the same commit must produce byte-identical unsigned APKs. Compare the downloaded artifacts with
`cmp first.apk second.apk` before a release. Keep the Gradle wrapper, Android plugin, Kotlin, NDK, Skia and native
source pins stable. The build disables VCS and dependency metadata and maps native source paths to stable values;
changes to those settings can break reproducibility. Release signing is a separate step.

Device scripts use only the device named by `ANDROID_SERIAL` (default `emulator-5554`). They are optional for the
build; use an emulator for development checks. `python3 tools/device_matrix.py checks --serial emulator-5556 --apk <debug apk>` runs
the smoke, memory-pressure, engine, device export, audio export, export and speed checks on one emulator and writes a JSON
report outside the repository; `status`, `create`, `boot`, `run`, `stop` and `clean` manage a matrix of arm64 emulators (Android 9,
13, 14, 16, a tablet and Android 17 with 16 KB memory pages). `create` stops when less than 15 GB would stay free. RNNoise is still vendored and its upstream commit is not recorded.
