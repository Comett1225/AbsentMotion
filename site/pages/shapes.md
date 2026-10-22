---
title: Shapes, drawing and masks
group: Create
order: 4
summary: Shapes, the pen tool, freehand drawing, SVG import, clipping masks, groups and animated shape masks.
---
## Shapes

Add a ready-made shape from the **Shape** tab of the [Add menu](@add-menu). There are two kinds.

- **Parametric shapes** (Circle, Rectangle, Star, Polygon and others) are edited with **Shape Options**: size, radius, number of points and so on. These settings can be animated.
- **Path shapes** (Heart, Cloud, Gear and others) are edited with **Edit Points**.

Colour and outline are set with **Fill & Color** and **Frame & Shadow**. See [Layer tools](@layer-tools).

## The pen tool and Edit Points

**Pen Tool** in the Add menu creates a new shape layer and opens the point editor. **Edit Points** in the tool grid opens it for an existing path shape. In the preview, tap to place a point; press and drag to pull out a curve handle. A ghost point shows where the next point will land. Leaving without drawing anything deletes the new layer.

At the bottom of the editor:

- **Close the path** or **Open the path** (needs two or more points).
- **Make the point curved** or **Make the point sharp**.
- **Add point** and **Delete point**.
- A trackpad that moves the selected point ("Drag to move the point"), and when you edit a handle of a smooth point, the toggles **Angle** and **Distance** lock its angle or its length.
- **Contour** with arrows moves between the separate sub-paths of a shape.
- A list of the points on the right, with small "+" cells between them for inserting a point.

The top has **Undo**, **Redo** and **Back**.

### Animated shapes (shape keyframes)

In the point editor, a time bar has **Previous shape keyframe**, **Next shape keyframe**, **Add shape keyframe** and **Remove shape keyframe**. A shape keyframe stores the whole shape at the playhead. Move to another time, change the points, add another keyframe, and the shape animates between them. The previous keyframe shows as a faint dashed outline to help you line up. This is how you make an animated mask for rotoscoping: trace an outline around something in a video and change it as the video moves.

## Freehand drawing

**Freehand Brush** in the Add menu creates a drawing layer and opens the drawing editor.

- **Pen** draws an even line, **Brush** changes the line width with pressure, **Fill** turns a loop you draw into a filled shape, and **Eraser** removes parts.
- A size slider (1 to 300), and a colour picker with a hue strip and an opacity strip.
- **Undo** and **Redo** at the top. Each stroke is kept when you lift your finger.
- The tool, size and colour are remembered. Leaving without drawing anything deletes the new layer.

Drawings have no keyframes. To animate a drawing, use effects such as Drawing Progress; see the [effects library](@effects).

## SVG files

**Import SVG** in the Add menu turns a vector image into shapes. See [The Add menu](@add-menu) for what is kept.

## Masks and clipping

- **Create Clipping Mask** (layer menu) clips a layer to the shape of the layer directly below it. **Release Clipping Mask** undoes it.
- **Group & Mask** and **Group & Exclude** (multi-select) group layers and set the top layer's blend mode to Mask or Exclude. You can also set the blend mode **Mask** or **Exclude** on any layer in **Opacity & Blend Mode**. **Mask** keeps the content below only where the layer is; **Exclude** cuts it away.
- Animated masks come from shape keyframes (above).
- **Remove Background** for photos and videos is described in [AI tools](@ai-tools).

## Groups and parents

- **Group** (multi-select) puts layers into one group layer. Move, scale, fade and add effects to the group as one. See [Layer menus and multi-select](@layer-menus).
- A **parent** (the parent button in the top bar) makes one layer follow another without grouping.
- A **Null** layer (Add, Object / Asset) is invisible. Make it the parent of other layers to move them all together, or let [motion tracking](@tracking) move it.
