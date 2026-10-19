# Changelog

All notable changes to AbsentMotion are listed here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions use `major.minor.patch`.

Versions before 2.0.0 were never published or numbered. The entry for 1.0.0 stands for the feature set as it stood on
2026-10-12.

## [2.5.0] - 2026-10-19

### Added

- **Retime keyframes by dragging.** Touch and hold a keyframe diamond on the selected layer's bar, then drag it along the
  clip. Every keyframe at that frame moves together (transform, fill, effect settings and speed), it snaps to frames, stays
  inside the clip, and one undo step puts it back.
- **Extend or move a single clip to the playhead.** When the playhead is off the selected clip, the tool row offers extend
  and move buttons for the left or right edge, as multi-select already did.
- **Export anyway.** Exporting a project whose media is missing now asks to export without it instead of refusing.
- **New effects:** Unsharp Mask, Highlights & Shadows, Denoise and Flicker. Imported Alight Motion projects convert their
  Unsharp Mask, Brightness & Contrast 2, Denoise, Highlights & Shadows and Flicker 2 effects instead of listing them as
  effects this version cannot run.
- **Gradient Map: Scale Brightness** switch, carried over when importing.

### Changed

- **Imported Alight Motion effects match Alight much more closely**, measured layer by layer against Alight Motion: exposure,
  sharpen, colour temperature, colour tune, saturation and vibrance, brightness and contrast, the blend mode of gradient
  effects, the four-colour gradient, Light Glow and Soft Glow. Light Glow now keeps the bright parts before blurring. The
  same effects look different in existing projects where their old maths was wrong.
- Missing pictures and videos draw nothing instead of a checkerboard over the picture.
- The timeline thumbnails of a video are prepared only when their frames change, and the preview video keeps updating while
  you drag speed or trim sliders.

### Fixed

- Sharpen no longer darkens the picture.
- Editing no longer parses the whole scene twice on the main thread, and the display timing history can no longer grow
  without limit.

## [2.4.0] - 2026-10-19

### Added

- **Offline background removal in the free build.** *Remove Background* now works in the `foss` build without any Google
  libraries, using a small model (2.9 MB) that runs on the device. The `play` build still uses Google ML Kit.

### Changed

- Video thumbnails are no longer prepared for clips outside the visible timeline, theme files are read off the main
  thread, and the GIF size estimate measures real encoded samples.
- The unused *Receive from phone* controls were removed from the home screen code.

### Fixed

- **Editing.** Copying styles, layers, elements and curves keeps their media settings, transforms and identities;
  applying a preset keeps unrelated text and media properties; retiming a video keeps nested keyframes and clears stale
  speed maps; canvas gestures and selection handles no longer corrupt a layer's transform; camera rulers add up and
  effect rulers do not drop a step; wheel scrubbing settles and border colours survive the easing screens.
- **Rendering.** Warped edges, camera lifetimes, arrow widths and effects on grouped layers are corrected, media in
  nested scenes is drawn at its local time, and video colour settings and crop bounds are respected.
- **Projects and templates.** Template media and editing state are kept correctly, replacement slots in sped-up clips
  are measured correctly, package imports no longer treat XML media as projects, and photos saved with a mirrored
  orientation before 2.3.0 are corrected when the project is opened.
- **Export and audio.** Export size estimates cannot overflow and use fresh free storage on retry, AAC audio keeps its
  priming and tail samples, and audio preview players are released when a source fails to open.
- **Screens.** Menus and popups stay inside the window, favourite font headers are shown, and labels are consistent.

## [2.3.0] - 2026-10-19

### Added

- **Timeline layer reordering.** Hold an empty part of a timeline row and drag to reorder layers. Dragging near the top or
  bottom edge scrolls the timeline, and a second finger can scroll while you drag.
- **Current Frame as...** On the Export screen, next to Current Frame as PNG, save the current frame as JPEG, WebP
  (lossy or lossless) or BMP.
- **More effects and controls.** There are now 51 effects, including 11 new effects (among them an Adjustment Layer) and
  choice controls. Effect thumbnails use freely licensed photographs.
- **Projects from other editors.** Projects made in another editor now open with their effects turned into
  AbsentMotion's own effects, each editable, and effects keep working when the project is opened again.
- **Smoother exports.** On the Export screen, *Frame Interpolation* can be set to *Frame blending* or *Optical flow*. Video
  clips whose frame rate is lower than the export frame rate (for example 30 or 40 fps clips in a 60 fps export) get
  new in-between frames, and every clip is sampled at the export frame rate, so the whole video moves smoothly. Off
  leaves exports exactly as before. Optical flow can bend thin lines and object edges.

### Fixed

- **Photo orientation and media.** Photos that are mirrored by their orientation data show the right way round, and
  pictures reload when their files change.
- **Video playback.** Video frames keep coming at the end of a clip and after a seek, a video retries after a preview
  decode error instead of disappearing, and a looping clip with no end time plays through.
- **Paths and shapes.** Path commands are read correctly, closed paths keep their curves when reopened, duplicate
  closing points are dropped, the X Mark inner corners are rounded, a shape's centred border is drawn inside its layer,
  and only the visible part of the transparency checkerboard is drawn.
- **Project loading and safety.** Element links inside linked elements are followed, embedded scenes use their own frame
  rate, a failed project import no longer leaves saved projects behind, and deeply nested project files are refused
  cleanly.
- **Text and timeline.** Line spacing applies to text laid out without a prepared layout, a cancelled timeline touch
  ends without a tap or a fling, rows stay clear of the multi-select bar, and layers split into adjacent halves without
  trimming a layer to no frame.
- **Export and frames.** GIF export size estimates are more accurate, image sequence frames are named with plain digits
  in every language, and a picture reloads in place when the app resumes during an export.
- **Stability and effects.** A still whose pixels do not fit in memory fails cleanly, a font change no longer brings
  back an older scene, several effects show updated thumbnails and work correctly, layers with partial opacity blend
  correctly, and a layer's opacity is applied once when its parts overlap.

### Removed

- **Send to Mac and Wi-Fi transfer.** Both features have been removed entirely.

## [2.1.1] - 2026-10-18

### Changed

- The app can now be built from source on Linux, which F-Droid needs. Skia is built from pinned sources with `gn` built
  from source, and a GitHub Actions workflow builds the free (`foss`) APK on Debian. The app itself is unchanged.

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
