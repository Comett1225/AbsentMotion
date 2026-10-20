# Icon sources

Every icon in AbsentMotion is either taken from an open icon set whose licence allows redistribution, or drawn for this
project. Nothing is copied or traced from another app. This file is the single list of where each icon comes from; the
third-party notices file is generated from it.

- **Lucide** (https://lucide.dev, https://github.com/lucide-icons/lucide), ISC licence, version **1.59.0** (SVG files at
  `https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/<name>.svg`, pages at `https://lucide.dev/icons/<name>`).
  Lucide's licence file marks a few icons as derived from Feather (https://github.com/feathericons/feather); those are also
  under the MIT licence below, so both notices apply to them. The Licence column says "ISC and MIT (Feather)" for those.
- **Material Symbols** (https://github.com/google/material-design-icons, Apache-2.0): none are used.
- **Own drawing**: made for AbsentMotion from circles, lines, rectangles and arcs; Apache-2.0 like the rest of the code.
- **Effect thumbnails** are not icons: they are small renders of six CC0 photographs, listed in `THIRD_PARTY_NOTICES.md`
  (section 5, "Effect thumbnail photos").

How the Lucide shapes were used: the SVG shapes (`path`, `circle`, `rect`, `line`) were joined into one path-data string on
the same 24 unit grid, written with absolute commands, and tinted and stroked by the app (round caps and joins, 1.2 to 2.5 dp
strokes depending on the icon). Where the Notes column says "scaled" or "mirrored" the shape was scaled about the grid
centre or flipped left to right; nothing else about the geometry was changed. "Moved" means shifted to sit inside the icon's
own viewport (for example a 32 dp speaker). The path strings themselves live in the `*Icons.kt` files.

The launcher icon is an own drawing, made by `tools/icon/make_icon.py` (source of the mark: `tools/icon/mark.svg`).

## Counts

| Source | Icons |
| --- | --- |
| Lucide only | 167 |
| Lucide shapes plus own parts | 8 |
| Own drawing only | 40 |
| Material Symbols | 0 |

(An icon is one named member of an `*Icons` object, or one icon-drawing composable.)

## Launcher icon

| File | What | Source | Licence |
| --- | --- | --- | --- |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | adaptive icon mark | own drawing (`tools/icon/make_icon.py`) | Apache-2.0 (own) |
| `app/src/main/res/drawable/ic_launcher_background.xml` | flat colour #FF5A36 | own | Apache-2.0 (own) |
| `tools/icon/mark.svg` | the mark on its background | own drawing | Apache-2.0 (own) |

## Icons

| File | Icon | Source | Upstream icon | Licence | URL | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| `ui/editor/ChromeIcons.kt` | `Exit` | Lucide | `log-out` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/log-out.svg | log-out mirrored left-right |
| `ui/editor/ChromeIcons.kt` | `Back` | Lucide | `arrow-left` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/arrow-left.svg | - |
| `ui/editor/ChromeIcons.kt` | `Gear` | Lucide | `settings` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/settings.svg | - |
| `ui/editor/ChromeIcons.kt` | `Share` | Lucide | `square-arrow-out-up-right` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/square-arrow-out-up-right.svg | - |
| `ui/editor/ChromeIcons.kt` | `Trash` | Lucide | `trash-2` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/trash-2.svg | - |
| `ui/editor/ChromeIcons.kt` | `More` | Lucide + own drawing | `circle` | ISC and MIT (Feather); own part Apache-2.0 | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/circle.svg | three dots |
| `ui/editor/ChromeIcons.kt` | `Undo` | Lucide | `undo-2` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/undo-2.svg | - |
| `ui/editor/ChromeIcons.kt` | `Redo` | Lucide | `redo-2` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/redo-2.svg | - |
| `ui/editor/ChromeIcons.kt` | `SkipBack` | Lucide | `arrow-left-to-line` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/arrow-left-to-line.svg | - |
| `ui/editor/ChromeIcons.kt` | `SkipForward` | Lucide | `arrow-right-to-line` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/arrow-right-to-line.svg | - |
| `ui/editor/ChromeIcons.kt` | `Play` | Lucide | `play` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/play.svg | play scaled x0.8 |
| `ui/editor/ChromeIcons.kt` | `PlayRepeat` | Lucide + own drawing | `repeat` | ISC; own part Apache-2.0 | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/repeat.svg | play triangle inside the loop |
| `ui/editor/ChromeIcons.kt` | `Pause` | Lucide | `pause` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pause.svg | pause scaled x0.7 |
| `ui/editor/ChromeIcons.kt` | `Transition` | own drawing | - | Apache-2.0 (own) | - | two triangles meeting in the middle |
| `ui/editor/ChromeIcons.kt` | `CopyPaste` | Lucide | `clipboard-copy` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/clipboard-copy.svg | - |
| `ui/editor/ChromeIcons.kt` | `PreviewMode` | Lucide + own drawing | `scan` | ISC; own part Apache-2.0 | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/scan.svg | scan moved; inner screen; small arrow |
| `ui/editor/ChromeIcons.kt` | `PreviewModeOpen` | Lucide + own drawing | `scan` | ISC; own part Apache-2.0 | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/scan.svg | small arrow; inner screen |
| `ui/editor/ChromeIcons.kt` | `PreviewCamera` | Lucide | `video` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/video.svg | video scaled x0.6, moved |
| `ui/editor/ChromeIcons.kt` | `Plus` | Lucide | `plus` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/plus.svg | plus scaled x1.15 |
| `ui/editor/ChromeIcons.kt` | `ShapeTab` | Lucide | `shapes` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/shapes.svg | - |
| `ui/editor/ChromeIcons.kt` | `MediaTab` | Lucide | `image-play` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/image-play.svg | - |
| `ui/editor/ChromeIcons.kt` | `AudioTab` | Lucide | `music` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/music.svg | - |
| `ui/editor/ChromeIcons.kt` | `ObjectTab` | Lucide | `box` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/box.svg | - |
| `ui/editor/ChromeIcons.kt` | `TemplateTab` | Lucide | `layout-template` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/layout-template.svg | - |
| `ui/editor/ChromeIcons.kt` | `Freehand` | Lucide | `pencil-line` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pencil-line.svg | - |
| `ui/editor/ChromeIcons.kt` | `VectorDrawing` | Lucide | `pen-tool` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pen-tool.svg | - |
| `ui/editor/ChromeIcons.kt` | `TextTool` | Lucide | `type` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/type.svg | - |
| `ui/editor/ChromeIcons.kt` | `Captions` | Lucide | `message-square-text` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/message-square-text.svg | - |
| `ui/editor/ChromeIcons.kt` | `CloseThick` | Lucide | `x` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/x.svg | x scaled x0.9 |
| `ui/editor/ChromeIcons.kt` | `NullObject` | Lucide | `crosshair` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/crosshair.svg | Add menu, Null |
| `ui/editor/ChromeIcons.kt` | `AssetProject` | Lucide | `folder-open` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/folder-open.svg | Add menu, Asset / Project |
| `ui/editor/ChromeIcons.kt` | `ImportSvg` | Lucide | `vector-square` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/vector-square.svg | Add menu, Import SVG |
| `ui/editor/ChromeIcons.kt` | `Model3d` | Lucide | `cuboid` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/cuboid.svg | Add menu, 3D Model |
| `ui/editor/ChromeIcons.kt` | `Close` | Lucide | `x` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/x.svg | x scaled x1.15 |
| `ui/editor/ChromeIcons.kt` | `ChevronDown` | Lucide | `chevron-down` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-down.svg | - |
| `ui/editor/ChromeIcons.kt` | `Pencil` | Lucide | `pencil` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pencil.svg | - |
| `ui/editor/ChromeIcons.kt` | `SelectAll` | Lucide | `list-checks` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/list-checks.svg | - |
| `ui/editor/ChromeIcons.kt` | `Duplicate` | Lucide | `copy-plus` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/copy-plus.svg | - |
| `ui/editor/ChromeIcons.kt` | `SavePreset` | Lucide | `save` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/save.svg | - |
| `ui/editor/ChromeIcons.kt` | `Copy` | Lucide | `copy` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/copy.svg | - |
| `ui/editor/ChromeIcons.kt` | `Paste` | Lucide | `clipboard-paste` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/clipboard-paste.svg | - |
| `ui/editor/ChromeIcons.kt` | `PasteStyle` | Lucide | `paintbrush` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/paintbrush.svg | - |
| `ui/editor/ChromeIcons.kt` | `ChevronRight` | Lucide | `chevron-right` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-right.svg | - |
| `ui/editor/ChromeIcons.kt` | `LayerParentIcon` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing: dashed square, solid square, ring badge |
| `ui/editor/PanelIcons.kt` | `Speed` | Lucide | `gauge` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/gauge.svg | - |
| `ui/editor/PanelIcons.kt` | `Animate` | own drawing | - | Apache-2.0 (own) | - | circle with three speed lines |
| `ui/editor/PanelIcons.kt` | `TrimLeft` | own drawing | - | Apache-2.0 (own) | - | playhead, solid clip and dashed edges |
| `ui/editor/PanelIcons.kt` | `TrimRight` | own drawing | - | Apache-2.0 (own) | - | mirror of TrimLeft |
| `ui/editor/PanelIcons.kt` | `Split` | Lucide | `scissors` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/scissors.svg | - |
| `ui/editor/PanelIcons.kt` | `AudioMuted` | Lucide | `volume-x` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/volume-x.svg | - |
| `ui/editor/PanelIcons.kt` | `Turtle` | Lucide | `turtle` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/turtle.svg | slow end of the speed slider |
| `ui/editor/PanelIcons.kt` | `Rabbit` | Lucide | `rabbit` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/rabbit.svg | fast end of the speed slider |
| `ui/editor/PanelIcons.kt` | `PaintBucket` | Lucide | `paint-bucket` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/paint-bucket.svg | - |
| `ui/editor/PanelIcons.kt` | `BorderShadow` | own drawing | - | Apache-2.0 (own) | - | square with an offset L-shaped shadow; also the Shadow tab's icon |
| `ui/editor/PanelIcons.kt` | `Blending` | Lucide | `blend` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/blend.svg | - |
| `ui/editor/PanelIcons.kt` | `MoveTransform` | Lucide | `move-3d` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/move-3d.svg | - |
| `ui/editor/PanelIcons.kt` | `EditShape` | Lucide | `spline` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/spline.svg | - |
| `ui/editor/PanelIcons.kt` | `Star` | Lucide | `star` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/star.svg | - |
| `ui/editor/PanelIcons.kt` | `Effects` | Lucide | `sparkles` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/sparkles.svg | - |
| `ui/editor/PanelIcons.kt` | `Camera` | Lucide | `video` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/video.svg | - |
| `ui/editor/PanelIcons.kt` | `Back` | Lucide | `arrow-left` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/arrow-left.svg | - |
| `ui/editor/PanelIcons.kt` | `TabStroke` | Lucide | `route` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/route.svg | Border & Shadow, Stroke tab |
| `ui/editor/PanelIcons.kt` | `TabBorder` | Lucide | `square` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/square.svg | Border & Shadow, Border tab |
| `ui/editor/PanelIcons.kt` | `Keyframe` | own drawing | - | Apache-2.0 (own) | - | hollow tall diamond with a plus, the proportions of the timeline keyframes |
| `ui/editor/PanelIcons.kt` | `KeyframeRemove` | own drawing | - | Apache-2.0 (own) | - | solid tall diamond with a minus cut out, shown on a keyframe |
| `ui/editor/PanelIcons.kt` | `Easing` | own drawing | - | Apache-2.0 (own) | - | axes and an ease curve |
| `ui/editor/PanelIcons.kt` | `More` | Lucide | `ellipsis` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/ellipsis.svg | - |
| `ui/editor/PanelIcons.kt` | `TabMove` | Lucide | `move` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/move.svg | - |
| `ui/editor/PanelIcons.kt` | `TabRotate` | Lucide | `rotate-cw` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/rotate-cw.svg | - |
| `ui/editor/PanelIcons.kt` | `TabSize` | Lucide | `scaling` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/scaling.svg | - |
| `ui/editor/PanelIcons.kt` | `TabSkew` | own drawing | - | Apache-2.0 (own) | - | parallelogram and a dashed upright edge |
| `ui/editor/PanelIcons.kt` | `Link` | Lucide | `link-2` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/link-2.svg | - |
| `ui/editor/PanelIcons.kt` | `Unlink` | Lucide | `link-2-off` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/link-2-off.svg | - |
| `ui/editor/PanelIcons.kt` | `FillNone` | Lucide | `ban` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/ban.svg | - |
| `ui/editor/PanelIcons.kt` | `FillGradient` | own drawing | - | Apache-2.0 (own) | - | frame with stripes that spread out |
| `ui/editor/PanelIcons.kt` | `FillMedia` | Lucide | `image` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/image.svg | - |
| `ui/editor/PanelIcons.kt` | `AddColor` | Lucide | `square-plus` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/square-plus.svg | - |
| `ui/editor/PanelIcons.kt` | `Eyedropper` | Lucide | `pipette` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pipette.svg | - |
| `ui/editor/PanelIcons.kt` | `Palette` | Lucide | `palette` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/palette.svg | - |
| `ui/editor/PanelIcons.kt` | `ColorWheel` | own drawing | - | Apache-2.0 (own) | - | ring, hub and three spokes |
| `ui/editor/PanelIcons.kt` | `RgbSliders` | Lucide | `sliders-horizontal` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/sliders-horizontal.svg | - |
| `ui/editor/PanelIcons.kt` | `Saturation` | Lucide | `droplet` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/droplet.svg | - |
| `ui/editor/PanelIcons.kt` | `Brightness` | Lucide | `sun` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/sun.svg | - |
| `ui/editor/PanelIcons.kt` | `Opacity` | own drawing | - | Apache-2.0 (own) | - | circle with a checker |
| `ui/editor/PanelIcons.kt` | `CheckCircle` | Lucide | `circle-check` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/circle-check.svg | - |
| `ui/editor/PanelIcons.kt` | `Backspace` | Lucide | `delete` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/delete.svg | - |
| `ui/editor/PanelIcons.kt` | `Check` | Lucide | `check` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/check.svg | - |
| `ui/home/HomeIcons.kt` | `Receive` | Lucide | `download` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/download.svg | - |
| `ui/home/HomeIcons.kt` | `Menu` | Lucide | `menu` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/menu.svg | menu scaled x1.1 |
| `ui/home/HomeIcons.kt` | `ChevronDown` | Lucide | `chevron-down` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-down.svg | chevron-down scaled x1.15 |
| `ui/home/HomeIcons.kt` | `ChevronRight` | Lucide | `chevron-right` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-right.svg | - |
| `ui/home/HomeIcons.kt` | `Close` | Lucide | `x` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/x.svg | x scaled x1.2 |
| `ui/home/HomeIcons.kt` | `Plus` | Lucide | `plus` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/plus.svg | plus scaled x1.45 |
| `ui/home/HomeIcons.kt` | `ClearCircle` | Lucide | `circle-x` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/circle-x.svg | circle-x scaled x0.78 |
| `ui/home/HomeIcons.kt` | `More` | Lucide | `ellipsis` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/ellipsis.svg | ellipsis scaled x1.1 |
| `ui/home/HomeIcons.kt` | `Pencil` | Lucide | `pencil` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pencil.svg | - |
| `ui/home/HomeIcons.kt` | `Upload` | Lucide | `file-up` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/file-up.svg | - |
| `ui/home/HomeIcons.kt` | `Link` | Lucide | `link-2` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/link-2.svg | - |
| `ui/home/HomeIcons.kt` | `ArrowDown` | Lucide | `arrow-down` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/arrow-down.svg | stroke 1.0, shown at 56 dp under the empty project list |
| `ui/home/HomeIcons.kt` | `Aspect` | Lucide | `expand` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/expand.svg | - |
| `ui/home/HomeIcons.kt` | `Resolution` | Lucide | `grid-3x3` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/grid-3x3.svg | - |
| `ui/home/HomeIcons.kt` | `Folder` | Lucide | `folder` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/folder.svg | - |
| `ui/home/HomeIcons.kt` | `FrameRate` | Lucide | `film` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/film.svg | - |
| `ui/home/HomeIcons.kt` | `Select` | Lucide | `pointer` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pointer.svg | - |
| `ui/home/HomeIcons.kt` | `Export` | Lucide | `share` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/share.svg | - |
| `ui/home/HomeIcons.kt` | `Duplicate` | Lucide | `copy` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/copy.svg | - |
| `ui/home/HomeIcons.kt` | `Delete` | Lucide | `trash-2` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/trash-2.svg | - |
| `ui/home/HomeIcons.kt` | `Replace` | Lucide | `arrow-right-left` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/arrow-right-left.svg | - |
| `ui/home/HomeIcons.kt` | `CropFill` | own drawing | - | Apache-2.0 (own) | - | rounded rectangle |
| `ui/home/HomeIcons.kt` | `CropFit` | own drawing | - | Apache-2.0 (own) | - | rectangle with a smaller one inside |
| `ui/home/HomeIcons.kt` | `CropStretch` | Lucide + own drawing | `move-horizontal` | ISC; own part Apache-2.0 | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/move-horizontal.svg | move-horizontal scaled x0.62; rectangle |
| `ui/home/HomeIcons.kt` | `ProjectsTab` | Lucide | `square-play` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/square-play.svg | - |
| `ui/home/HomeIcons.kt` | `ProjectsTabOff` | Lucide | `square-play` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/square-play.svg | - |
| `ui/home/HomeIcons.kt` | `TemplatesTabOn` | Lucide + own drawing | `layout-template` | ISC; own part Apache-2.0 | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/layout-template.svg | header band filled |
| `ui/home/HomeIcons.kt` | `TemplatesTab` | Lucide | `layout-template` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/layout-template.svg | - |
| `ui/export/ExportIcons.kt` | `Close` | Lucide | `x` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/x.svg | x scaled x1.2 |
| `ui/export/ExportIcons.kt` | `Back` | Lucide | `arrow-left` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/arrow-left.svg | - |
| `ui/export/ExportIcons.kt` | `ChevronRight` | Lucide | `chevron-right` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-right.svg | chevron-right scaled x1.1 |
| `ui/export/ExportIcons.kt` | `ChevronDown` | Lucide | `chevron-down` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-down.svg | chevron-down scaled x1.1 |
| `ui/export/ExportIcons.kt` | `Info` | Lucide | `info` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/info.svg | - |
| `ui/export/ExportIcons.kt` | `Help` | own drawing | - | Apache-2.0 (own) | - | circle with a question mark |
| `ui/export/ExportIcons.kt` | `Video` | Lucide | `clapperboard` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/clapperboard.svg | - |
| `ui/export/ExportIcons.kt` | `Package` | Lucide | `package` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/package.svg | - |
| `ui/export/ExportIcons.kt` | `Frame` | Lucide | `image` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/image.svg | - |
| `ui/export/ExportIcons.kt` | `Sequence` | Lucide | `images` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/images.svg | - |
| `ui/export/ExportIcons.kt` | `Xml` | Lucide | `code-xml` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/code-xml.svg | - |
| `ui/export/ExportIcons.kt` | `File` | Lucide | `file` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/file.svg | - |
| `ui/export/ExportIcons.kt` | `Play` | Lucide | `play` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/play.svg | play scaled x0.8, moved |
| `ui/export/ExportIcons.kt` | `Pause` | Lucide | `pause` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pause.svg | pause scaled x0.85, moved |
| `ui/export/ExportIcons.kt` | `Prev` | own drawing | - | Apache-2.0 (own) | - | chevron polyline |
| `ui/export/ExportIcons.kt` | `Next` | own drawing | - | Apache-2.0 (own) | - | chevron polyline |
| `ui/export/ExportIcons.kt` | `Share` | Lucide | `square-arrow-out-up-right` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/square-arrow-out-up-right.svg | - |
| `ui/editor/TextIcons.kt` | `Edit` | Lucide | `case-sensitive` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/case-sensitive.svg | - |
| `ui/editor/TextIcons.kt` | `Left` | Lucide | `align-left` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-left.svg | - |
| `ui/editor/TextIcons.kt` | `Centre` | Lucide | `align-center` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-center.svg | - |
| `ui/editor/TextIcons.kt` | `Right` | Lucide | `align-right` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-right.svg | - |
| `ui/editor/TextIcons.kt` | `Justify` | Lucide | `align-justify` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-justify.svg | - |
| `ui/editor/TextIcons.kt` | `Tick` | Lucide | `check` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/check.svg | - |
| `ui/editor/TextIcons.kt` | `Menu` | Lucide | `menu` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/menu.svg | - |
| `ui/editor/TextIcons.kt` | `Search` | Lucide | `search` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/search.svg | - |
| `ui/editor/TextIcons.kt` | `Import` | Lucide | `import` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/import.svg | - |
| `ui/editor/TextIcons.kt` | `Recent` | Lucide | `clock` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/clock.svg | - |
| `ui/editor/TextIcons.kt` | `Down` | own drawing | - | Apache-2.0 (own) | - | solid triangle |
| `ui/editor/TextIcons.kt` | `Up` | own drawing | - | Apache-2.0 (own) | - | solid triangle |
| `ui/editor/TextIcons.kt` | `DownOutline` | Lucide | `chevron-down` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-down.svg | - |
| `ui/editor/TextIcons.kt` | `ViewAll` | Lucide | `layout-grid` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/layout-grid.svg | - |
| `ui/editor/TextIcons.kt` | `Star` | Lucide | `star` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/star.svg | - |
| `ui/editor/TextIcons.kt` | `FilledStar` | Lucide | `star` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/star.svg | - |
| `ui/editor/SelectionIcons.kt` | `Cancel` | Lucide | `x` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/x.svg | x scaled x1.15 |
| `ui/editor/SelectionIcons.kt` | `Next` | Lucide | `chevron-right` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-right.svg | - |
| `ui/editor/SelectionIcons.kt` | `Previous` | Lucide | `chevron-left` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-left.svg | - |
| `ui/editor/SelectionIcons.kt` | `Mask` | Lucide + own drawing | `scan` | ISC; own part Apache-2.0 | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/scan.svg | square and circle inside the scan frame |
| `ui/editor/SelectionIcons.kt` | `Exclude` | Lucide + own drawing | `scan` | ISC; own part Apache-2.0 | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/scan.svg | square and circle with the overlap crossed out |
| `ui/editor/SelectionIcons.kt` | `Group` | Lucide | `group` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/group.svg | - |
| `ui/editor/SelectionIcons.kt` | `AlignLeft` | Lucide | `align-start-vertical` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-start-vertical.svg | - |
| `ui/editor/SelectionIcons.kt` | `AlignCentre` | Lucide | `align-center-vertical` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-center-vertical.svg | - |
| `ui/editor/SelectionIcons.kt` | `AlignRight` | Lucide | `align-end-vertical` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-end-vertical.svg | - |
| `ui/editor/SelectionIcons.kt` | `AlignTop` | Lucide | `align-start-horizontal` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-start-horizontal.svg | - |
| `ui/editor/SelectionIcons.kt` | `AlignMiddle` | Lucide | `align-center-horizontal` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-center-horizontal.svg | - |
| `ui/editor/SelectionIcons.kt` | `AlignBottom` | Lucide | `align-end-horizontal` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-end-horizontal.svg | - |
| `ui/editor/SelectionIcons.kt` | `DistributeHorizontal` | Lucide | `align-horizontal-space-between` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-horizontal-space-between.svg | - |
| `ui/editor/SelectionIcons.kt` | `DistributeVertical` | Lucide | `align-vertical-space-between` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/align-vertical-space-between.svg | - |
| `ui/editor/AudioIcons.kt` | `Speaker` | Lucide | `volume-2` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/volume-2.svg | volume-2 moved |
| `ui/editor/AudioIcons.kt` | `Note` | Lucide | `music` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/music.svg | music moved |
| `ui/editor/AudioIcons.kt` | `Level` | Lucide | `volume-2` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/volume-2.svg | volume-2 moved |
| `ui/editor/TimelineIcons.kt` | `Eye` | Lucide | `eye` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/eye.svg | eye moved |
| `ui/editor/TimelineIcons.kt` | `EyeOff` | Lucide | `eye-off` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/eye-off.svg | eye-off moved |
| `ui/editor/effects/EffectIcons.kt` | `Close` | Lucide | `x` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/x.svg | x scaled x1.15 |
| `ui/editor/effects/EffectIcons.kt` | `Search` | Lucide | `search` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/search.svg | - |
| `ui/editor/effects/EffectIcons.kt` | `Add` | Lucide | `plus` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/plus.svg | - |
| `ui/editor/effects/EffectIcons.kt` | `Expand` | own drawing | - | Apache-2.0 (own) | - | solid triangle |
| `ui/editor/effects/EffectIcons.kt` | `Expanded` | own drawing | - | Apache-2.0 (own) | - | solid triangle |
| `ui/editor/effects/EffectIcons.kt` | `MoreVert` | Lucide | `ellipsis-vertical` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/ellipsis-vertical.svg | - |
| `ui/editor/effects/EffectIcons.kt` | `Grip` | Lucide | `menu` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/menu.svg | - |
| `ui/editor/effects/EffectIcons.kt` | `Eye` | Lucide | `eye` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/eye.svg | - |
| `ui/editor/effects/EffectIcons.kt` | `EyeOff` | Lucide | `eye-off` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/eye-off.svg | - |
| `ui/editor/effects/EffectIcons.kt` | `Recent` | Lucide | `clock` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/clock.svg | - |
| `ui/editor/MediaIcons.kt` | `MediaGridIcon` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing |
| `ui/editor/MediaIcons.kt` | `MediaAlbumArrow` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing |
| `ui/editor/MediaIcons.kt` | `MediaPlayIcon` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing |
| `ui/editor/DrawingIcons.kt` | `Pen` | Lucide | `pen` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/pen.svg | - |
| `ui/editor/DrawingIcons.kt` | `Brush` | Lucide | `brush` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/brush.svg | - |
| `ui/editor/DrawingIcons.kt` | `Fill` | Lucide | `paint-bucket` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/paint-bucket.svg | paint-bucket scaled x0.83 |
| `ui/editor/DrawingIcons.kt` | `Eraser` | Lucide | `eraser` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/eraser.svg | eraser scaled x0.875 |
| `ui/editor/DrawingIcons.kt` | `PathClosed` | own drawing | - | Apache-2.0 (own) | - | ring with four points |
| `ui/editor/DrawingIcons.kt` | `PathOpen` | own drawing | - | Apache-2.0 (own) | - | ring with a gap and four points |
| `ui/editor/DrawingIcons.kt` | `VertexCurved` | own drawing | - | Apache-2.0 (own) | - | smooth point: round handles on a line through the point |
| `ui/editor/DrawingIcons.kt` | `VertexSharp` | own drawing | - | Apache-2.0 (own) | - | corner point: square handles at an angle |
| `ui/editor/DrawingIcons.kt` | `DeletePoint` | Lucide | `circle-minus` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/circle-minus.svg | - |
| `ui/editor/DrawingIcons.kt` | `AddPoint` | Lucide | `circle-plus` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/circle-plus.svg | - |
| `ui/editor/DrawingIcons.kt` | `SizeDot` | own drawing | - | Apache-2.0 (own) | - | three discs |
| `ui/editor/DrawingIcons.kt` | `Contours` | own drawing | - | Apache-2.0 (own) | - | two nested rings |
| `ui/editor/DrawingIcons.kt` | `Prev` | Lucide | `chevron-left` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-left.svg | - |
| `ui/editor/DrawingIcons.kt` | `Next` | Lucide | `chevron-right` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-right.svg | - |
| `ui/editor/DrawingIcons.kt` | `AngleLock` | own drawing | - | Apache-2.0 (own) | - | arcs, handles and a point |
| `ui/editor/DrawingIcons.kt` | `AngleLockOn` | own drawing | - | Apache-2.0 (own) | - | arcs, handles and a point |
| `ui/editor/DrawingIcons.kt` | `DistanceLock` | own drawing | - | Apache-2.0 (own) | - | double arrow between two handles |
| `ui/editor/DrawingIcons.kt` | `DistanceLockOn` | own drawing | - | Apache-2.0 (own) | - | double arrow between two handles |
| `ui/editor/panels/SpeedPanel.kt` | `RetimeGlyph` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing: playhead, arrow and a diamond on the affected part |
| `ui/editor/panels/EasingIcons.kt` | `ReverseIcon` | Lucide | `arrow-right-left` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/arrow-right-left.svg | - |
| `ui/editor/panels/EasingIcons.kt` | `ChevronIcon` | Lucide | `chevron-left`, `chevron-right` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-left.svg<br>https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/chevron-right.svg | - |
| `ui/editor/panels/EasingIcons.kt` | `CurveThumb` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing: framed sketch of the curve |
| `ui/editor/PreviewMode.kt` | `GridIcon` | Lucide | `grid-3x3` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/grid-3x3.svg | - |
| `ui/editor/PreviewMode.kt` | `CameraIcon` | Lucide | `video`, `video-off` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/video.svg<br>https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/video-off.svg | - |
| `ui/editor/PreviewMode.kt` | `CanvasZoomIcon` | Lucide | `zoom-in` | ISC and MIT (Feather) | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/zoom-in.svg | - |
| `ui/editor/PreviewMode.kt` | `PixelIcon` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing |
| `ui/editor/PreviewMode.kt` | `SoloIcon` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing |
| `ui/editor/PreviewMode.kt` | `ZoomBlock buttons` | own drawing | - | Apache-2.0 (own) | - | Canvas drawing |
| `ui/camera/CameraIcons.kt` | `Camera` | Lucide | `camera` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/camera.svg | - |
| `ui/camera/CameraIcons.kt` | `Switch` | Lucide | `switch-camera` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/switch-camera.svg | - |
| `ui/camera/CameraIcons.kt` | `Flash` | Lucide | `zap` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/zap.svg | - |
| `ui/camera/CameraIcons.kt` | `FlashOff` | Lucide | `zap-off` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/zap-off.svg | - |
| `ui/camera/CameraIcons.kt` | `Timer` | Lucide | `timer` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/timer.svg | - |
| `ui/camera/CameraIcons.kt` | `TimerOff` | Lucide | `timer-off` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/timer-off.svg | - |
| `ui/camera/CameraIcons.kt` | `Grid` | Lucide | `grid-3x3` | ISC | https://cdn.jsdelivr.net/npm/lucide-static@1.59.0/icons/grid-3x3.svg | - |

## Licence texts

### Lucide (ISC), https://github.com/lucide-icons/lucide/blob/main/LICENSE

```
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

### Feather (MIT), for the Lucide icons derived from Feather, https://github.com/feathericons/feather/blob/main/LICENSE

The Lucide licence file lists the Feather-derived icons and reproduces this text:

```
The MIT License (MIT) (for the icons listed above)

Copyright (c) 2013-present Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
