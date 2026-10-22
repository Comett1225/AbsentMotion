---
title: Using effects
group: Create
order: 2
summary: Add, stack, reorder, hide, copy and animate effects, and find the right one in the effects library.
---
An effect changes how a layer looks or moves: a blur, a glow, a color change, a shake. A layer can have as many effects as you like. AbsentMotion has {{effect_count}} effects in ten categories. The [effects library](@effects) on this website lists every effect with all of its settings.

## Add an effect

1. Select a layer.
2. Tap **Effects** in the tool grid.
3. Tap **Add Effect** at the end of the list. The **Effects Library** opens.
4. Tap an effect. It is added with its standard settings and its settings open at once.

To read about an effect first, press and hold its tile. A page opens with an animated preview, a description, a star to mark it as a favorite, **+ Standard Settings** and, for a few effects, ready-made presets.

![The Effects panel of a layer: the Light Glow effect with its settings](img/effects.webp)

## The Effects Library

- The top has a search box ("Search") that matches names first, then descriptions, and also finds AI tools and presets.
- A strip of recommended effects (Chroma Key, Blur and Glow), a grid of your **Recent** or **Favorite** effects (switch with the two small icons), then the categories.
- **Categories:** Color, Blur, Distort, Stylize, Noise, Repeat, Key, Move, 3D and Text. A **New** category appears for effects added in the latest versions. An **Audio** category appears for layers that have sound, and **AI** holds the AI tools.
- An effect that cannot run on your phone is grayed out.

## Settings of an effect

Tap an effect in the list to open it. Its header has the effect name, a three-dot menu and a trash can.

- Each number has a ruler and a value box. Tap the name of a setting to make it the target of the keyframe button. Settings that are positions can be moved by dragging on the preview.
- A color setting opens a row of quick swatches and a field for the exact color.
- A switch turns an option on or off. A choice opens a list.
- The three-dot menu has **Effect Info**, **Reset to Defaults**, **Duplicate**, **Copy Effect** and **Paste Effect Values** (when you copied one effect of the same kind).
- The trash can removes the effect. Tap the header to close it.

If the layer is a parent of other layers, a switch **Apply to Children** appears at the top of the effect (not for Move or Repeat effects).

## The effect list

- The order matters: effects at the top are applied first. Drag the grip (**Reorder**) of a row to change the order.
- The eye (**Visibility**) hides an effect without deleting it.
- **More** in the left column has **Copy Effects** (you tick which ones) and **Paste Effect** or **Paste Effects**, which adds the copied effects at the end of the list of the selected layer.

### Effects on many layers at once

Copy the effects in one layer. Then select several layers (see [Layer menus and multi-select](@layer-menus)), tap **Effects** in the top bar and choose **Paste All Effects**. Sound, camera and null layers are skipped.

## Animate an effect

Every number, color and position in an effect can have keyframes. Open the effect, tap the name of the setting, put the playhead where you want, and tap the keyframe button. The easing button shapes the movement between keyframes. See [Keyframes and easing](@keyframes).

## Presets inside effects

Four text effects come with ready-made presets: Text Progress (Typewriter, Fade In by Word, Slide Up by Letter, Pop In), Text Transform (Wave, Spin Cascade), Text Randomizer (Scramble) and Count Up/Down (Count Up). Press and hold the effect in the library and choose the preset under **PRESET**.

These are different from the **Presets** tool of a layer, which keeps your own looks: see [Assets, templates and presets](@assets-templates).

## Special kinds of effects

- **Move** effects (Auto-Shake, Oscillate, Swing, Spin, Random Displacement, Random Jitter, Pulse, Echo, Offset, Move Along Path, True Motion Blur) animate a layer by themselves.
- **Repeat** effects make copies in a line, a circle, a grid, along a path or scattered.
- **3D** effects turn a layer into a cube, sphere, torus and other solids. The Rotate tab then controls the effect's rotation.
- **Key** effects (Chroma Key, Luma Key) remove a color or the dark and bright parts of a layer, for example a green screen.
- **Text** effects (Text Progress, Text Transform, Text Randomizer, Count Up/Down, Timecode) animate text letter by letter. See [Text and fonts](@text).
- **Audio** effects work on sound: see [Audio](@audio).
- **AI** tools (Remove Background, Reduce Noise) run on the phone: see [AI tools](@ai-tools).

## Tips

- If the preview gets slow with many effects, switch **Preview Quality** to Half or Quarter. The export is not affected.
