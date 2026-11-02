# linux

What only Linux uses: the native build (`build.sh`, using `native/linux.cmake`). The engine draws with Vulkan
(`../pc/native/gpu_vulkan.cpp`, shared with Windows) and decodes video through VA-API when available (`../pc/native/video_copy.cpp`), else in software.

Not built yet. It needs Skia built for Linux in `../third_party/skia/out/linux-x64` (`../tools/build_skia.sh` supports Linux),
pkg-config with FFmpeg, fontconfig and freetype, and a JDK 21.
