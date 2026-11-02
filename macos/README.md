# macos

What only macOS uses: Metal for the engine (`native/gpu_metal.mm`), VideoToolbox video frames (`native/video_mac.mm`), the
sound output delay (`native/audio_mac.cpp`), the native build (`build.sh`, using `native/macos.cmake`) and the app icon.

Needs Skia built for macOS in `../third_party/skia/out/mac-v2-2-arm64` and FFmpeg's libraries (Homebrew `ffmpeg` for now).
`cd ../pc && ./gradlew createDistributable` makes `AbsentMotion.app`.
