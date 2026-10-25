# Changelog

All notable changes to AbsentMotion are listed here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions use `major.minor.patch`.

Versions before 2.0.0 were never published or numbered. The entry for 1.0.0 stands for the feature set as it stood on
2026-10-12.

## [Unreleased]

### Changed

- Release tooling checks APK signatures across supported Android versions, native-library packaging and alignment, and matching app versions and certificates. The signing helper uses pinned build tools and each APK's minimum Android version.
- The animated GIF decoder test generates its own ImageIO fixture instead of depending on a removed documentation image.

## [4.0.0] - 2026-10-25

### Added

- A **5-minute tutorial** in its own practice project, offered after the first start and in Settings.
- **Render preview**: when playback is choppy, the editor offers to render the project so it plays smoothly, in sync with the sound.
- **AI background removal 2.0** for pictures, an optional download of about 62 MB, and **exports that keep going in the background** with a progress notification.
- **Moving several clips at once**, and **Move to playhead** for several clips (the first press keeps their gaps, the second lines them up).
- **Smoothing updates itself** after small trims and asks after big changes.
- A **sound library** with search, sorting, albums, artists and folders, **From Files** for any folder, and **animated GIFs** as looping clips.
- **Fonts** in TTF, OTF, TTC and WOFF, **typed values** with fine-tuning and double-tap reset, **mouse and trackpad** timeline scrolling and zoom.
- **Bengali** and **Persian** (14 languages), and about 115 more **Alight Motion effects** on import, with new Noise, Raster Transform, Solid Matte, Smooth Edges and Simple Starfield effects.

### Changed

- **Exports with Motion Blur** are about twice as fast, **Glow** about ten times faster on older devices, and dragging layers in **big projects** does a third of the work.
- The app follows the phone's **font size**, Arabic and Persian read **right to left** in the editor, and **screen readers** can use the timeline.
- **Tablets** edit text in a side panel and open full screen when started sideways; **portrait exports** on older tablets are about twice as fast.
- A **stuck export** stops with a clear reason, the export check catches **black or frozen** pictures, and **time estimates** learn from each export.
- **Failed saves** keep the newest edits.

### Fixed

- **Exports freezing** on older devices with Motion Blur or Echo over heavy effects.
- **Backups made on Android 11** being damaged, **unsaved edits** lost after a crash, and **damaged projects** opening blank.
- **Smooth** losing the clip's sound, **Scatter Field** using gigabytes of memory, long **MKV and WebM** exports running out of memory, and interrupted exports left behind as broken files.
- **WAV exports over 4 GB**, **WebP lossless** on Android 9, **AV1** format details and custom **export sizes**.
- Crafted **project, SVG and font files** crashing the app, and a shared project showing another project's pictures.
- Sounds in groups cut off, **looped clips** freezing after a split, split **path followers** and **groups** losing their path or timing, and **Undo** losing a step.
- The **number keypad** with Bengali, Hindi and Thai digits, **motion tracking** with size-less parents, 3D model names with accents, and the tutorial offer covering the Import complete window.

## [3.3.2] - 2026-10-24

### Changed

- **Camera and 3D layers**: touch editing now follows the camera, and **tablets** get the wide Home layout with search.
- **AI Smooth** works on trimmed clips, and **AI Enhance** and AI Smooth keep the clip's **sound** in more formats.
- **Backup, weekly backup and the camera** ask for storage permission on Android 9, and **WebM and MKV** are greyed out on phones without an Opus encoder.
- **Stroke** works on drawings and groups, and **Thai, Lao, Khmer and Burmese** text can animate by word.

### Fixed

- **Exports with sound** failing on Android 9 and 10, and a shared file without read access crashing the app.
- **Deleting a project** removing AI results another project still used, and **version history** stopping after a wrong phone date.
- **Static**, Flicker and Glitch turning to stripes after about 20 seconds, Glitch smearing a layer's edge across the frame, doubled **glows**, and **Film Grain** sliders doing nothing.
- **Timecode** frame numbers, **Frame Rate** and **Random Jitter** timing, and Count Up/Down reading "1.000,5".
- **Outside borders** showing through see-through fills and text, and **Repeat** borders staying in place.
- **Splitting a group** breaking echo, reverb and voice effects in exports, **editing shape points** making open lines vanish, and **speed presets** on trimmed clips.
- **Looping sound** clicking in the preview, **Quick sharpen** doing nothing, **Faces** failing on 4K and rotated clips, and AI Smooth showing a frozen frame or "encoder busy".
- A stray keyframe from the **fill tab**, text layers renamed by opening a panel, the colour editor staying open after Undo, the Parent menu reopening, timeline zoom resetting, and the playhead stopping between frames.
- Hindi and Tamil word endings swallowing spaces, wrong arrow direction in Arabic, Filipino plurals without the number, a double tap starting two exports, and the GPU AI switch turning off after unrelated crashes.

## [3.3.1] - 2026-10-23

### Changed

- Cut-out **borders, strokes and shadows** follow the visible part of the picture, and tapping the **empty area around a cut-out** no longer selects it.
- **Video cut-outs** move with the clip's effects, like shake and Random Displacement.
- **AI Smooth exports** use far less memory.
- **SVG imports** are more accurate (holes, fills, gradients) and big files import faster.
- A **failed media copy** shows a message, and the **camera microphone notice** can be tapped to ask again or open Settings.
- Android 9 asks for **storage permission** before the first export, and the **update check** ignores the AI models download.

### Fixed

- A **crash** and an **Android low-memory close** when exporting with AI Smooth.
- A **crafted backup** erasing app data when restored and then deleted, and a restored backup leaving projects without their media.
- **Cut-off project files** opening as half a project, and shared projects opening web addresses by themselves.
- A cancelled export leaving video previews switched off, and leftover files from stopped exports and cancelled copies.
- **Splitting a clip with a mask** losing the mask, moved clips gaining or losing a frame, and transitions leaving a gap after a split.
- Pasting a mask on its own, Reset Position and Flip on parented or animated layers, keyframes missed on a layer's first frame, list reordering that scrolled by itself, and pinch zoom drifting.
- Motion Blur looking dark on the first frames, drawings made of single taps disappearing, and text stroke outlining the text box.
- The editor not recovering after the phone's graphics reset, sound that kept playing after a quick pause, and a rare sound crash in Reverb and Echo.
- Mark beats running out of memory on very long clips, huge SVG files crashing the app, and AI Enhance leaving half-written files or missing faces in rotated clips.

## [3.3.0] - 2026-10-22

### Added

- A Compatible version for Android 9, 10, 11 and 12, published as its own file. Features it leaves out (Optical flow
  High, AI Smooth, AI Enhance) are shown with a lock and the note "Needs Android 13 (Latest version)".
- The Frame Rate effect (formerly Time Quantization, setting Frames/sec) makes any layer move at a lower frame rate,
  video included, for a choppy or stop-motion look. Old projects open unchanged.
- Frame Interpolation has Low, Medium and High for Frame blending and Optical flow, and a help sheet that says which
  phones suit each. Medium gives the same result as before.
- A Motion Blur export option (Off, Low, Medium, High) that smears moving video like a camera shutter, also when a clip
  already has the export frame rate.
- Smooth on a video clip, in its Speed tools: the in-between frames are made once and kept with the project, so the
  preview and every export show them. The original clip can be brought back.
- AI Smooth, made on the phone, as a fourth smoothing method.
- AI Enhance for video clips.
- A Help screen in Settings with the whole guide offline, and a ? on every effect that opens its help page.
- A documentation and download website that recommends the right file for the visitor's phone, with a phone checker
  and a page for every effect.
- A clear message instead of a crash when a phone's graphics cannot run the editor.

### Changed

- Tablets: the Add menu uses the full width for videos, pictures and shapes, and in landscape the clip tools are a
  narrow column at the right edge.
- Exports on phones with a small video chip free the editor's video players first, fall back to another encoder when
  one is busy, and the result screen always shows the exported video.
- Exports below Android 13 are about a third faster, and the export time estimate accounts for heavy effects.
- AI work runs on the processor unless the graphics chip is known to handle it safely.
- Error messages are in plain words instead of codes.

### Fixed

- Old Time Quantization effects without a value showed 5 fps in the settings while playing at 8.
- The hardware video encoder of some phones refused every export.
- The too-large project file message said 32 MB while the limit is 16 MB.
- Restoring an AI-changed clip lost its media details.

## [3.2.0] - 2026-10-22

### Added

- An Audio category in Effects for clips with sound: Reverb, High Pitch, Low Pitch and Voice Filter, with ten voice presets
  (Helium, Deep, Bass Boost, Full Voice, Vocal Clarity, Echoey, Ethereal, Underwater, Cheap Mic, Telephone) that stay
  editable as one effect. The Effects button of an audio clip opens only the audio effects.
- Reduce Noise asks what to clean: a voice recording, wind and rumble, or music and mixed sound.
- AI changes (reduced noise, removed backgrounds, video cut-outs) show in the effects lists, and reduced noise and removed
  backgrounds can be undone there with one tap.
- A short note says what Undo or Redo just changed.
- Copy chosen effects and paste them onto every selected clip at once. The selection bar has an Effects button with
  Paste All Effects.
- Emoji are drawn in full color in every kind of text, and Settings > Editor > Emoji Style chooses between the phone's
  emoji and the built-in Android emoji.
- Random Displacement moves by itself: a new Speed setting moves the pattern over time, and each seed moves differently.
- Screen readers can read and set every slider and name the shutter, color swatches and media tiles. The launcher icon
  supports themed icons.

### Changed

- Runs on Android 13 and newer (was Android 14) and on phones with Vulkan 1.1 (was 1.2).
- The app is smaller: the release build leaves out code it never uses (about 70 MB down to 51 MB).
- About 60 effects are closer to Alight Motion: defaults, missing settings and behavior in the color, key, blur, noise,
  stylize, distort, text, move, repeat and 3D groups. Projects saved before keep their look.
- A clip moves only when it is held and dragged; a plain swipe across a selected clip scrubs the timeline.
- The timeline keeps its scroll position after you leave a clip, and selecting a clip centers its row.
- Google Fonts are downloaded only when you pick one; opening a project that uses them asks first.
- A video export starts at the project's own frame rate.
- 5.1 and 7.1 sound is mixed down to stereo instead of losing the center and surround channels.
- Captions for long recordings are made in parts, so they no longer run out of memory, and photos are loaded at the size
  the project needs instead of the largest size the phone allows.
- The app's English uses one spelling (US), and export estimates of an hour or more read naturally in every language.

### Fixed

- Split and duplicated clips appeared at the top of the layer list instead of directly above their source.
- Splitting or trimming the start of a group, element or template restarted its content.
- Pictures and 3D models disappeared from an export when the app was paused during it.
- Sharing or opening a file started a second copy of the app inside the other app.
- Sound kept playing when another app took over the sound or the headphones were unplugged, and trimming a clip during
  playback let its edge slide away.
- Groups with a speed or Time Quantization could show blank frames at cuts, and the video inside a quantized group ignored
  the quantization.
- A clipped layer ignored its blend mode, the motion path of a parented layer was drawn in the wrong place, and fit,
  align, distribute and snapping were wrong for parented layers.
- A video decode that timed out during export reused an earlier frame, Export anyway failed on deleted videos, a package
  export could not be cancelled in the middle of a large file, and the Done screen waited for the template copy.
- Restoring a backup could bring back projects that cannot open, and the Android cloud backup went over its 25 MB limit
  and saved nothing.
- A damaged caption model made captions fail until it was removed in Settings; it is now downloaded again.
- AbsentMotion .absproj packages were imported as templates instead of projects, and a crafted 3D model file could use
  hundreds of MB of memory.
- Many smaller fixes from a full code review.

## [3.1.1] - 2026-10-20

### Changed

- Motion Blur works like Alight Motion's: it blurs the layer along the way it really moves, from its keyframes and from
  motion effects such as Auto-Shake and Oscillate, with Tune, Position, Scale and Angle. The old fixed directional blur is
  now called Smear, and older projects keep their look.
- Random Displacement placed after a Repeat effect gives every copy its own offset, as in Alight Motion.

### Fixed

- The timeline showed the keyframes of every effect and setting of a layer together: while editing one effect, another
  effect's keyframes were shown, highlighted and moved with it, and all of an effect's settings moved as one bundle. Now
  only the keyframes of the setting you are editing, or of the open effect, are shown, highlighted, dragged and stepped
  through.
- Motion Blur did not react to shakes or any other movement.

## [3.1.0] - 2026-10-20

### Added

- Move a clip in time by holding it and sliding it left or right, or by dragging a clip that is already selected. It snaps
  to the playhead, other clips, keyframes and bookmarks, and one undo puts it back.
- Turn 3D objects with your fingers on the Rotate and Tilt tabs: one finger turns them, two fingers twist them flat. The
  Rotate tab of a 3D object shows X, Y and Z values to type and keyframe.
- The camera works like Alight Motion's: once a project has a camera, the preview shows the world around the frame and the
  camera as a box with an X, a selected camera's angle of view changes with a pinch, Camera View starts at 100 % zoom,
  camera clips are pink, and Camera Options gain a Z trackpad, a Size tab and a view cone.
- Camera Moves for a camera: Push In, Pull Out, Orbit, Pan, Dolly Zoom and Handheld, written as keyframes you can edit.
- Crash logs in Settings, each with the time and one sentence saying why, a Copy button and Delete all. After a crash the
  app says once when and why it closed.
- An optional update check, asked once: the app can look up the newest version on GitHub when it opens, and Settings has
  Check for Update. When a newer version exists, crash and error messages say it may already be fixed there.
- Alight Motion projects keep their Glow, Block Noise and Bend effects when imported.

### Changed

- Glow works like Alight Motion's: Radius, Alpha, Hardness, Color and Blending Mode, drawn as a halo around the shape that
  grows smoothly from 0. Glows in older projects are converted.
- Bend works like Alight Motion's: a positive angle bends left, the bend is one even curve and it follows the layer's
  rotation. Older projects are converted and keep bending to the same side.
- Block Noise blocks grow and shrink with the layer, and Block Dissolve's size is in pixels, both as in Alight Motion.
- Datamosh has a Start and an End in seconds instead of a Start and a Length.
- Every ruler works like a tape measure: swiping left raises the value. Move & Transform lists Size above Rotate.
- The Animate button is a round button in the bottom-right corner of the preview, the Media tab's search is a Search
  square under Camera, the Object page has icons, and the keyframe button has a new icon.
- Effects and audio effects lift and slide when you reorder them, menus and sheets animate, tiles react when pressed, and
  drags and snaps give a light vibration.
- Settings are grouped into Editor, Playback and preview, Projects and media, and Backup.
- Chroma Key and Luma Key have animated thumbnails.
- GIF and image exports are checked after saving, and the GIF and audio-only time estimates follow the real work.

### Fixed

- Opening Easing on a linear or bezier curve crashed the editor.
- Bending left with Bend cut the layer off, and Datamosh painted a picture's, shape's or text's edge colour over the whole
  canvas.
- Glow jumped to full brightness as soon as it was above 0.
- The selection box of an imported SVG or a background-removal group covered the whole canvas.
- Layers with effects were cut off at the canvas edge in the camera's world view.
- The Pulse, Wobble and Rotate loop animations ignored their size, swing and speed and used the effect's defaults.
- Effects: Glitch, Halftone, Ripple and several 3D objects could draw broken pixels; Directional Blur left a grey fringe;
  Unsharp Mask brightened flat areas; Dark Glow was too dark and Soft and Light Glow ignored the colour's transparency;
  Flicker's Mode did nothing and is gone; Pinch folded over at high strength and now follows Alight Motion's curve;
  Displacement Map's Edges did nothing with Layer Below; Radial Wipe's feather crossed the start line; Noise Displace only
  moved along a diagonal; step keyframes on Spin and the other speed motions drifted; and Move Along Path's Inset switched
  sides on S curves.
- Tall projects such as 1080 x 2400 were marked as unsupported by the phone's encoder at 1080p.
- Fixes from a code review, among them: a restored project linked to the wrong asset; replacing a template's video stopped
  it looping; a failed template media copy silently used the original file; cancelling motion tracking threw away the
  tracked frames; presets left out audio effects; new layers and clips were one frame too long; skipping to the next
  keyframe could leave the project; dragging a parented layer on the canvas moved it the wrong way; the speed keyframe
  button removed every keyframe; Save to My Assets froze the screen; a styled group lost its look when saved as an asset;
  adding a big template froze the screen; and the New Project sheet did not fit narrow phones.

## [3.0.0] - 2026-10-20

### Added

- **120 new effects (175 in total), modelled on Alight Motion's.** Move effects that change where a layer is (Auto-Shake,
  Random Displacement, Random Jitter, Oscillate, Swing, Spin, Pulse Size, Pulse Opacity, Stretch Axis, Offset, Move Along
  Path, Echo Keyframes, Time Quantization, True Motion Blur); Repeat, Linear, Radial and Grid Repeat, Repeat Along Path and
  Scatter Field; 3D objects (cube, box, cylinder, ellipsoid, torus, pyramid, prisms, raster extrude, spherize, tunnel and
  more); text animators (Text Progress, Text Transform, Text Randomizer, Count Up/Down, Timecode) with one-tap presets;
  Drawing Progress and Stroke Taper; VHS, Datamosh, Glass, Displacement Map and many pattern, blur, glow, distortion and
  colour effects. Imported Alight Motion projects keep their Move, Repeat and text effects with their settings.
- A New effects category with NEW badges, animated effect thumbnails, and parent effects that can apply to child layers.
- One-tap transitions between clips and In, Out and Loop animations for any layer, written as keyframes you can edit.
- 3D tilt (X and Y rotation) for layers, and 3D Model layers from `.obj`, `.gltf` and `.glb` files.
- Motion tracking that writes keyframes, animated mask paths for rotoscoping, and background removal for videos.
- An in-app camera for photos and videos, SVG import as editable shapes, and search in the media, audio, asset and project
  lists.
- Audio effects per clip (EQ, filters, reverb, echo, compressor, noise gate, limiter) with ten presets, and a master limiter.
- Export to MP4, MOV, MKV or WebM with H.264, HEVC, AV1, VP9 or APV where the phone has the encoder, audio settings, and
  audio-only export as WAV, FLAC, M4A, OGG or MP3.
- A new export screen: space, size, time and temperature in one stacked block, options the phone cannot make greyed out
  with the reason, a codec warning with a better choice, help for frame interpolation, and a check of every finished video and audio file.
- Project safety: background saving, version history, crash recovery, imported media kept in the project, and backup and
  restore of all projects.
- 12 languages (English, Indonesian, Hindi, Portuguese (Brazil), Spanish, Filipino, Vietnamese, Thai, Malay, Arabic,
  Turkish, Russian), a language screen and an optional device check on first start.
- Slide-to-select layers, frame-accurate snapping, speed-ramp presets and freeze frames, Full, Half and Quarter preview
  quality, a tablet layout and preview copies of heavy 4K videos.
- 50 fonts built into the app; 100 more can be downloaded.

### Changed

- The preview is faster, and the timeline and preview no longer redraw on every played frame.
- MP4 export also works on phones without the `VK_GOOGLE_display_timing` Vulkan extension, writes straight into the
  gallery, slows down instead of failing when the phone is hot, and shows an estimated time.
- The size ruler grows more slowly for big layers. The trim-limit vibration switch is now called Vibration.
- Wave Warp, RGB Split, Pixelate, Posterize, Zoom Blur, Unsharp Mask, Swirl (was Twirl), Pinch/Bulge (was Bulge), Radial
  Wipe, Vignette, Turbulence and Zoom and Spin Streaks use Alight Motion's settings and look; older projects are converted
  when they open. Wave Warp and Pinch leave see-through gaps at the layer's edges instead of stretching edge colours.

### Fixed

- Typing fast in the text box could drop letters.
- Plugging in a keyboard, changing the font size or turning on bold text closed the editor and stopped a running export.
- A keyframe or speed with an invalid number in an imported file could crash the editor.
- Duplicating a project or making a new one on a full phone crashed the app, and a double tap made two projects.
- Projects were opened and saved on the main thread; templates and presets could be left half-written.
- An imported project could link to other photos on the phone and carry them into packages exported from it.
- Auto captions could crash on processors without the newer arithmetic instructions, and the speech model is now checked
  against a known hash.
- Back left the app while the Home menu or search was open; the trim and split buttons jumped as the playhead moved; SVG
  files showed as black tiles; icon-only buttons had no TalkBack names; new media did not appear until the app was
  reopened.
- An imported project showed a blank thumbnail until it was opened once.

## [2.5.2] - 2026-10-20

### Added

- **A red grip and a light tick when a trim reaches the end of its source.** Dragging the end or start grip of a video or
  audio clip past what the file has turns the grip red and gives one very light vibration. A new switch, Trim Limit
  Vibration, in the Home settings turns the vibration off.
- A privacy policy (`PRIVACY.md`).

### Fixed

- Audio stopped a few seconds into the second half of a split clip, or into any clip that starts later than three seconds in
  its file.
- The picture vanished for a moment where one video clip meets the next. The next clip is now prepared before playback or
  scrubbing reaches it.
- Pressing Play and Pause quickly could send the playhead back in time instead of stopping where it landed.
- Effects, borders and shadows on a group are now drawn; they were saved but never shown.
- Invert no longer paints white outside a layer, and RGB Split no longer changes translucent colours at distance 0.
- Removing a parent link, deleting a parent, grouping across a parent, copying or saving a preset now keeps the whole
  animation of the child in place, not just the frame under the playhead.
- Save to My Assets keeps the rotation, animation and parent chain of the layer or group.
- Deleting a project no longer removes imported media that another project or template shares when that other file cannot be
  read at that moment.
- Missing media is relinked only when exactly one file has the same name and size, instead of the first match.
- Replacing a trimmed text video fill with a shorter clip keeps a valid trim.
- Four Android lint errors are fixed.

## [2.5.1] - 2026-10-19

### Changed

- **Android Vulkan 1.2 is stated everywhere.** The manifest, README and store text said Vulkan 1.1 while the engine has always
  needed 1.2; the manifest feature now asks for 1.2, so a phone that only offers 1.1 is no longer listed as compatible.
- GIFs from a transparent project or an element are flattened onto the background chosen in the export options (white by
  default) instead of coming out on black.
- A failed save now shows a message once instead of failing silently, and the project is saved again at the next edit.
- Deleting a project also deletes the AI results (cut-outs, noise reduction, voices) that no project or template uses.
- The projects list reuses the details of projects that did not change, and Home keeps at most 64 decoded thumbnails.

### Fixed

- Coming back from the picker, the share sheet or another app no longer reloads every picture and blanks every preview video.
- A picture that failed to decode is tried again, and a failed video frame keeps the last good frame on screen.
- A project with a frame rate of 0 no longer crashes when opened; imports with absurd sizes or frame rates are refused, and
  imported projects with duplicate or missing layer ids get new ids instead of editing the wrong layer.
- Extending or trimming a clip from the Speed panel keeps a speed ramp instead of flattening it to one speed, and a very
  long layer at the slowest speed no longer overflows its end time.
- Extract Audio from a muted video no longer produces a silent audio layer.
- Replacing a template layer shifts its speed-ramp keys and turns keyframed rotation with the new picture.
- The MP4 size estimate counts video and nested audio, and the missing-media check no longer runs on the main thread.
- Text to speech gives up when the system voice never answers, and the eyedropper no longer crashes when the preview closes.
- The camera ruler fields edit the current layer instead of an older copy of it.
- Several storage errors (creating a project from a template, saving an asset, renaming or deleting a template or preset)
  show a message instead of crashing, and template and preset delete and rename no longer block the screen.
- The edited scene is serialised once per edit instead of twice.

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
