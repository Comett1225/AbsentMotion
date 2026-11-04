# windows

What only Windows uses: the native build (`build.ps1`, using `native/windows.cmake`), a compatibility header for MSVC
(`native/compat`) and the app icon (`AbsentMotion.ico`, made from the Android store icon). The engine draws with Vulkan
(`../pc/native/gpu_vulkan.cpp`, shared with Linux).

Needs Visual Studio Build Tools with clang-cl (C and C++ both go through clang-cl) and Skia built for Windows (`SKIA_DIR`,
`out/win-x64`). `build.ps1` copies Skia's `icudtl.dat` next to `amdesktop.dll`; Skia's ICU loader looks there after the
program folder, so text shaping works in the app, the tests and `gradlew run`.

Set `FFMPEG_DIR` to an FFmpeg shared build with development files (`include`, `lib` and `bin`; the GPL build
`win64-gpl-shared` from BtbN/FFmpeg-Builds, which has x264, x265, libvpx, dav1d, Opus and SVT-AV1 and nothing nonfree,
is the one the installer is made with). The app then decodes video itself, on
the GPU through D3D11 when it can (`../pc/native/video_copy.cpp`), and `build.ps1` copies the FFmpeg DLLs, `ffmpeg.exe`,
`ffprobe.exe` and FFmpeg's licence next to `amdesktop.dll`. The app loads those DLLs from its own folder before the engine,
and the installer ships them, so it needs no FFmpeg on `PATH`. Without `FFMPEG_DIR` the engine is built without its decoder
and the app runs the `ffmpeg` program on `PATH` for every frame instead, which is much slower.

`cd ..\pc`, then `gradlew createDistributable` builds the app and `gradlew packageMsi` the per-user installer (Compose
downloads WiX for it).
