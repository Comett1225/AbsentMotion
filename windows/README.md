# windows

What only Windows uses: the native build (`build.ps1`, using `native/windows.cmake`) and a compatibility header for MSVC
(`native/compat`). The engine draws with Vulkan (`../pc/native/gpu_vulkan.cpp`, shared with Linux).

Needs Visual Studio Build Tools with clang-cl and Skia built for Windows (`SKIA_DIR`, `out/win-x64`). With FFmpeg development
files (`FFMPEG_DIR`) the app decodes video itself, on the GPU through D3D11 when it can (`../pc/native/video_copy.cpp`); without
them it uses the `ffmpeg` program. `cd ..\pc` and `gradlew createDistributable` builds the app.
