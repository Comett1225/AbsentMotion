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

## setup

`setup/` is AbsentMotion's own installer and updater, with its own window instead of the Windows Installer wizard.
`setup/build.ps1` builds `AbsentMotionSetup.exe` (a small native program drawn with Direct2D) and `ampack.exe`, and packs the
app image from `gradlew createDistributable` into `pc/build/compose/binaries/main/setup/`:

- `AbsentMotion-<version>-windows-x64-setup.exe`: one file to download and run. It installs for the current Windows account
  into `%LOCALAPPDATA%\Programs\AbsentMotion` without prompts or admin rights, adds Start menu and desktop shortcuts and an
  Apps entry with an uninstaller, then opens the app.
- `AbsentMotion-<version>-windows-x64.ampack`: the same package without the program, for in-app updates. An app that finds
  `AbsentMotionSetup.exe` next to its launcher downloads the signed `.ampack` and runs a copy of its own setup program with no
  further clicks. The setup unpacks next to the app, closes every program running from the app folder (a polite close, then
  ended after 5 s), swaps the two folders and opens the new version. If anything fails, the old version stays and opens again.

`gradlew packageMsi` builds the setup program first and ships it next to `AbsentMotion.exe` (jpackage `--app-content`), so an
MSI install updates through the setup from its next update on. That first setup update moves the install off Windows
Installer: it moves the old folder aside, removes the MSI product with `msiexec /x` (passing `INSTALLDIR` for that folder and
`MSIRESTARTMANAGERCONTROL=Disable`, so nothing outside it is touched or closed), puts the new version in place and adds its
own Apps entry, Start menu shortcut and `.absproj`/`.amproj` file types. `gradlew packageAmpack` writes the `.ampack` and the
one-file setup into `pc/build/compose/binaries/main/ampack/`.

During an update the setup appends to the `--result` file: `started`, then `stage verify`, `stage close`, `stage msi`,
`stage swap` and `stage register`, and last `ok <version>` or `fail <reason>`. The app reads the last line on its next start
and explains a failure. The package uses the LZMS compression and SHA-256 built into Windows, so no third-party code is
added. Command line: `--dir`, `--name`, `--no-launch`, `--quiet`, `--log`, `--uninstall`,
`--update --payload <file> --wait-pid <pid> --result <file>` and `--probe`. `build.ps1 -StubOnly` builds only the setup
program; `-UpgradeCode` and `-DefaultName` make a test build that never matches a real install. With Smart App Control on,
Windows refuses unsigned programs, so sign the setup before relying on it there.
