# macos

What only macOS uses: Metal for the engine (`native/gpu_metal.mm`), VideoToolbox video frames for the FFmpeg preview decoder
(`native/video_mac.mm`), FFamp (`native/video_ffamp.mm` for its preview decoder, `native/media_ffamp.cpp` for describing
files, sound and frames; FFamp is the submodule `../third_party/FFamp`), the sound output delay (`native/audio_mac.cpp`), the
native build (`build.sh`, using `native/macos.cmake`) and the app icon.

The macOS app reads media with FFmpeg by default: the native library links FFmpeg's libraries for the preview decoder and
the app runs the bundled `ffmpeg` and `ffprobe` for the rest, as on Windows and Linux. FFamp is built in as well and is used
from the next start when Settings > Playback > Media reader is set to FFamp (experimental). Then reading, playing, export,
the tools, the camera and the video downloader go through FFamp (`MediaEngine` in
`pc/src/standins/kotlin/app/absentmotion/media`), and a file FFamp cannot open or describe is read with FFmpeg instead. Tests
pick the reader with `-PmediaReader=ffamp` (default `standard`). FFamp uses libopus, LAME, libvorbis, libogg, OpenAPV,
libvpx, dav1d, SVT-AV1 and Xvid (and mpg123 when LAME is built with its decoder) when CMake finds them; Xvid plays DivX and
Xvid video with B-frames. `build.sh arm64` and `build.sh x64` stop when the codec folder has no Xvid.

Needs Skia built for macOS in `../third_party/skia/out/mac-v2-2-arm64` (Apple silicon) or `out/mac-x64` (Intel, same
`args.gn` with `target_cpu = "x64"`). `cd ../pc && ./gradlew createDistributable` makes `AbsentMotion.app` for this Mac
against Homebrew's `ffmpeg` and the codec libraries Homebrew has installed, so that copy only runs where those are
installed. `./gradlew installMac` copies it to `/Applications` (or `-PinstallDir=<folder>`), records a start-up class archive
there and signs the app. The archive is made after the copy because Java 21 only accepts it while the app's files sit at the
exact paths and dates they had when it was made; it saves about a second of start-up work. A copy moved elsewhere still
starts, just without the archive.

Release builds carry their own FFmpeg, codec libraries and Java, so they run on a Mac without Homebrew. Each chip needs an
FFmpeg built for it with `--install-name-dir=@rpath` and its licence text as `ffmpeg-LICENSE.txt` in the prefix, and FFamp's
codec libraries built once per chip from their pinned source archives (checksums are checked; needs CMake, Ninja, Meson and
NASM):

    ../third_party/FFamp/third_party/build_mac.sh arm64 <folder for arm64>
    ../third_party/FFamp/third_party/build_mac.sh x86_64 <folder for x86_64>

Those are static, for macOS 11 and later. The app ships the JDK that runs Gradle (`JAVA_HOME`), which must be a JDK 21 for
that chip that does not link Homebrew libraries (JetBrains Runtime; Homebrew's `openjdk` links Homebrew's freetype, harfbuzz
and others), so use the root `gradlew` (`pc/gradlew` picks the Mac's JDK 21). Intel Macs are built on an Apple-silicon Mac
under Rosetta:

    FFMPEG_PREFIX=<arm64 FFmpeg> FFAMP_DEPS=<folder for arm64> JAVA_HOME=<arm64 JDK 21> ./gradlew -p pc -Ptarget=macos-arm64 packageDmg
    FFMPEG_PREFIX=<x86_64 FFmpeg> FFAMP_DEPS=<folder for x86_64> JAVA_HOME=<x86_64 JDK 21> arch -x86_64 /bin/sh ./gradlew -p pc -Ptarget=macos-x64 packageDmg

`build.sh arm64` or `build.sh x64` puts the native library in `pc/build/native-arm64` or `pc/build/native-x64` with
FFmpeg's libraries, `ffmpeg`, `ffprobe` and the licence next to it (they ship inside the app and are found through
`@loader_path`). It ignores Homebrew and stops if the library links anything outside the system and those FFmpeg libraries.
The minimum macOS is 11, so whisper is built without its BLAS back end (Accelerate's new BLAS needs macOS 13.3).
`createDistributable` signs the app ad hoc and `packageDmg` puts it in a disk image. `bundle_check.sh <AbsentMotion.app>`
checks that the app carries FFmpeg and FFamp, then runs the media tests with FFamp chosen against that build's native
library; those tests point `ffmpeg` and `ffprobe` at a missing file, so FFamp has to work without them. The library
versions and FFmpeg's configure options are in `THIRD_PARTY_NOTICES.md`.
