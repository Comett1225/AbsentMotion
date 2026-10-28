---
title: The editor screen
group: Interface
order: 3
summary: The top bar, the preview, the play bar and how the pieces fit together.
---
The editor has four parts, from top to bottom: the **top bar**, the **preview**, the **play bar** and the **timeline** with its tools. On a wide tablet held sideways the preview keeps the whole width, and the tools of a selected clip open in a narrow column on the right edge.

![The editor: a photo with a title, and the timeline with keyframe diamonds](img/editor.webp)

Changes are saved automatically a moment after each edit. A version of the project is also kept every two minutes while you edit (see [Projects, backup and import](@projects)). The top layer in the timeline is the front layer, and new layers are added at the front.

The Back button of the phone steps back one level at a time: it closes the preview strip, clears a multi-selection, closes the easing graph, leaves an open effect, leaves a panel, deselects the layer and only then leaves the editor.

## The top bar

What the top bar shows depends on what is selected.

**Nothing selected**

- **Exit to project list**, at the left.
- The **project name**. Tap it to rename the project.
- **Project settings** (the gear): see below.
- **Export & Share** (the share icon): opens the [Export](@export) screen.

**A layer selected**

- **Back** deselects the layer.
- The **layer name**. Tap to rename; this is also the label on its timeline bar.
- The **parent** button (a dashed square, a solid square and a small circle). It lists "None" and every other layer. Choosing one makes that layer the parent: the selected layer then follows it when it moves, turns or scales, and keeps its place on screen when you set it.
- **Delete layer** (the trash can) deletes at once, with no question. Undo brings it back. Children of the layer are released.
- **Layer menu** (three dots): see [Layer menus and multi-select](@layer-menus).

**A panel open.** The top bar shows a back arrow and the panel's name, such as Transform, Fill & Color or Effects.

### Project settings

The gear opens a sheet with the same options as the new project sheet: the shape (16:9, 9:16, 4:5, 1:1, 4:3 or a custom ratio), **Resolution**, **Frame Rate** and **Background**. Changing them only changes the canvas; your layers are not rescaled. The change can be undone. The **Version history** row has an **Earlier versions** button.

For an Asset project the sheet also has **Precompose** and **Re-timing**: see [Assets, templates and presets](@assets-templates).

## The preview

The preview shows the canvas. A transparent background shows as a checkerboard.

- **Select.** Tap a layer to select it (the top layer under your finger). Tap empty canvas to deselect.
- **Move.** Drag anywhere on the preview to move the selected layer, not only on the layer itself.
- **Scale.** The selected layer has a white outline with eight round handles. Dragging a corner scales the layer evenly; dragging a side stretches it in that direction. Pinching with two fingers also scales. The handles scale around the pivot (**Transform**, **Pivot**), which stays still.
- **Rotate.** There is no rotate handle and no two-finger twist for ordinary layers. Use **Transform**, **Rotate** (see [Layer tools](@layer-tools)).
- **Camera layers.** Dragging pans the camera and pinching changes its view angle.
- **Text.** In the text editor, the handle at the side of the text frame changes the wrap width.
- **Guides.** While you move a layer in Transform, thin red lines appear when it lines up with the center of the canvas or with another layer.

### Zoom and pan the canvas

With nothing selected, two fingers pan and zoom the canvas (from 1 % to 6400 %). With a layer selected, tap the small magnifier at the top left of the preview to turn on pan and zoom mode; then two fingers zoom the canvas while one finger still moves the layer. Double-tap with two fingers to go back to 100 %, and again to return to the previous view.

### The preview strip

The last button of the play bar (**Preview mode**) opens a narrow strip at the right edge of the preview with these buttons, from top to bottom.

1. **Pixel preview** shows hard-edged project pixels, useful when zoomed in.
2. **Grid** overlays a grid every 40 project pixels.
3. **Solo the selected layers** hides every other layer. Tap again to draw the other layers at half opacity, and a third time to turn it off.
4. **Camera View** looks through the camera layer (available when the project has one).
5. **Full**, **Half** or **Quarter**: the preview quality. Lower is faster on a slow phone and does not change your export. It is the same setting as **Preview Quality** in [Settings](@settings).
6. **Fast Blur** or **Full Blur**: how the motion blur effects are previewed. Fast is an approximation; Full is accurate and slower. Exports are always accurate.
7. **Zoom**: plus and minus buttons and the current percentage.

### Other things on the preview

- The round **Animate** button at the bottom right appears when a visual layer is selected: it opens In, Out and Loop animations. See [Transitions and animations](@animations).
- The Undid/Redid note at the bottom shows what the last undo or redo did.

## The play bar

Seven buttons, left to right.

1. **Undo** and 2. **Redo**. The app keeps up to 200 steps. A whole drag or slider move counts as one step. Each undo or redo shows a short note such as "Undid: Move".
3. **Skip back** and 5. **Skip forward** jump to the previous or next stop. With nothing selected the stops are the start and the end of the project; with a layer selected they are its start, its end and its keyframes. Bookmarks are always stops.
4. **Play** / **Pause**. Press and hold Play while stopped to turn looping on or off. Playing from the end starts again at the beginning.
6. **Copy and paste** opens a menu for duplicating, copying, pasting and saving presets: see [Layer menus and multi-select](@layer-menus).
7. **Preview mode** opens the preview strip.

The thin line under the bar is a live level meter while the project plays: green, then yellow, then red as the sound gets louder. The whole line flashes red if the sound clips.

## Multi-select

Press and hold the eye or the name area of a layer's row and then slide up or down to select several rows at once; afterwards a tap toggles rows. The top bar changes color and shows group, effects, delete and alignment buttons, and a bar appears at the bottom with clip-timing commands. They are all described in [Layer menus and multi-select](@layer-menus).

## Next

- [The timeline](@timeline)
- [The Add menu](@add-menu)
- [Layer tools](@layer-tools)
