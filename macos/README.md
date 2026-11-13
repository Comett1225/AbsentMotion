# macos

What only macOS uses: Metal for the engine (`native/gpu_metal.mm`), the media engine on FFamp (`native/video_ffamp.mm` for the
preview decoder, `native/media_ffamp.cpp` for describing files, sound and frames; FFamp is the submodule `../third_party/FFamp`),
the sound output delay (`native/audio_mac.cpp`), the native build (`build.sh`, using `native/macos.cmake`) and the app icon.

The macOS app contains no FFmpeg: the native library links none and the app starts no `ffmpeg` or `ffprobe`. Reading, playing,
export, the tools, the camera and the video downloader all go through FFamp (`MediaEngine` in
`pc/src/standins/kotlin/app/absentmotion/media`). FFamp uses libopus, LAME, libvorbis, libogg, OpenAPV, libvpx, dav1d and SVT-AV1
(and mpg123 when LAME is built with its decoder) when CMake finds them.

Needs Skia built for macOS in `../third_party/skia/out/mac-v2-2-arm64` (Apple silicon) or `out/mac-x64` (Intel, same
`args.gn` with `target_cpu = "x64"`). `cd ../pc && ./gradlew createDistributable` makes `AbsentMotion.app` for this Mac with
the codec libraries Homebrew has installed, so that copy only runs where those are installed. `./gradlew installMac` copies it
to `/Applications` (or `-PinstallDir=<folder>`), records a start-up class archive there and signs the app. The archive is
made after the copy because Java 21 only accepts it while the app's files sit at the exact paths and dates they had when
it was made; it saves about a second of start-up work. A copy moved elsewhere still starts, just without the archive.

Release builds carry their own codec libraries and Java, so they run on a Mac without Homebrew. Build the libraries once per
chip from their pinned source archives (checksums are checked; needs CMake, Ninja, Meson and NASM):

    ../third_party/FFamp/third_party/build_mac.sh arm64 <folder for arm64>
    ../third_party/FFamp/third_party/build_mac.sh x86_64 <folder for x86_64>

Everything is static, for macOS 11 and later. The app ships the JDK that runs Gradle (`JAVA_HOME`), which must be a JDK 21 for
that chip that does not link Homebrew libraries (JetBrains Runtime; Homebrew's `openjdk` links Homebrew's freetype, harfbuzz
and others), so use the root `gradlew` (`pc/gradlew` picks the Mac's JDK 21). Intel Macs are built on an Apple-silicon Mac
under Rosetta:

    FFAMP_DEPS=<folder for arm64> JAVA_HOME=<arm64 JDK 21> ./gradlew -p pc -Ptarget=macos-arm64 packageDmg
    FFAMP_DEPS=<folder for x86_64> JAVA_HOME=<x86_64 JDK 21> arch -x86_64 /bin/sh ./gradlew -p pc -Ptarget=macos-x64 packageDmg

`build.sh arm64` or `build.sh x64` puts the native library in `pc/build/native-arm64` or `pc/build/native-x64`. It ignores
Homebrew and stops if the library links anything outside the system. The minimum macOS is 11, so whisper is built without its
BLAS back end (Accelerate's new BLAS needs macOS 13.3). `createDistributable` signs the app ad hoc and `packageDmg` puts it in
a disk image. The library versions are in `THIRD_PARTY_NOTICES.md`.
