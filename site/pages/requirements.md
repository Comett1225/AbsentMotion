---
title: Requirements and versions
group: Start
order: 2
summary: Which Android versions and phones AbsentMotion runs on, and the difference between the Latest and Compatible versions.
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
- **Portrait screen.** The app is built for phones held upright. Tablets with a wide screen get a wider editor layout.

No account, Google account or internet connection is needed to use the app. Only a few optional features use the internet, and the [Privacy](@privacy) page lists them all.

## Two builds of the Latest version

The Latest version is published in two builds that differ in one thing: how Background Removal works.

| Build | Background removal | Google libraries |
|---|---|---|
| **Standard** (the file ending in `-foss.apk`, recommended) | An offline model that is part of the app. Nothing is downloaded. | None |
| **Play-services** (`-play.apk`) | Google Play services downloads its own model the first time it is needed and may send diagnostics to Google. | Yes, for this one feature |

Everything else is the same in both builds: the engine, effects, export, audio, captions and noise reduction.

## Speed

How fast things go depends on the phone's chip. Playing and editing is smooth on most phones with a mid-range chip or better. Exporting 4K video and the AI tools (captions, background removal for video) take longer on older or cheaper chips; the export screen shows an estimated time before you start, and the first-start check rates your phone as Fast, Good or Basic.

## Tested on

Phones running Android 16 and 17, and emulators from Android 13. Other phones should work if they meet the list above, but nobody can try every model.
