# macos

What only macOS uses: Metal for the engine (`native/gpu_metal.mm`), VideoToolbox video frames (`native/video_mac.mm`), the
sound output delay (`native/audio_mac.cpp`), the native build (`build.sh`, using `native/macos.cmake`) and the app icon.

Needs Skia built for macOS in `../third_party/skia/out/mac-v2-2-arm64` and FFmpeg's libraries (Homebrew `ffmpeg` for now).
`cd ../pc && ./gradlew createDistributable` makes `AbsentMotion.app`. `./gradlew installMac` copies it to `/Applications`
(or `-PinstallDir=<folder>`), records a start-up class archive there and signs the app. The archive is made after the copy
because Java 21 only accepts it while the app's files sit at the exact paths and dates they had when it was made; it saves
about a second of start-up work. A copy moved elsewhere still starts, just without the archive.

Intel Macs (x86_64) are built on an Apple-silicon Mac under Rosetta, with an x86_64 JDK 21 (JetBrains Runtime) and an
x86_64 FFmpeg built with `--install-name-dir=@rpath`:

    FFMPEG_PREFIX=<x86_64 FFmpeg> JAVA_HOME=<x86_64 JDK 21> arch -x86_64 /bin/sh ./gradlew -p pc -Ptarget=macos-x64 packageDmg

It needs Skia in `../third_party/skia/out/mac-x64` (same `args.gn` as arm64 with `target_cpu = "x64"`). The native library
goes to `pc/build/native-x64` with FFmpeg's libraries, `ffmpeg` and `ffprobe` next to it (they ship inside the app), and the
minimum macOS is 11. `createDistributable` signs the x64 app ad hoc, and `packageDmg` puts it in a disk image.
