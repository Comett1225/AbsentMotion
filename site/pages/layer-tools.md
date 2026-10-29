---
title: Layer tools
group: Interface
order: 6
summary: The grid of tools that appears when you select a layer, and every panel behind it.
---
Tap a layer and a grid of tools opens at the bottom. The first row has five small buttons (Speed, trim and split, Volume), described in [The timeline](@timeline). Under them are large buttons whose set depends on the kind of layer.

| Layer | Tools |
|---|---|
| Shape, photo, video | Fill & Color, Frame & Shadow, Opacity & Blend Mode, Transform, Shape Options, Presets, Effects |
| Path shape (pen, SVG, many shapes) | The same, with **Edit Points** instead of Shape Options |
| Text | The same, with **Edit Text** instead of Shape Options |
| Drawing | The same, with **Edit Drawing** instead of Shape Options |
| Group | Transform, Presets, Effects. A group linked to an asset also has **Asset Properties**. |
| Null | Like a shape, but nothing to draw |
| Camera | Camera Options, Transform, Effects, Presets, Camera Moves |
| 3D model | Model Options, Opacity & Blend Mode, Transform, Presets, Effects |
| Audio | Presets, Effects (including Voice filters), AI (Reduce Noise and Auto captions) |

A photo or video is a rectangle with a picture fill, so it has the same tools as a shape.

## Every panel

Most panels share a narrow column on the left with four buttons: **Back**, **Add keyframe** or **Remove keyframe**, **Easing** and, in Transform and Effects only, **More**. A gray keyframe button means the setting cannot be animated. See [Keyframes and easing](@keyframes).

Tap any number box to open the number pad. The first tap selects the whole value, so typing replaces it. A tick applies it; tapping outside closes the pad. Rulers (a strip with ticks and a center needle) change a number when you drag sideways.

### Transform

Tabs on the right: **Move**, **Rotate**, **Size**, **Tilt**, **Skew**, **Pivot**. A camera has only Move, Rotate and Size. Hold **Transform** in the tools, or open **More**, to switch between **Basic Controls** (Move, Rotate, Size and Skew) and **Advanced Controls** (all six tabs, the default).

- **Move.** A trackpad ("Drag here to move the layer") and X and Y boxes. Tap **Z** to move the layer along the depth axis. Thin red guides show when the layer lines up with the canvas or another layer.
- **Rotate.** A dial: drag the knob around or tap the degrees to type. When the layer is tilted in 3D, this tab becomes a 3D trackpad: one finger tilts, two fingers twist.
- **Size.** Width and height boxes and a **link** button (on by default) that keeps the proportions. For a camera this tab changes the view angle. The link is remembered for each layer and saved with the project, and the ruler keeps gliding after a flick.
- **Tilt.** **X ROTATION** and **Y ROTATION** in degrees, for 3D tilt. See [Camera layers and 3D](@camera-3d).
- **Skew.** **X SKEW** and **Y SKEW** in degrees.
- **Pivot.** The point the layer turns and scales around, in pixels from the middle of the layer. Type **X** and **Y**, drag the trackpad, tap one of the nine squares for a corner, an edge or the center, or drag the marker on the canvas. The layer and its child layers stay where they are.
- **More** has **Reset Location**, **Reset Rotation**, **Reset Scale**, **Reset 3D Rotation**, **Reset Skew**, **Reset Pivot** and **Reset Transform** (all six).

Move is the position, with X, Y and Z animated together. Rotate, Size, Tilt, Skew and Pivot each have their own keyframes; width and height share the Size keyframes.

### Crop

Photos and videos have a **Crop** button. It dims the part of the layer that will be cut off and shows a frame with eight handles: drag a corner or an edge to crop, or drag inside the frame to move it. Two fingers still zoom the canvas. **Free**, **1:1**, **4:5**, **3:4**, **16:9** and **9:16** fit the largest centered crop of that shape and keep the shape while you drag. Tap a side to type its value, **Reset** removes the crop and **Done** closes the panel. The keyframe button keys all four sides together. The crop is the **Crop** effect, so you can also change it in **Effects**, with **Roundness** and **Feather**; the layer stays where it is.

### Fill & Color

Tabs at the top: **None**, **Color**, **Gradient**, **Media**.

- **Color.** Fourteen swatches, then your saved colors. The bar at the top shows the color as #RRGGBB and its opacity; the **+** on it saves the current color. Three buttons: **Eyedropper** (touch the preview to sample a color), **Color sliders** and **Full color picker** (a color wheel or HSV and RGB sliders with a hex field).
- **Gradient.** Pick linear, radial or sweep, then a start and an end color. Two handles on the preview move the gradient. A gradient has exactly two colors.
- **Media.** Fills the shape with a photo or video. It is active when the layer already has a media fill or is a text layer; for text, tapping it opens the picker to fill the letters with a picture or video.

Only the color in the Color tab can be keyframed.

### Frame & Shadow

Three tabs: **Stroke**, **Border** and **Shadow**.

- **Stroke** is the outline of the shape. It has a switch, a color, a size, the line cap and join, and a style for each end of an open line (arrows, circles, diamonds and more).
- **Border** lets you stack any number of borders. Each border has a size, a color and a position (**INSIDE**, **CENTER**, **OUTSIDE**). Drag the grip to change their order. **Add Border** adds one.
- **Shadow** has a switch, **Color**, **Size**, **Alpha** and **Position** (X and Y).

### Opacity & Blend Mode

An **opacity** slider with a percentage box, and 28 blend modes in seven groups: Normal; Darken (Multiply, Darken, Darker Color, Color Burn, Linear Burn); Lighten (Screen, Lighten, Lighter Color, Color Dodge, Linear Dodge); Contrast (Overlay, Soft Light, Hard Light, Soft Overlay, Vivid Light, Linear Light, Pin Light); Difference (Difference, Exclusion, Subtract, Divide); Color (Hue, Saturation, Color, Luminance); and Mask (Mask, Exclude). Tap a group to open it and tap a mode to apply it. **Mask** keeps what is below only where this layer is; **Exclude** removes what is below where this layer is.

### Shape Options

The settings that define the shape, one row each with a ruler and a value box. Examples: a Circle has Size; a Rounded Rectangle has Size and Radius; a Polygon has Sides, Radius and Angle; a Star has Points, Outer Radius, Inner Radius and Angle; an Arrow has Start, End, Tail Width, Head Width and Head Length. The selected row is the one the keyframe button animates. Photos and videos use Size here.

### Edit Points, Edit Drawing, Edit Text

These open full-screen editors: see [Shapes, drawing and masks](@shapes) and [Text and fonts](@text).

### Camera Options and Model Options

See [Camera layers and 3D](@camera-3d).

### Volume

For sound and for videos. See [Audio](@audio).

### Effects and Presets

See [Using effects](@effects-guide) and [Assets, templates and presets](@assets-templates).
