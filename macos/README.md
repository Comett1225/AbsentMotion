# macos

What only macOS uses: Metal for the engine (`native/gpu_metal.mm`), VideoToolbox video frames (`native/video_mac.mm`), the
sound output delay (`native/audio_mac.cpp`), the native build (`build.sh`, using `native/macos.cmake`) and the app icon.

Needs Skia built for macOS in `../third_party/skia/out/mac-v2-2-arm64` (Apple silicon) or `out/mac-x64` (Intel, same
`args.gn` with `target_cpu = "x64"`). `cd ../pc && ./gradlew createDistributable` makes `AbsentMotion.app` for this Mac
against Homebrew's `ffmpeg`, so that copy only runs where Homebrew FFmpeg is installed. `./gradlew installMac` copies it
to `/Applications` (or `-PinstallDir=<folder>`), records a start-up class archive there and signs the app. The archive is
made after the copy because Java 21 only accepts it while the app's files sit at the exact paths and dates they had when
it was made; it saves about a second of start-up work. A copy moved elsewhere still starts, just without the archive.

Release builds carry their own FFmpeg and Java, so they run on a Mac without Homebrew. Each chip needs an FFmpeg built for
it with `--install-name-dir=@rpath` and its licence text as `ffmpeg-LICENSE.txt` in the prefix. The app ships the JDK that
runs Gradle (`JAVA_HOME`), which must be a JDK 21 for that chip that does not link Homebrew libraries (JetBrains Runtime;
Homebrew's `openjdk` links Homebrew's freetype, harfbuzz and others), so use the root `gradlew` (`pc/gradlew` picks the
Mac's JDK 21). Intel Macs are built on an Apple-silicon Mac under Rosetta:

    FFMPEG_PREFIX=<arm64 FFmpeg> JAVA_HOME=<arm64 JDK 21> ./gradlew -p pc -Ptarget=macos-arm64 packageDmg
    FFMPEG_PREFIX=<x86_64 FFmpeg> JAVA_HOME=<x86_64 JDK 21> arch -x86_64 /bin/sh ./gradlew -p pc -Ptarget=macos-x64 packageDmg

`build.sh arm64` or `build.sh x64` puts the native library in `pc/build/native-arm64` or `pc/build/native-x64` with
FFmpeg's libraries, `ffmpeg`, `ffprobe` and the licence next to it (they ship inside the app and are found through
`@loader_path`). The minimum macOS is 11, so whisper is built without its BLAS back end (Accelerate's new BLAS needs
macOS 13.3). `createDistributable` signs the app ad hoc and `packageDmg` puts it in a disk image. The FFmpeg version and
configure options are in `THIRD_PARTY_NOTICES.md`.
