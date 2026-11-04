# linux

What only Linux uses: the Skia build (`skia.sh` with `skia-args.gn`), the native build (`build.sh`, using
`native/linux.cmake` and `native/glibc_compat.c`), the packages (`package.sh`), the app icon and `Dockerfile`, an Ubuntu
24.04 build machine. The engine draws with Vulkan (`../pc/native/gpu_vulkan.cpp`, shared with Windows) when a Vulkan 1.1
driver is installed, else on the CPU; decodes video through VA-API when the VA-API libraries are installed
(`../pc/native/video_copy.cpp`), else in software; and finds fallback fonts (Arabic, Hebrew, CJK) through fontconfig.

Build on Linux (arm64 or x64), or in the Docker image on a Mac (`docs/BUILD.md` section 9 has the full commands):

    linux/skia.sh
    FFMPEG_PREFIX=<unpacked BtbN linux64-gpl-shared-9.0 or linuxarm64-gpl-shared-9.0> ./gradlew -p pc test createDistributable packageDeb
    linux/package.sh 5.0.0-pre

`skia.sh` builds Skia for the running chip into `../third_party/skia/out/linux-arm64` or `out/linux-x64` (`SKIA_DIR`,
`SKIA_OUT` and `GN` change the source, output and `gn`; `SKIA_LIBS` tells `build.sh` where the libraries are). Skia uses
its own FreeType, because Debian 12's FreeType 2.12 draws no colour emoji. `build.sh` copies FFmpeg's libraries, `ffmpeg`,
`ffprobe` and the licence next to `libamdesktop.so` and sets their run path to `$ORIGIN`, so the app needs no system
FFmpeg. The library links libstdc++ statically and `glibc_compat.c` maps the few newer glibc symbols an Ubuntu 24.04
compiler picks to older ones, so a build made on Ubuntu 24.04 runs on glibc 2.35 and newer (Ubuntu 22.04, Debian 12).

`package.sh` writes `pc/build/linux-release/AbsentMotion-<version>-linux-<arm64|x86_64>.deb` and `.AppImage`. It takes the
Compose `.deb` and replaces its `Depends` line (Compose lists Ubuntu 24.04 package names that Debian 12 lacks), adds
`Recommends` for Vulkan, VA-API and `xdg-user-dirs`, lets the menu and file-type scripts fail quietly on systems without a
desktop menu, passes opened files to the app (`%F`), keeps `*.xml` out of the file types and marks `ffmpeg` and `ffprobe`
executable. The AppImage is the same app folder with the AppImage type 2 runtime in front.
