# linux

What only Linux uses: the Skia build (`skia.sh` with `skia-args.gn`), the native build (`build.sh`, using
`native/linux.cmake` and `native/glibc_compat.c`), the packages (`package.sh`, with `start.sh` and `install.sh`), the
distribution check (`distro_check.sh`), the app icon and `Dockerfile`, an Ubuntu 24.04 build machine. The engine draws with Vulkan (`../pc/native/gpu_vulkan.cpp`, shared with Windows) when a Vulkan 1.1
driver is installed (a graphics card before a software Vulkan driver such as lavapipe), else on the CPU; decodes video through VA-API when the VA-API libraries are installed
(`../pc/native/video_copy.cpp`), else in software; and finds fallback fonts (Arabic, Hebrew, CJK) through fontconfig.

Build on Linux (arm64 or x64), or in the Docker image on a Mac (`docs/BUILD.md` section 9 has the full commands):

    linux/skia.sh
    FFMPEG_PREFIX=<unpacked BtbN linux64-gpl-shared-9.0 or linuxarm64-gpl-shared-9.0> ./gradlew -p pc test createDistributable packageDeb
    linux/package.sh 5.0.0

`skia.sh` builds Skia for the running chip into `../third_party/skia/out/linux-arm64` or `out/linux-x64` (`SKIA_DIR`,
`SKIA_OUT` and `GN` change the source, output and `gn`; `SKIA_LIBS` tells `build.sh` where the libraries are). Skia uses
its own FreeType, because Debian 12's FreeType 2.12 draws no colour emoji. `build.sh` copies FFmpeg's libraries, `ffmpeg`,
`ffprobe` and the licence next to `libamdesktop.so` and sets their run path to `$ORIGIN`, so the app needs no system
FFmpeg. The library links libstdc++ statically and `glibc_compat.c` maps the few newer glibc symbols an Ubuntu 24.04
compiler picks to older ones, so a build made on Ubuntu 24.04 runs on glibc 2.35 and newer (Ubuntu 22.04, Debian 12).

`package.sh` writes `pc/build/linux-release/AbsentMotion-<version>-linux-<arm64|x86_64>.deb`, `.AppImage` and `.tar.gz`. It takes the
Compose `.deb` and replaces its `Depends` line (Compose lists Ubuntu 24.04 package names that Debian 12 lacks), adds
`Recommends` for Vulkan, VA-API and `xdg-user-dirs`, lets the menu and file-type scripts fail quietly on systems without a
desktop menu, passes opened files to the app (`%F`), keeps `*.xml` out of the file types and marks `ffmpeg` and `ffprobe`
executable. The AppImage is the same app folder with the AppImage type 2 runtime in front. The `.tar.gz` is the app folder
`AbsentMotion/` with `install.sh`, which installs for the user without root: the app to `~/.local/opt/absentmotion`, the
menu entry, icon and `.absproj` file type to `~/.local/share` (`XDG_DATA_HOME`), and an `absentmotion` command in
`~/.local/bin`; `install.sh --uninstall` removes all of it and keeps the projects.

All three start the app through `bin/absentmotion.sh` (`start.sh`), which sets up what the Java launcher cannot: a
per-user start-up class archive in `~/.cache/absentmotion` (named after the app's jar files, so an update makes a new
one; not inside an AppImage, whose folder moves on every start), `-Dsun.java2d.uiScale` from `Xft.dpi` when `GDK_SCALE` is
not set, `LC_CTYPE=C.UTF-8` when the locale is not UTF-8 (so file names with any characters work), and
`_JAVA_AWT_WM_NONREPARENTING=1` on tiling window managers. The base class archive for the bundled Java runtime is made when
the app folder is built (`createDistributable`, `pc/build.gradle.kts`).

Video export picks FFmpeg's VA-API encoders (`h264_vaapi`, `hevc_vaapi`, `av1_vaapi`, through the first
`/dev/dri/renderD*` or `AM_VAAPI_DEVICE`) after NVENC, QSV and AMF and before the software encoders; each encoder is tried
on a short test clip first, so one that does not work is skipped. `AM_SOFTWARE_RENDER=1` makes the engine draw on the CPU
even when a Vulkan driver is present.

`distro_check.sh [AbsentMotion-<version>-linux-<arch>.tar.gz] [distro ...]` unpacks the `.tar.gz` in Docker containers
of Ubuntu 22.04 and 24.04, Debian 12, Linux Mint 22 (its Ubuntu 24.04 base on arm64), Fedora, Arch (x86_64 only) and
openSUSE Tumbleweed, installs only the libraries the main README lists (plus Xvfb and a font for the check), runs
`install.sh`, renders three test scenes without a display, encodes 60 frames with the bundled FFmpeg the way the app
does, starts the app on a virtual display (`--warmup`: opens a project, adds a shape and text, opens the effects and the
export screen), uninstalls, and prints PASS or FAIL per distribution.
