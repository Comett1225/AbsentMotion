---
title: Camera layers and 3D
group: Create
order: 6
summary: Fly a virtual camera through your layers, tilt layers in 3D and add 3D models.
---
## Camera layers

A **camera layer** makes the project three-dimensional: layers have a depth (Z), and the camera decides what is seen. Add one from **Add**, **Object / Asset**, **Camera**. It lasts the whole project, uses perspective with a view angle of 60 degrees, and has focus blur and fog switched off.

The first time you add a camera, a tip says: "To look through the camera, open this panel and turn on Camera View." The **Camera View** button is in the preview strip (see [The editor screen](@editor)). With it on, you see the project through the camera. Otherwise the cameras show as dark rectangle frames in the preview.

With several cameras, the top-most camera that is active at the playhead is used.

### Moving the camera

Select the camera layer. In the preview, drag to pan it and pinch to change the view angle. **Transform** has **Move**, **Size** and **Rotate**; **Size** changes the view angle.

### Camera Options

Three tabs:

- **Camera View.** A diagram of the view cone, buttons **Perspective** and **Orthographic**, a **View Angle** (0.1 to 179.9 degrees) and a **Zoom** that follows the angle.
- **Focus Blur.** A switch, then **Focus Distance** (with the focus plane shown as "at Z ..."), **Depth of Field** and **Blur Strength**.
- **Fog.** A switch, a **Color** and the **Near** and **Far** distances.

Each row can be animated with the keyframe button.

### Camera Moves

**Camera Moves** in the tool grid adds a ready-made movement: **Push In**, **Pull Out**, **Orbit**, **Pan**, **Dolly Zoom** or **Handheld**. Each tile has an animated preview. Set the **Duration** from 0.5 to 10 seconds. The move starts at the playhead and is shortened if the camera layer ends first. Picking another tile or changing the duration updates it, and the result is ordinary keyframes you can edit.

## Tilt a layer in 3D

Any layer can be tilted around the X and Y axes. Open **Transform** and the **Tilt** tab, which has **X ROTATION** and **Y ROTATION** in degrees. Dragging on the preview while Rotate or Tilt is open tilts the layer too.

When a layer is tilted, animated in tilt, is a 3D model or has a 3D effect, the **Rotate** tab turns into a 3D trackpad ("Drag here to rotate in 3D") with **X**, **Y** and **Z ROTATION** boxes. One finger tilts the layer and two fingers twist it. **Reset 3D Rotation** in the **More** menu puts it back.

## 3D effects

The effects in the **3D** category turn a layer into a solid: Cube, Box, Hollow Box, Cylinder, Ellipsoid, Torus, Pyramid, Octahedron, Hexagonal Prism, Star Prism, Star Polyhedron, Heart, Three-axis Cross, Raster Extrude, Flip Layer, Spherize and Tunnel. When one is present, the Rotate and Tilt controls change that effect's rotation. See the [effects library](@effects).

## 3D models

**Add**, **Object / Asset**, **3D Model** loads an `.obj`, `.glb` or `.gltf` file. If the model has material and picture files, pick them together with the model. The files can total up to 256 MB, and a model with more than 50,000 triangles is shown in a simplified form (the app tells you).

**Model Options** has **Size**, **Auto-rotate**, **Light Angle**, **Ambient Light** and **Light Intensity**, all animatable. A model also has **Transform** (including 3D rotation), **Opacity & Blend Mode**, **Effects** and **Presets**.
