# Changelog

All notable changes to AbsentMotion are listed here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions use `major.minor.patch`.

Versions before 2.0.0 were never published or numbered. The entry for 1.0.0 stands for the feature set as it stood on
2026-10-12.

## [2.1.0] - 2026-10-17

### Added

- **Text to Speech.** In the Audio tab, choose *Text to Speech*, type text, pick a language and voice, set speed and
  pitch, preview it, and add it as an audio layer on the playhead. The layer is named after the first words of the
  text. Voices run offline, using the voices already installed on the device.

### Fixed

- The on-screen keyboard no longer covers the buttons of the Text to Speech sheet.

## [2.0.0] - 2026-10-17

### Added

- **Auto captions.** Generates caption layers from speech, on the device, with whisper.cpp. The speech model is
  downloaded the first time you generate captions (its size is shown first) and can be removed in Settings. Options
  for font, size, colour, outline, position and words per caption; caption layers are sized to fit any aspect ratio.
- **Bookmarks and Mark Beats.** Tap the time readout to add or remove a bookmark at the playhead. *Mark Beats* in the
  layer menu detects the beat of an audio or video layer and drops a bookmark on each beat.
- **Full-screen colour picker** with a colour wheel and RGB values, an eyedropper that samples the preview, and saved
  colours that persist.
- **Gradient tab** rebuilt, with handles on the canvas for the gradient's start and end, and gradient and image or
  video fills for text.
- Font strip: the order stays fixed while it is open, and when it opens again the most-used fonts come first.
- *Upload XML files* switch in Settings; the upload card on the home screen is hidden until it is on.
- The home screen shows *Projects*, the add button and *Templates*, in that order.

### Changed

- Text with a gradient or an image fill is drawn (it was invisible in some imported projects), together with its
  borders. A gradient is spread over the text box and an image fill is clipped to the glyphs.
- The *Border & Shadow* panel is rebuilt with a thin switch, colour swatches and aligned rows. Sliders across the app
  are thinner. Text bars on the timeline are yellow.
- The effect browser hides internal, deprecated and experimental effects and effects that do not apply to the selected
  layer type, and sorts the rest by name. Projects that use a hidden effect still render it.
- Updated to Kotlin 2.4.21 and Gradle 9.8.1; the Android build has no compiler warnings and unused code was removed.
- Release builds are made with `tools/release.sh`.

### Fixed

- Two or more layers with blend modes in one frame smeared the backdrop on some Vulkan GPUs. The renderer now copies
  the destination for blending (a patch to Skia, `tools/skia-patches/vulkan-dst-read-copy.patch`).
- Text inside groups lost its font and wrapping.
- Deleting a project could delete media that other projects still used.
- A project file that begins with a byte-order mark was rejected on import.
- Malformed imported files could produce invalid numbers; these are now guarded against.

## [1.0.0] - 2026-10-12

First complete feature set.

### Projects and library

- Home screen with the project list (grouped by date, sort by name or date, duplicate, delete, thumbnails), a
  new-project sheet (size, frame rate, background), reusable elements, templates and presets, and a settings drawer.
- Import of scene XML files and project packages from a file picker, from the share sheet and from "open with". Media
  in a package is copied into the app; media that cannot be found is shown as a grey checker and can be replaced.
  An import report lists unknown elements, effects the app cannot run, missing media and missing fonts; nothing is
  removed from the file.
- Elements: save a layer as an element, add it to other projects, edit the original, convert an instance to a group,
  unlink it, set re-timing (freeze, stretch, loop, loop and stretch, blank) with intro and outro marks.
- Presets: save the effects and keyframes of a layer, preview them, apply them to another layer in one undo step.
- Templates: replace media in a template, fit, stretch or fill, trim video, and continue to the timeline.

### Editor

- A timeline with layers for shapes, text, images, video, audio, freehand drawings, groups, null objects and cameras;
  scrubbing, zoom, trimming, splitting, reordering, hiding, multi-select, grouping, group masks and exclusions,
  clipping masks, layer parenting, label colours, snapping on the position pad, looping playback, and undo and redo
  for every edit. Copy and paste carries timing and media.
- Gestures on the preview: drag to move, corner handles to scale, side handles to stretch, two fingers to move and
  scale, tap to select; two-finger pan and zoom of the canvas; preview modes (pixel, grid, solo, camera).
- Move & Transform (position, rotation, size, skew), Colour & Fill (none, solid, swatches, gradient, image or video),
  Edit Shape, a number keypad, project settings, and animated transitions between panels.

### Keyframes and easing

- Keyframes for position, rotation, size, skew, opacity, fill colour and every shape and effect parameter; diamonds on
  the timeline; skip buttons that stop at keyframes.
- Easing: cubic Bezier, bounce, elastic, cyclic, random, steps and elastic steps, edited on a graph with handles, with
  presets, reverse, previous and next stretch, and copy and paste of a curve.

### Shapes, drawing and text

- 54 built-in shapes with editable parameters.
- Freehand drawing (pen, brush, fill, eraser), vector drawing with point editing, and convert to outline.
- Text layers with alignment, spacing, wrapping and a font browser: fonts are downloaded from Google Fonts when picked;
  you can import font files and mark favourites.
- Borders (inside, outside, centred), shadows, strokes with caps, joins and end decorations, linear, radial and sweep
  gradients, 25 blend modes and opacity.

### Media and audio

- Image and video layers with fit, fill and stretch, trim, split, speed, time remapping, thumbnails and a media info
  panel.
- Audio layers, a mixer, waveforms, volume, *Extract Audio* from a video, and an offline mix for export.

### Effects

- An effect browser and parameter panels with rulers, presets, copy and paste of effects, and *Swipe to Adjust* on the
  canvas.
- *Remove Background* for images (Google Play services ML Kit) and *Reduce Noise* for audio (RNNoise).

### Camera

- Camera layers with perspective or orthographic projection, depth placement and ordering, focus blur, fog and motion
  blur.

### Export

- MP4 video with sound (H.264, HEVC and the other codecs the device offers), animated GIF, PNG or JPEG image
  sequences, the current frame as an image, the project as XML, and the project as a package. A finished screen plays
  or shows the result and offers to share it.
- Exports run on a queue of frames so that the encoder stays busy.
