<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" alt="AbsentMotion" width="128" height="128">
</p>

# AbsentMotion

AbsentMotion is a free, open-source alternative to CapCut and Alight Motion: a motion graphics, animation and video
editor for Android phones. Everything runs on your phone and it works without an internet connection. There is no
subscription, no account and no advertising, and your projects never leave your device.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="The editor: a photo with a title on the timeline, keyframes shown as diamonds" width="280">
  &nbsp;&nbsp;
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Effect parameters on a layer" width="280">
</p>
<p align="center"><sub>Photo: Ruby Mountain reflected in Diablo Lake, public domain (CC0), Wikimedia Commons.</sub></p>

## What it does

- **Layers and a timeline.** Shapes, text, images, video, audio, freehand drawings, groups, reusable elements and
  null objects, with trimming, splitting, parenting, clipping masks, label colours and bookmarks.
- **Keyframes with easing.** Any value (position, size, rotation, skew, opacity, colour, shape and effect
  parameters) can be keyframed. Easing is a cubic Bezier, bounce, elastic, cyclic, random or stepped curve, edited
  with handles on a graph.
- **Shapes and drawing.** Parametric shapes with editable parameters, vector paths with point editing, freehand pen,
  brush, fill and eraser, borders, shadows, gradients and blend modes.
- **Text.** Text layers with spacing and alignment, and any font from Google Fonts, downloaded when you pick it.
- **Effects.** Per-layer effects with keyframeable parameters. The built-in set is small at first (see "Limits").
- **Camera layers.** A camera with perspective or orthographic projection, depth ordering, focus blur, fog and motion
  blur.
- **Audio.** Audio layers and the sound of video clips, a waveform view, volume, an offline mixer, beat detection
  (Mark Beats) and noise reduction.
- **Offline text to speech.** Turns text into an audio layer with the voices already installed on the device.
- **Auto captions.** On-device speech recognition (whisper.cpp) turns speech into caption layers. The speech model is
  downloaded the first time you use the feature, after the app tells you its size.
- **Export.** MP4 video (H.264 or HEVC, where the device has an encoder), animated GIF, PNG or JPEG image sequence,
  single frames, and project files.

## Requirements

Android 14 (API level 34) or newer (tested on Android 16 and 17), a 64-bit ARM device (`arm64-v8a`), Vulkan 1.2.

There are two builds: `play`, which has background removal on images through Google Play services, and `foss` (for
F-Droid), which contains no Google libraries at all.

## Limits

AbsentMotion is a young project. Please know these before you rely on it:

- **The effects pack is small at first.** The app ships with a limited set of effects. A project that uses an effect
  AbsentMotion does not have still opens, and the effect stays in the file, but it is not drawn.
- **Only 64-bit ARM Android devices on Android 14 or newer, with Vulkan 1.2.** Tested on Android 16 and 17.
- **Background removal.** The `foss` build removes backgrounds with its own offline model. The `play` build uses
  Google Play services instead, a proprietary Google library (listed in `THIRD_PARTY_NOTICES.md`) that downloads its
  model and may send diagnostics to Google, as it does for any app.
- **Network use.** Nothing is needed online. The internet is only used when you choose an extra downloadable font and
  for the speech model the first time you generate captions (the `play` build's Play services library aside, see above).

## File compatibility

AbsentMotion can open scene XML files and `.amproj` packages exported from Alight Motion, and it exports scene XML in the same format. Effects that AbsentMotion does not include are kept in the file and are not drawn. AbsentMotion saves its own packages as `.absproj`. AbsentMotion is an independent project. It is not affiliated with, endorsed by or sponsored by Alight Creative, Alight Motion, ByteDance or CapCut. Alight Motion and CapCut are trademarks of their owners.

## Building

The app is built with Gradle (`app/`) and has a C++ engine on top of Skia, which is built from source. See
[`docs/BUILD.md`](docs/BUILD.md).

## Contributing and security

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request. To report a security problem, see
[`SECURITY.md`](SECURITY.md).

## Licence

AbsentMotion is released under the [Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for the third-party software it uses (Skia, whisper.cpp, RNNoise,
AndroidX Compose and others) and their licences.
