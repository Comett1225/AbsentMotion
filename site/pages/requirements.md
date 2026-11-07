---
title: Requirements and versions
group: Start
order: 2
summary: Which Android versions, phones and Chromebooks AbsentMotion runs on, the difference between the Latest and Compatible versions, and the version for computers.
---
## Two versions

AbsentMotion comes in two versions so that more phones can use it.

| Version | Android | Notes |
|---|---|---|
| **Latest** | 13 or newer | Every feature. |
| **Compatible** | 8 to 12 | Some features are left out. See [the Compatible version](@compatible). |

Android 7 and older cannot run AbsentMotion. To find your Android version, open Settings, About phone, Android version.

::: site
Not sure whether your phone works? Type its name in the [phone checker](@phone-checker).
:::

## What the phone needs

- **An ARM chip.** Usually 64-bit (also called arm64): nearly every phone and tablet launched in the last several years has one. Cheap phones sold as Android Go edition often run a 32-bit system: they need the **Compatible 32-bit** file, on Android 8 or newer.
- **Vulkan 1.1 or OpenGL ES 3.0.** AbsentMotion draws the preview and the export with the phone's graphics chip, through Vulkan 1.1 when the phone has it and through OpenGL ES 3.0 when it does not. With OpenGL the preview is slower, and **AI Smooth**, **AI Enhance**, **AI background removal 2.0** and **Optical flow High** are left out. **Settings**, **Graphics** switches between the two.
- **Memory.** We recommend 4 GB of RAM or more. Phones with less can work for small 1080p projects, but Android may close the app when a project has 4K video or many heavy effects. If the app closes, see [Troubleshooting](@troubleshooting).
- **Free storage** for your projects, the media they use and your exports. The export screen checks the free space before it starts.
- **Portrait screen.** The app is built for phones held upright. Tablets get a layout made for big screens, in portrait and in landscape: see [The editor screen](@editor).

No account, Google account or internet connection is needed to use the app. Only a few optional features use the internet, and the [Privacy](@privacy) page lists them all.

## Chromebooks

AbsentMotion runs on Chromebooks that can install Android apps.

- Chromebooks with an ARM chip use the same files as phones.
- Chromebooks with an **Intel or AMD** chip have their own files, built for x86_64: **Latest, Intel or AMD** (`-foss-x86_64.apk`, Android 13 or newer) and **Compatible, Intel or AMD** (`-compatible-x86_64.apk`, Android 9 to 12). They are in the list of all downloads on the [download page](@download) and on the [releases page](@releases).
- If you installed the phone file on an Intel or AMD Chromebook, install the Intel or AMD file over it. Your projects stay.
- **Check for Update** in [Settings](@settings) downloads the Intel or AMD file by itself on these Chromebooks.
- The **Video Downloader** does not work in the Intel or AMD files.
- On a Chromebook the app opens in a large window.

## Speed

How fast things go depends on the phone's chip. Playing and editing is smooth on most phones with a mid-range chip or better. Exporting 4K video and the AI tools (captions, background removal for video) take longer on older or cheaper chips; the export screen shows an estimated time before you start, and the first-start check rates your phone as Fast, Good or Basic.

## Tested on

Phones running Android 16 and 17, and emulators from Android 13. Other phones should work if they meet the list above, but nobody can try every model.

## AbsentMotion on your computer

From version 5.0 there is also a version for computers. It is the same app as on the phone, with the same effects, projects and export, laid out for a mouse and a keyboard. The phone app still comes first.

- **Mac:** computers with Apple silicon (M1 or newer) or an Intel chip, with macOS 11 or newer. From the [releases page](@releases), download the Mac file for your chip: `-macos-arm64.dmg` for Apple silicon or `-macos-x64.dmg` for Intel. The first time, macOS says it cannot check the app: click **Done**, then open **System Settings** > **Privacy & Security** and click **Open Anyway**.
- **Linux:** 64-bit computers with an ARM (`arm64`) or Intel or AMD (`x86_64`) chip, running Ubuntu 22.04, Debian 12 or newer, or another Linux with glibc 2.35 or newer. From the [releases page](@releases), download the `.deb` package, which adds AbsentMotion to the app menu of Ubuntu, Debian and similar systems, the `.AppImage`, which runs without installing: mark it as executable and open it, or the `.tar.gz`, which installs for your user on any distribution (Arch, Fedora, openSUSE and others): unpack it and run `install.sh`. Pick the file with `arm64` or `x86_64` in its name. The [README](@repo) has step-by-step install notes for each kind of Linux.
- **Windows:** 64-bit Windows 10 or 11 on an Intel or AMD chip. From the [releases page](@releases), download the installer (`-windows-x64.msi`). It installs for your user and adds AbsentMotion to the Start menu and the desktop. If Windows says it protected your PC, click **More info**, then **Run anyway**.

On Linux, AbsentMotion draws with the graphics chip through Vulkan. Without a Vulkan driver (on Ubuntu and Debian, the package `mesa-vulkan-drivers`) it draws with the processor, which is slower, and frame blending, optical-flow and motion-blur smoothing, and Datamosh do not work.

What is different on a computer:

- The library with your media, sounds and objects is on the left, the preview in the middle and the inspector on the right, which edits the selected layer. The timeline below gives every layer its own row.
- Drag files from your file manager into the window, or link a folder so its media shows in the library. Drag a tile from the library onto the timeline or the preview to add it.
- Drag a layer in the preview to move it and drag a corner to resize it. Hold Alt and scroll to scale it; add Shift to rotate it. Drag with the middle mouse button to move the view of the preview or the timeline.
- Right-click a clip on the timeline for a menu with cut, copy, paste, duplicate, split, delete, group and more. Double-click a group to open it.
- Keyboard shortcuts use Cmd on a Mac (Ctrl on Windows and Linux): Space plays and pauses, the arrow keys step one frame (ten with Shift), Cmd+Z undoes and Cmd+Shift+Z redoes, Cmd+C, Cmd+X and Cmd+V copy, cut and paste layers, Cmd+D duplicates, Cmd+B splits at the playhead, Option+Left and Option+Right (Alt on Windows and Linux) jump to the previous or next bookmark and then to the start or end, the comma and period keys nudge the selected clips by one frame (ten with Shift), Delete removes the selected layers and Cmd+E opens the export.
- The bar at the top is part of the app, with the system's own window buttons (on the left on a Mac, on the right on Windows and Linux). The **☰** button next to the exit button opens the File, Edit, View, Playback and Help menus. If the bar causes trouble, **Settings** > **Editor** > **Use system title bar** brings back the normal one.
- Each project shows only the media you picked or linked in it. Right-click a folder in the library and choose **Pin to all projects** to see it in every project. A search lists the folder first and then the matching files in it.
- With nothing selected, the inspector shows the project: its name, size, frame rate, background, length, earlier versions and the list of layers. A level meter for the whole project sits right of the buttons on the left.
- The **?** on an effect opens its help in a small window inside the app; close it with the X or Esc.
- Taking a photo or a video uses the computer's webcam.
- The Video Downloader is not on the computer yet.

To move a project between your phone and your computer, export it as a **Project Bundle** (`.absproj`) and open the file on the other device. See [Projects, backup and import](@projects).
