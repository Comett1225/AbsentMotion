---
title: Photos, video and speed
group: Create
order: 5
summary: Add photos and videos, change speed, make speed ramps and freeze frames, and keep big videos smooth.
---
## Add photos and videos

Open the **Media** tab of the [Add menu](@add-menu) and tap a picture or video. It is added at the playhead and sized to fit the canvas, with the file's rotation respected. A video lasts as long as the file. You can also use **Camera** in the same tab to shoot something new.

Photos and videos are rectangles with a picture fill, so all shape tools work on them: **Transform**, **Frame & Shadow**, **Opacity & Blend Mode**, effects and so on.

By default the app keeps a copy of added media inside the project (the setting **Keep a copy of imported media in the project**). Files over 32 MB show "Copying ... into the project..." while the copy is made. If there is not enough free space the project keeps using the original file instead.

To fit a picture to the canvas, use the layer menu: **Fit to Canvas** shows the whole picture, **Fill the Canvas** covers the canvas and crops the edges, **Stretch to Canvas** distorts it to fit exactly. See [Layer menus and multi-select](@layer-menus).

### Media Info and swapping a file

**Media Info** in the layer menu shows the file's name, dimensions, frame rate, duration, format and size. **Swap media file** opens the photo picker and replaces that file in every layer of the project that uses it. This is how to fix a layer whose file went missing.

## Speed

Select a video or sound clip and tap **Speed** (the first small button of the tool grid). The **Time Remapping** panel opens.

- The four buttons at the top set the start or the end of the clip to the playhead **without** trimming the video: the speed changes so that the same part of the video fits the new length. They are: extend the start, trim the start, trim the end and extend the end.
- A read-out ("1.00x") and a slider between a turtle and a rabbit set the speed at the playhead, from 0.001x to 4x. The slider clicks at 0.25x, 0.5x, 0.75x, 1x, 2x and 3x.
- With no speed keyframes, the slider sets one speed for the whole clip.
- Add keyframes and use **Easing** to change the speed over time: this is a speed ramp.

### Speed ramp presets

The chips under the slider spread a ramp over the whole clip.

| Preset | What it does |
|---|---|
| **Montage** | 1x, 2.5x, 0.6x, 2.5x, 1x |
| **Hero** and **Bullet** | Slow down in the middle |
| **Jump Cut** | Spikes to 4x in the middle |
| **Flash In** | Starts at 4x and eases to 1x |
| **Flash Out** | Ends at 4x |

**Freeze Frame** (when the playhead is inside the clip) asks for the **Seconds** to hold (0.1 to 30) and holds the picture at the playhead. **Remove Keyframes** returns the clip to a constant 1x after a confirmation.

The sound of a clip follows its speed, so its pitch changes with it.

## Big videos and the preview

A video larger than 1920 x 1080, or one with more than two seconds between its internal key pictures, is slow to scrub. By default AbsentMotion makes a smaller copy in the background (a thin line along the bar shows the progress) and uses it for the preview and the thumbnails. Exports always read the original file. You can turn this off or clear the copies in [Settings](@settings).

## Take the sound out of a video

**Extract Audio** in the layer menu makes an audio layer from the video's sound and mutes the video. The new layer keeps the video's timing, speed, volume and audio effects. See [Audio](@audio).

## Remove the background

The **AI** tools can cut the background from a photo or a video and reduce noise in a recording. They run on your phone. See [AI tools](@ai-tools).

## Smoother motion when you export

If your video clips have a lower frame rate than your export, the export screen can add in-between frames. See **Frame Interpolation** on the [Export](@export) page.
