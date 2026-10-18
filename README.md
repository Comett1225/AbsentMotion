# AbsentMotion

AbsentMotion is a free, open-source motion graphics and animation editor for Android phones. There is no
subscription, no account and no advertising. Projects live on your device.

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

Android 14 (API level 34) or newer (only tested on Android 17 so far), a 64-bit ARM device (`arm64-v8a`), Vulkan 1.1.

There are two builds: `play`, which has background removal on images through Google Play services, and `foss` (for
F-Droid), which contains no Google libraries at all.

## Limits

AbsentMotion is a young project. Please know these before you rely on it:

- **The effects pack is small at first.** The app ships with a limited set of effects. A project that uses an effect
  AbsentMotion does not have still opens, and the effect stays in the file, but it is not drawn.
- **Only 64-bit ARM Android devices on Android 14 or newer (tested so far only on Android 17).** Older Android
  versions are not supported, and the app needs Vulkan 1.1.
- **Release signing.** Release APKs are signed with the project's own key. Its SHA-256 certificate fingerprint is
  `5F:AF:34:79:2B:68:1E:88:06:A1:43:E8:A7:1C:3C:C5:50:B4:E5:ED:D3:88:BA:29:2A:46:DC:09:CD:B7:E9:64`; compare it
  with `apksigner verify --print-certs` before you install a file from anywhere other than the project's release page.
- **Background removal uses Google Play services, in the `play` build only.** It is a proprietary Google library
  (listed in `THIRD_PARTY_NOTICES.md`): Play services downloads its model and may send diagnostics to Google, as it does
  for any app. The `foss` build has no background removal.
- **Network use.** The app downloads a font from Google's font servers when you choose it, and the speech model
  when you first generate captions. The app itself does nothing else over the Internet (the `play` build's Play
  services library aside, see above).

## File compatibility

AbsentMotion can open scene XML files and `.amproj` packages exported from Alight Motion, and it exports scene XML in the same format. Effects that AbsentMotion does not include are kept in the file and are not drawn. AbsentMotion saves its own packages as `.absproj`. AbsentMotion is an independent project. It is not affiliated with, endorsed by or sponsored by Alight Creative or Alight Motion. Alight Motion is a trademark of its owner.

## Building

The app is built with Gradle (`app/`) and has a C++ engine on top of Skia. Building Skia from source is the slow
part (minutes on a fast machine, several gigabytes of disk). The full guide, from a fresh machine, is in
[`docs/BUILD.md`](docs/BUILD.md).

## Contributing and security

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request. To report a security problem, see
[`SECURITY.md`](SECURITY.md).

## Licence

AbsentMotion is released under the [Apache License 2.0](LICENSE). See [`NOTICE`](NOTICE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for the third-party software it uses (Skia, whisper.cpp, RNNoise,
AndroidX Compose and others) and their licences.
