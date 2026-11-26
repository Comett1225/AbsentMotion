# Changelog

All notable changes to AbsentMotion are listed here, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions use `major.minor.patch`.

Versions before 2.0.0 were never published or numbered. The entry for 1.0.0 stands for the feature set as it stood on
2026-10-12.

## [5.3.2] - 2026-11-26

### Added

#### Effects
- **Face Tracking** - pick a **face** and the video **moves and zooms** so it stays in place. It is tracked once and kept until you change its settings. Searching **face motion** finds it too.
- **Text Spacing** - **letter spacing** and **line spacing** for text, with keyframes.
- **New effects** - **Advanced Chroma Key**, **Box Blur**, **Gaussian Blur**, **Hue Shift**, **Saturation / Vibrance**, **Spectral Map**, **Stroke Color**, **Streak Strips** and **Ring**, each with Alight Motion's settings.
- **Lens Flare** - the old **Light Flare**, now with **Shine**, **Points**, **Alpha** and **Blending Mode** and a new look.
- **Glow Scan** and **Star Polyhedron** - rebuilt with Alight Motion's settings.

#### Community
- **New** - the first page shows everything that was added, **grouped by day**.
- **Effects included** - shown as **tiles**, each with its picture and name.
- **Thumbnails** - every Community effect has **its own picture**, still or moving.

### Changed

#### Effects
- **Deep Glow** - a smoother, richer glow built in light from several sizes, with new **Exposure**, **Threshold** and **Threshold Softness** settings. Projects you already made keep their glow.
- **Find effects like in Alight Motion** - effects also show in the **category Alight Motion uses**, and every effect is found by its **English name** in any language.
- **Alight Motion's names** - **Time Quantization**, **Color Tune**, **Hot Color**, **Exposure / Gamma**, **Highlights and Shadows**, **Find Edges** and **Halftone Dots**. Searching the **old names** still finds them.

#### AI tools
- **Reduce Noise** - a much stronger clean-up that removes **hiss**, **fan noise**, **traffic**, **chatter** and some **room echo** while voices stay **natural**. Its model downloads **once** (4.1 MB), and a new **Amount** slider sets how much is removed.

#### Editor
- **Paste Style** - also pastes the copied layer's **effects**, **animations** and **transitions**.
- **Fonts install by themselves** - fonts a project needs **download on their own**. A weight Google Fonts doesn't have uses the **closest one**.
- **Tool row** - with the playhead past a clip, **Extend end** comes first, then **Move to the playhead**.
- **What's new** - sections **slide** open and shut.
- **Paste Style on computers** - in the **Edit** menu and when you **right-click** a clip, with every part like on the phone.
- **Added** is always the **last category** in the Effects Library.

#### Export
- **Export again at once** - exporting an **unchanged project** again with the same settings shows the **finished video straight away**.
- **Windows** - videos keep to the **bit rate you chose** on AMD graphics, and **size estimates** match the real size.

#### Alight Motion import
- **3D shapes** - **Cube**, **Box**, **Ring**, **Cylinder**, **Torus**, **Ellipsoid**, **Hexagonal Prism**, **Octahedron**, **Pyramid**, **Star Prism**, **Spherize**, **Hollow Box** and **Three-axis Cross** use Alight Motion's **camera and light** and look **the same**.
- **Now the same** - **Auto-Shake**, **Swirl**, **Pixelate**, **Unsharp Mask**, **Radial Shadow**, **Polar Coordinates**, **Kaleidoscope**, **Curl**, **Star**, **Radial Rays**, **Dark Glow**, **Glow Scan**, **Halftone Dots**, **Halftone Lines**, **Fill Behind**, **Stroke Taper** and the **Linear**, **Radial**, **Grid** and **Along Path** repeats.
- **Closer** - **Tunnel**, **Page Curl**, **Heart**, **Raster Extrude**, **Lens Flare**, **Streak Strips**, **Contour Strips**, **CMYK Halftone Dots**, **Energy Beam**, **Zoom Streaks**, **Vortex Blur**, **Random Jitter**, **Flicker**, the **old Cube** and the **Tile** and **Hexagon** effects.
- **Settings and keyframes** - imported effects keep far more of Alight Motion's settings and their **full keyframed range**, such as **Inner Blur** and **Box Blur** Iterations, **Spin Blur**'s centre, **Luma Key**, **Invert** and the **Swirl**, **Pinch/Bulge** and **Ripple** centres.
- **Drawings** - pen and brush strokes are no longer **thicker** than in Alight Motion.
- **Sharp edges** - pictures and videos keep **sharp edges** when turned or scaled, like Alight Motion.
- **181** effects look the same, and **12** are very close.
- **Your saved projects keep their look.**

### Fixed
- **3D layers** - **Wipe** and other effects work on 3D layers that are **turned**, following the layer's own surface.
- **Split sound** - the second piece of a split or separated sound no longer stays **silent** until you pause and play again.
- **Fonts** - the **Fonts not on this phone** message is gone; fonts that can't be downloaded are tried once more on their own.

## [5.3.1] - 2026-11-25

### Added

#### Tools
- **Merge** - join **videos** in the order you choose. Videos that match are joined **without re-encoding**; others are fitted to the **first video's size**, with silence for clips without sound.
- **Convert Image** - turn pictures into **PNG**, **JPG**, **WebP** or **GIF**. Animated **GIF** and **WebP** stay **animated**.
- **Tools page** - starts with **Compress**, **Trim**, **Extract Audio**, **Video Downloader** and **Send between devices**. The highlighted **Add more tools** at the end lists the rest.

#### Captions
- **Import SRT** - turn an **.srt** subtitle file into caption layers with the look you chose, from the **Auto Captions** sheet.

#### Export
- **Same as source** - a project that is **one video** can keep that video's **bit rate**.
- **Export folders** - choose a **Videos and GIFs** folder and a **Sounds** folder in **Settings > Projects**.

#### Look
- **Light Green** theme - navy editor with mint highlights and a light grey home, in **Settings > Theme**.

#### Editor
- **Other keyframes** - while an effect is open, the layer's **other keyframes** show as **dim diamonds** on the timeline, and keyframes you drag **snap** to them.

### Changed

#### Auto captions
- **Far more accurate** - **Standard** now uses a **264 MB** model and **Higher precision** an **874 MB** one, instead of two small models.
- **Faster** on newer phones.
- **Cleaner** - repeated and made-up lines are left out, captions **end with the speech**, and lines split at **natural pauses**.
- The **old caption models** are deleted to free space.

#### Export
- **Exact size estimates** - every estimate is **measured from your project** with the codec, quality, size, frame rate, **Smooth** and **AI Smooth** you chose. It shows **Calculating…** for a moment first.
- **Bit rate kept** - videos come out at the **bit rate you chose**, no longer far bigger.

#### Alight Motion import
- **Layers with a pivot** stay where Alight Motion draws them.
- **Groups** longer than what's inside them **hold the last frame**.
- **Screen**, **Lighten**, **Darken**, **Hard Light** and **Soft Light** layers blend with their opacity like Alight Motion.
- **Text** - **Drawing Progress** draws text outlines in, and **Text Transform** eases each letter in and out like Alight Motion.
- **Wave Warp** - angled waves move like Alight Motion's.
- **Page Curl** - the page **rolls up** like Alight Motion's, with its shine and shadow.
- **Gradient Map** - new **Squeeze** setting, kept on import.
- **Find Edges**, **Time Quantization**, **Move Along Path**, **Lens Blur** and **Chromatic Zoom Blur** now look **the same**.
- **Rays**, **Pixelate**, **Offset**, **Circular Ripple**, **Inner Glow**, **Repeat Along Path** and **Spherize** are closer.
- **Effect settings like Alight Motion's** - about **30 effects** show Alight Motion's **names**, **numbers**, **ranges** and **order**.
- **Measured more strictly** - every effect is now checked with **varied settings** too. **155** look the same, and **15** are very close.
- **Your saved projects keep their look.**

## [5.3.0] - 2026-11-24

### Added

#### Dutch
- **Nederlands** - AbsentMotion now speaks **Dutch**: the whole app, every effect's name, settings and description, and the help pages. Pick it in **Settings > Language**, or it starts in Dutch when your device is set to Dutch.

#### Send between devices
- **Send between devices** - send a project from your **phone to your computer**, or back, on the **same Wi-Fi**. Open **Tools > Send between devices** or a project's **•••** menu > **Send to another device**.
- **Scan to connect** - the other device **scans the code** with its **camera**. No account, nothing goes online, and the code **works once**.
- **Sending back** - a project that comes back **replaces the original** if you didn't change it there; otherwise **both are kept**.
- **Receive** from **Import** on computers or the **link dialog** on phones.

#### 3D models
- **Textures panel** - **replace**, **tint** or **remove** the picture of each material.
- **Missing pictures** - **add the pictures** a model lost on import.
- **Pose panel** - models with **bones** (glTF or GLB) show **every bone** on the preview, including in-between and end bones. Pick one and **turn it** on X, Y and Z, with **keyframes**, or **Reset pose**.

#### Drawing
- **Brush settings** - **Smoothing**, **Opacity**, **Hardness** and **Pressure** (Off, Stylus or Speed) for Freehand.
- **Zoom and pan while drawing** - with **two fingers**, the **mouse wheel** or a **middle-button drag**.
- **Eraser** - keeps its **own size** and can erase **whole strokes**.
- **Pen tool** - tap the **first point** to close a path, and **double-tap** a point to switch **sharp** and **curved**.
- **Hold to straighten** a stroke, and **tap with fingers** to undo and redo.
- **Keys on computers** - **Space** to pan, **Alt** to zoom, **[ ]** for size, **B P F E** for tools, **1-0** for opacity, **Alt-click** to pick a color, **Shift-click** for straight lines, **Shift** for 45° lines, **Alt-drag** for handles, and **Enter**, **Esc** and **Delete** in the Pen tool. The brush shows its **outline**.

#### Effects
- **New effects** - **Cartoon**, **Channel Mixer**, **Color Adjust**, **Difference**, **Difference Matte**, **Outline**, **Vortex Blur** and **Warp Blur**.
- **Color Balance** - new **Keep Brightness** switch.
- **Chroma Key** - new **Luma** setting.
- **Drop Shadow** - new **Spread** setting.
- **Edge Detect** - new **Color** setting.
- **Polar Coordinates** - new **Radius** that cuts a hole in the middle.
- **Zoom Blur** - **Center**, **Strength** and **Adaptive**, like Alight Motion.
- **Squeeze** - **Strength** from **-2 to 2**.
- **Warp Blur** and **Turbulent Displace** - **Offset**, **Stretch**, **Seed** and **Parallax** shape the noise.
- **Style row** - effects that now match Alight Motion have a **Classic / Standard** style, so **saved projects keep their look**.
- **Recommended learns from you** - the three suggestions show up to **two effects you use most** on that kind of layer (video, photo, text, shape, group or 3D model) and at least **one that fits the layer**. Old habits fade, and effects you keep passing over make room for others.
- **New** lists only the effects added in **this version** (5.3 and its updates).

#### Timeline
- **Extend to the playhead** - with **several clips selected**, **Trim end** also **extends** the shorter clips, so **every selected clip ends at the playhead**. Videos stop at the end of their footage.

#### Import and projects
- **Fill / Fit / Stretch** - choose how a **picture or video fill** sits in its layer.
- **QR codes from pictures** - **Import project from link** reads **Alight Motion** and **AbsentMotion** QR codes from your **gallery** or **files**, **several at once**.
- **Alight Motion importer** tool - keeps **links** and **QR codes** in one place.

#### Export
- **Save video** - a finished **video**, **GIF** or **image** waits on **Ready to save**: **Save video**, **Save GIF** or **Save image** keeps it, **Share** sends it without saving, and **OK** leaves it unsaved. Exporting the **same unchanged project** with the same settings again shows it **at once**, without exporting it again.

#### Home and tools
- **Select several projects** - **hold** a project to select it, then tap others. **Select all**, **Duplicate** and **Delete** work on all of them at once; **Export** and **Send to another device** on one.
- **Customize tools** - choose which tools show, and in which order, on the **Tools page** and **inside a project**. Open it from **Settings > Tools** or the **sliders button** on the Tools page. It replaces **Change layout**.
- **Every tool in a project** - **Enhance Image**, the **Alight Motion importer** and **Send between devices** (which sends the open project) join the Tools in the **+** menu.

#### Community
- **Download counts** on every item.
- **Effects included** - see which effects a project or preset uses.
- **Details beside the picture** - creator, description, tags, effects and downloads, on wide screens; on phones they show **without scrolling**.

### Changed

#### Alight Motion import
- **156 effects look the same** as in Alight Motion, **measured frame by frame** against Alight Motion's own frames (5.2.3 had 67). **14** more are **very close**.
- **25 more Alight Motion effects** and effect versions from older projects now import.
- **Your saved projects keep their look** - only imported projects and newly added effects use the new looks.
- **Values past the usual range** - keyframes **above 1** or **below 0** are no longer cut off, so sweeps move like Alight Motion's.
- **Text** - lines, letter spacing, edges and centring are placed like **Alight Motion**.
- **Missing fonts** - opening or importing a project **downloads its Google Fonts** first.
- **Pictures** in imported projects are drawn **as sharp** as in Alight Motion.
- **Text Transform** - ranges count **letters without spaces**, with **Square**, **Smooth** and **Triangle** shapes, **Ease In**, **Ease Out**, **Overlap**, **Contour** and **anchor** pivots. **Random Order** shuffles like Alight Motion for **every seed**, and **Offset** imports as **Position**.
- **3D solids** - **Box**, **Cube**, **Cylinder**, **Ellipsoid** and **Tri-Prism** are **lit** like Alight Motion.
- **Blink 2** - keyframed **Frequency** keeps the right speed.
- **Box Blur** (first version) - same **spacing** and **edges**.
- **Channel Map HSV** - **Luminance** choice matches.
- **Checker** - **Amount 0** fills the layer, and the first version's **cell size** matches.
- **Chroma Key** (first version) - keys the **same colors**, with the same **feather**.
- **Color Tune** - **Gamma** acts on **shadows and highlights**, like Alight Motion.
- **Colorize** - **tint** comes across.
- **Contour** - **line width** and **Count** match.
- **Counter** - same **digits** and **placement**.
- **Contour Gradient** - reaches the same **width** past the layer.
- **Cube 2** - **edge size**, **light**, **ambient** and **face pictures** match.
- **Displacement Map 1, 2 and 3** and **Polar Displacement** - same **channels** and **offsets**.
- **Echo Keyframes** - same **copy count**, copies **before the clip** keep moving, and **Composite** comes across.
- **Edge Glow** - same **edges**, **smoothing**, **spread** and glow **outside the layer**.
- **Fade** - same **easing**.
- **Feather** - same **softness**.
- **Fill Behind** - background **zoom** and **edges** match.
- **Flip Layer** (first version) - same **size**.
- **Four-Color Gradient** - same **point positions** and **blend**.
- **Glow** - reaches as **far** as Alight Motion's.
- **Grid** and **Grid 2** - same **spacing**, **line width**, **smoothing** and **edges**.
- **Halftone Dots** and **Halftone Lines** - **closer** dot sizes and line widths.
- **Highlights & Shadows**, **Vibrance**, **RGB Separation** and **Sharpen** - same look.
- **Hollow Box** - same **face pictures**.
- **Inner Glow** - same **radius**, and no glow where a layer **meets the frame edge**.
- **Inner Pinch/Bulge** - same **radius** and **feather**.
- **Light Glow** - same **threshold** and **glow**.
- **Long Shadow** and **Radial Shadow** - same **shadow length**.
- **Luma Key 1, 2 and 3** - same **thresholds**.
- **Luma Stamper** - stamps the color **by brightness**, with the same **gamma**.
- **Matte Fringe** - no fringe where a layer **meets the frame edge**.
- **Mirror**, **Solid Color** and **Solid Matte** - **Alpha above 1** works like Alight Motion.
- **Motion Blur** - the blur is **centred** on the frame, and the **first frame** blurs too.
- **Move Along Path** - circle paths **start at 12 o'clock**.
- **Oscillate 2**, **Pulsate 1 and 2** and **Pulse Opacity 1 and 2** - same **speed**.
- **Page Curl** - the back shows the picture tinted by **Back Color**, closer **flap size**.
- **Palette Map** - **Alpha above 1** works like Alight Motion.
- **Parenting Helper** - same look.
- **Pinch/Bulge** - **Strength below -1** comes across.
- **Pixelate 1 and 2** - **square** cells, **Size 0** and **edge** cells match.
- **Plus**, **Star Polygon** and **Heart 2** - closer **size** and **position**.
- **Polar Coordinates** - same **mapping**.
- **Posterize** - same **levels**, and **Step Count below 1** works.
- **Random Displacement** - same **noise**, within 0.2 px.
- **Rays** - same **reach**, and short rays **glow** like Alight Motion's.
- **Repeat**, **Repeat Grid**, **Repeat Line**, **Repeat Radial** and **Repeat Along Path** - copies **stack** and **space out** the same way.
- **Replace Color** - same **threshold** and **feather**.
- **Ridges 1 and 2** - same **noise**.
- **Smooth Bevel** - same look.
- **Soft Glow** - same **glow** per color.
- **Spectral Map** - small **Repeat** values work.
- **Spin** - keyframed **RPM** matches.
- **Spin Blur** - same **edges**.
- **Squeeze** and **Zoom Blur** - same look.
- **Stretch** and **Stretch 2** - same **edge smear**, and **Scale 0** shows nothing.
- **Stretch Segment** - same **direction** and **distance**.
- **Stripes** - same **layout**, **width** and **smoothing**.
- **Swing** - same **speed** and **angle**.
- **Text Progress** - types the same **letters**, centred the same way.
- **Timecode** - shows **MM:SS:FF** like Alight Motion.
- **Transform** - same **edges** when zoomed out.
- **Tunnel** - stays inside the **layer's ellipse**, closer mapping.
- **Turbulence 1 and 2** - same **noise**, **intensity** and **stretch**.
- **Turbulent Displace** - same **noise**, and **X**, **Y** and **R** directions come across.
- **Vignette** - **Strength above 1** keeps darkening.
- **Voronoi** (first version) - same **cell size**.
- **Warp Blur** - same **noise field**.
- **Wave Warp** - same **damping**, **direction**, **spacing** and **edges**.
- **Wipe** - works in the **layer's box**, like Alight Motion.

#### Effect controls
- **One tap on a value** opens the **keypad**.
- **Sections** you can **fold**, like Alight Motion's, for Text Transform, 3D Light Source, Texture Mapping and more.
- **Choices as pills** - 2 or 3 choices show as **pills**, more as a **list**.
- **Sliders** where Alight Motion has sliders, with a **mark at the default**.
- **Ruler steps** per setting, and a **vertical swipe** on a ruler **scrolls the panel**.
- **Keyframe mark** on every row, and values show with Alight Motion's **degrees** and **decimals**.
- **Short panels** fit their last row.

#### Editor and app
- **Pill buttons** - every dialog button is a **pill**: the main action in color, the other in grey.
- **Video preview** shows the **full-quality frame** as soon as playback stops.
- **New text** uses **whole-pixel** letter spacing like Alight Motion. **Older projects** keep their layout.
- **Sharper pictures** when they're shown smaller than a quarter of their size.
- **Text on computers** has the **same weight** as on phones.
- **Community** - **shimmering placeholders** while the page loads, and **bigger cards** on wide windows.
- **Send between devices** keeps the phone's **Wi-Fi at full speed** during a transfer.

#### Computers
- **Windows** - installs from the old **.msi** move to the **faster updater** with the next update, and the **desktop shortcut stays** where you put it.
- **Windows** - when **Smart App Control** or an **antivirus** blocks part of AbsentMotion, the app names the **blocked file** and says **how to allow it**.
- **Mac** - the install help has the **Terminal command** that lets AbsentMotion open.

### Fixed

#### Editor
- **Puppet** applied to a group whose layer already has a puppet **keeps the whole picture**.
- **Bent layers** inside a group can reach **past its frame**.
- **Fill & Color** on a **group** colors the **whole group**.
- **Elements** with depth or tilt **no longer shift** when saved.
- **Group & Mask** takes the size of **what the mask shows**, so Tiles and other effects on the group leave **no gaps**.
- **Camera layer** view in the preview shows **only the project frame**, like the export.
- **Spin** and other speed effects move the **right way** before a layer starts.
- **Text Transform** letters sit on the **right baseline**.
- **Easing** works when two keyframes sit at the **same time**.
- **Drawing** - only the **left mouse button** draws, and **hold to straighten** follows the **vibration** setting.
- **Easing** page stays open when the playhead is **on a keyframe**. On the first keyframe it shows the curve to the next one.
- **Pose** remembers the **bone** and **axis** you picked when you come back from another page.

#### Effects
- **3D shapes** with Soft or Direct lighting no longer **glow** through see-through parts of the picture.
- **Outline** in Fill style draws its **color** at the right strength.
- **Echo** follows a **turned or scaled parent** before the layer starts.
- **Broken numbers** in an effect fall back to the **default** instead of spoiling the picture.

#### 3D models
- **Textures** are found when names have **quotes**, another **case** or a **copy number**.
- **.mtl files** are found the same way.
- **glTF** picked without its **.bin** says **which file is missing**.
- **Small textures** no longer **crash** on Android.
- **Picture names** that start with **digits** show **in full** in the Textures panel.

#### Send between devices and Community
- A **cancelled** connection is **closed** properly.
- The code **keeps working** when a phone **drops off** mid-connection.
- **Damaged replies** are reported instead of failing silently.
- **Cancelling** while a project is being **packed** leaves **no file** behind.
- **Community** download counts no longer go **empty** after leaving the tab.

#### Export
- A **failed save** at the end of an export is reported as an **error**.
- **Dither** strength can't go **too high**.
- Export **cleanup** no longer clashes with a running export.

#### Alight Motion import
- **Colorize** hue sweeps no longer **stand still**.
- **Flip Layer 3** only shines when its **light is on**.
- **Four-Color Gradient** keeps its **fourth point**.
- **Luma Key 3** High Threshold **1** keeps the **whole picture**.
- **Echo Keyframes** Composite and **Channel Remap (HSV)** options come across.
- **3D Cube** edges are the **right size**.
- **Lens Blur** is no longer **four times too strong**.
- **Spherize** (first version) no longer shrinks to **5%** at Z Distance 0.
- **Swing** no longer runs **twice as fast**.
- **Blink 2** no longer **doubles** its speed with keyframed Frequency.
- **Stretch Segment** no longer stretches the **wrong way**.
- **Move Along Path** circles no longer start at **3 o'clock**.
- **Directional Blur** keeps keyframed angles **past 360°**.
- **Vortex Blur** and **Chromatic Vortex Blur** keep their **centre** in the middle of the layer.
- **Cube** and **Hollow Box** faces no longer get **dark edges**.
- **Linked settings** in imported effects keep their **animation**.
- **Spin**, **Swing** and **Pulse** blur correctly **before a layer starts**.
- **Emoji** in animated imported text keep **clean edges**, and turn on their own in **Contour** mode.
- **Text on a path** places letters that are cut into pieces correctly.
- A **turned-off Text Transform** no longer changes how imported text is placed.

## [5.2.3] - 2026-11-21

### Added

- **Import projects from links**. Preview public Alight Motion shares before importing. On Android, open supported links with AbsentMotion or Alight Motion.
- **Recommended effects**. The Effects browser opens with three effects picked for the selected layer from its type, its animation, its current effects, Recents and Favorites. A green or blue screen in the clip or picture brings Chroma Key first, beat marks bring a Beat effect, a quick clip in a longer edit brings Shake, a cut-out picture brings Drop Shadow and dark footage brings Exposure.
- **Added effects**. Add effects other people made from an `.abmfx` pack or `.json` file in Effects > Added, on phones by opening or sharing the file and on computers by dropping it on the window. The app shows each effect on a sample picture and checks it works on the device first. Hold an added effect to remove it, and a project that uses an added effect you don't have says which one is missing. Added effects stay in Effects > Added, one folder per creator, with an added badge on every tile and in the layer's effect list. Hold an effect to show it in the category its creator chose (built-in categories only), or hold a creator folder to show it as its own category at the bottom of the Effects Library. Effects update by version, and Fun Pack by AbsentMotion is the first Community effect pack.
- **Community effects**. The Community tab has an Effects page next to Projects and Presets, and the Community guide explains how to make and share an effect.
- **Drag to reorder Tools**. In Change layout, hold a tool and drag it anywhere in the list, alongside the up and down arrows. The list scrolls when you drag near its top or bottom.

### Fixed

- **Alight Motion import** preserves more settings and animations in older projects, including Oscillate, Move Along Path, 360° Reorient Sphere and Blur.
- **Grouping** has a labeled Group button. Mask and Exclude are in Group options.
- **Preview audio** stops promptly when paused and stays in sync when the audio buffer runs low.
- **Effect colors** can open the full picker or sample the preview with the eyedropper.
- **Vignette** shades visible corners when a layer fills the project and extends beyond the frame.
- **Settings menu on Android**: the dark area beside it blocks every tap while the menu is open, opening or closing, and a tap there only closes it.

## [5.2.2] - 2026-11-20

### Added

- **Background updates on Android**. Leave the app or hide the card while it downloads. Notifications show progress with Cancel and tell you when the update is ready or stopped. When notifications are off, the card asks you to keep the app open.
- **Change layout in Tools**. Open More options to move tools up or down, reset the order, or save it for next time.
- **Share to AbsentMotion on Android**. Copy videos, pictures and sounds into the open project as top layers. From Home, choose an existing project, create one from the clip, or cancel.
- **Drive or folder on Android**. Link a USB drive, SD card or folder from Media or Audio to browse its clips alongside the phone's library.

### Changed

- **New project**. Tap the dark area around the sheet to close it.
- **Add menu**. A light shadow marks the edge of the tab row when more tabs are hidden.
- **Projects and Templates search**. The search bar opens and closes more smoothly, and matching results ease into place.
- **Timeline padlock**. The padlock column appears while clips are selected. Locked layers keep a small padlock on their thumbnails. Hold a locked clip to open its lock menu.

### Fixed

- **Community bundle and export fields** scroll above the keyboard as you type.
- **Current Frame as...** is selected when tapped. The arrow opens its page.
- An **import error** no longer reappears every time the app is reopened.

## [5.2.1] - 2026-11-20

### Added

- Layer locking: a padlock beside the eye on every timeline row (phone, tablet, computer and Samsung DeX). Tap locks or unlocks; holding it or right-clicking offers Lock layer, Lock timing only and Unlock. A locked layer cannot be selected or changed (canvas taps go to what is below it, its eye and padlock still work, a locked group cannot be opened); a timing-locked layer stays editable but cannot be moved, trimmed, split, stretched or nudged. The lock is saved with the project and survives undo, copy, duplicate and bundles.
- The Community tab asks before it connects to the internet and loads nothing until Connect is pressed.
- Right-click a button or menu item that has a keyboard shortcut (computer app and Samsung DeX) for Change shortcut, Remove shortcut and Reset to default; the Keyboard shortcuts page has a Remove button on each row, so a shortcut can be left empty.
- Ask before using mobile data (Settings > Projects and media, on by default): Community, AI model, caption model, font, video downloader and app update downloads ask once per app start before they use mobile data instead of Wi-Fi. Automatic update and link checks skip mobile data instead of asking.

### Changed

- Samsung DeX uses the same keyboard shortcuts as the computer app, read from the same list, including changed and removed ones (it used to know only a fixed set).
- Recording keyframes while playing also starts keyframes on a property that has none yet, for drags and slider changes; single taps still change the value without keyframes.

### Fixed

- A 3D model with Scatter Field could crash the app by running out of memory: the copies are now drawn from one picture of the model per frame, and copies at other times (Copy Delay) are limited to one million triangles.

## [5.2.0] - 2026-11-19

### Added

- Community library: the Community tab lists projects and presets shared by other people, with search (title, creator, tags), Newest first or A to Z sorting, tags to include or exclude, details and Add. Downloads are checked by size and SHA-256. Open form at the top of the tab links the Community submission form: upload the file to any file host (anonymous, Google Drive or MEGA) and send the link. Presets are shared from Templates > My Presets > Share to Community as a .absproj preset bundle (at most 20 MB) with a placeholder picture or video; My Presets cards show each preset on its placeholder, and opening a preset bundle adds it to My Presets.
- Community guidelines (COMMUNITY_GUIDELINES.md): the Community bundle checklist asks to confirm they are followed and opens them inside the app.
- AI Enhance for images (Realistic or Anime and cartoon, Max quality on computers with Real-ESRGAN x4plus models), Tools > Enhance Image, and a before/after slider after every image enhance.
- Record keyframes while playing, with a count-in and playback speed (hold Play), Overwrite while recording (hold the keyframe button), keyframe thinning and one undo step per recording.
- Font search also lists Google Fonts families when online, under a Google Fonts divider, with source chips (All, Imported, App, Device, Google Fonts); device fonts are listed too. A "Find more fonts on DaFont" link opens dafont.com in the browser, and Import Fonts accepts .zip files (up to 50 fonts and 50 MB).
- Computer layout in Samsung DeX (experimental): the editor turns the desktop layout on by itself in Samsung DeX and off when DeX ends, with the project kept open; there is no setting.
- Phones open, preview and export AVI (DivX and Xvid), WMV and videos with AC-3 sound they could not play, plus 10-bit and 4:2:2 H.264, ProRes and DNxHD, through FFamp's own decoders. Files the phone plays stay on the phone's reader.
- FFamp media reader (experimental) on every platform: the first start asks once whether to try it, with this device's measured speed-ups; Settings > Playback > Media reader changes it.
- Double-tap to type: one tap on a number selects it for the ruler or slider, a double tap (double-click on computers) opens typing with the whole value selected.
- Theme color and App font in Settings > Editor: an accent colour (presets or your own) and a font for the whole app.
- Current Frame as... has its own export screen on phones and its own window on computers.
- Text Blips: a Skeleton voice and Custom Sound, which plays a sound file you pick.
- Keyboard shortcuts page in desktop Settings: every action with its keys, rebinding with conflict checks and reset; new shortcuts copy and paste keyframes, jump between keyframes and add bookmarks.
- Hold an icon-only button on a phone to see its name; letting go does not press it.
- Undo and redo notes name the change (for example "Undid: Add Blur"); tap a note to go to the change, swipe it down to close it.

### Changed

- Alight Motion import: many more effects look the same as or very close to Alight Motion (blurs, tiles and patterns, colour and key effects, shadows and glows, warps, wipes, easing curves). New settings added to match always go after the existing ones.
- Landscape tablets and computers: pages and tools use the width and open as wide cards instead of a phone-shaped frame.
- Export with AI Smooth shows Downloading AI Smooth as its own stage, and the time left follows the measured speed.
- Computers: right-click a finished export's preview to copy the file; the export time estimate says Calculating until it knows; a timeline selection box keeps its corner while the timeline scrolls.
- Phones get the big Create project button in New project.
- GIF, Image Sequence and Current Frame as... export pages work like Video: the resolution dropdown, the frame rate slider and an Export bar that starts the export from the page.
- Every favourite star (fonts, effects, effect presets) is one shared button with a short pop when it turns on, in a new Favorite theme colour.
- A left-to-right swipe anywhere in the font list opens its side menu, and a swipe back closes it.
- Every new text and help page is translated into all 24 languages.

### Fixed

#### Editor

- Add to project from the Tools tab no longer crashes when storage is low.
- Flip, fit and paste style during playback no longer cut keyframes down to one key.
- Live recording keeps writing the held finger position after playback loops.
- Edit, undo and redo no longer move the playhead back one frame at the end.
- Frame screenshots of time-remapped clips now capture the right video frame.
- Grouping a layer after a screenshot no longer deletes the screenshot from your gallery.
- Pasted keyframes are no longer silently dropped on other layers.
- Live recording of Center and Stretch effect points now keeps the shape of the motion.
- Flip, link to parent and fit during playback now keep all their keyframe changes.
- Dragging the pivot no longer turns one bounce into two full bounces.
- Parenting and grouping no longer make a child's scale or rotation jump with Parenting Helper.
- Retiming a clip very fast no longer crashes when its sound plays or exports.
- Duplicating layers now selects the right layer when ids clash.
- Snapping to a playhead parked at the end now lands on the right frame.
- Layer thumbnails in the thumbnail picker now sit on the correct side in right-to-left languages.
- Timeline thumbnails of portrait 4K videos are no longer sideways.
- The Animate button no longer shifts left, and Back no longer seems to do nothing.
- Editing points or drawings now jumps to the first frame where the layer shows.
- Dragging a value ruler after undo or redo no longer crashes the app.
- Palette pictures up to 64 MB now load, and oversized SVGs show a clear message.
- Pasting layers copied from a deleted project no longer brings missing media.
- Adding a linked element that fails to load no longer crashes the app.
- Trimming a layer no longer moves its In/Out animation effect to the bottom.
- Groups slowed below 0.05x are no longer shortened when a project loads.
- Adding a speed key in a stepped segment no longer rewrites both halves as linear.
- Layer menu actions like Mark beats now finish even if the menu closes.
- Swap media file on a missing video now opens a video-only picker.
- A Preview drag no longer edits the wrong layer if the selection changes.
- Solo mode can now be turned off after deselecting.
- Pressing Done on the camera zoom keypad no longer changes the view angle.
- A media fill no longer accepts a video of unknown length that shows only one frame.
- Dragging an effect point now follows your finger on scaled or rotated layers.
- Typing a size on a layer at scale 0 no longer sets a guessed size.
- The speed curve keypad now accepts only the 0.1x to 10x range the graph covers.
- Pressing Done on the speed keypad without typing no longer re-times the clip.
- Unanswered crash-recovery edits are now kept in Version history.
- Preview copies of 1080p and 720p videos no longer look blurry.
- Closing the editor now returns to Home without waiting for the thumbnail.
- The Settings help page now covers System default, Theme color and App font.
- Swiping right on New Project no longer opens Settings on top of it.
- Settings model and downloader rows now refresh after downloads from Tools.
- Template preview now plays with sound.
- First-run setup now resumes at the Look step if it was stopped earlier.
- The Undo tutorial step no longer finishes after a single Undo.
- The Keyframes tutorial step now always finishes.
- Restarting the tutorial no longer leaves an extra practice project.
- Layer placement now uses the project's frame rate instead of 30 fps.
- Urdu text is now laid out right-to-left across the app.
- The camera frame outline and selection now follow camera tilt.
- A cropped layer's frame and handles now follow the cropped part.
- Moving a keyed pivot between keys with Bounce, Elastic, Steps or a reversed easing no longer crashes.
- Linking or unlinking a layer with locked or weighted rotation under a tilted parent keeps it in place.
- Round corners now stop where the corners are fully round, and the ruler stops there too.
- Every favourite star (fonts, effects, effect presets) is the same button now, with a short pop and a brighter yellow.
- Playback no longer stops one frame before the end.
- 1080p and 720p videos with far-apart key frames no longer get a low-resolution preview copy.

#### Effects and rendering

- Video Feedback trails now show past frames, and 'Use layer transform' works when off.
- Duplicating an effect no longer moves its value links to the wrong copy.
- Keyframed on/off switches now flip at the same moment in the panel and the render.
- Animated switches no longer show one state in the panel and render the other.
- 3D models with small whole-number positions no longer look wrongly scaled.
- One double-sided part no longer turns off back-face hiding for the whole 3D model.
- Flip Layer's selection outline now follows the new Pivot settings.
- Layers with effects inside a Puppet group are no longer cut off at the group's edge.
- Some effect settings now show the same default that actually renders.
- Copy Background's spill now follows the current time, not time zero.
- Copy Background with Frame Rate no longer shows a stale or blank backdrop.
- Copy Background with Frame Rate now keeps its hold with animated text spacing or weight.
- Adding a keyframe to a palette no longer turns the palette black and white.
- Effects shared with Apply to children now stay in time with their parent.
- A clipping-mask base with Copy Background or Glass now sees the content below.
- Tiles on a turned layer now meet without gaps.
- Echo and Video Feedback copies now blend together instead of one by one.
- 3D models are now lit on the correct side when mirrored or stretched.
- Point lights now light Scatter and Fracture pieces where they really are.
- A border on a keyed layer now outlines the subject, not a rectangle.
- Copy Background with Frame Rate now updates when a video or 3D model finishes opening after the first preview.
- Frame Rate on Copy Background now holds the copied background for each step, so effects after it work.

#### Text and fonts

- Picking a font from the text editor's quick strip now downloads it.
- Text with vertical tab or form feed breaks no longer runs lines together.
- Closing font search no longer leaves a hidden source filter hiding most fonts.
- Importing a very large font zip no longer crashes the app.
- Font zips with unusual file names no longer fail as Corrupted.
- Japanese and Chinese font zips are no longer rejected as Corrupted.
- Chinese system fonts now use the right face instead of Japanese glyphs.
- App font now switches back to Default after its imported font is deleted.
- Right-to-left text on computers now starts in the right direction.
- Text on a path on computers no longer wraps at the wrap width.
- Japanese text no longer shows Chinese letter shapes.
- The font list on computers now finds fonts installed for one user.
- Punctuation Pause now works after Chinese and Japanese punctuation.
- A downloaded font now goes to the text layer it was picked for.
- In the font list, a left-to-right swipe anywhere opens the side menu, and a swipe back closes it.
- Many spaces in a row now wrap to the next line instead of running past the text box.

#### Audio

- The waveform now repeats on looping audio layers.
- Seeking past the end of audio no longer marks the sound as failed.
- A sound file that fails to decode no longer ruins the whole export.
- Text Blips typing now matches the sound's speed and pause settings.
- Text Blips edits no longer wait while another layer's sound renders.
- The waveform is no longer shifted when audio does not start at zero.
- Surround sound mixed down to 3 to 8 channels no longer loses speakers.
- Surround channels are no longer dropped when mixing down to fewer speakers.
- Sound with over 8 channels mixed down no longer keeps only the first channels.
- Speech speed and pitch changes now give the right length.
- Extreme speech speed or pitch no longer cuts off the voice or adds silence.
- Converted speech no longer clips when mixed down to mono or stereo.
- Very short sound clips are now sped up instead of being cut.
- Sound that starts near 0 ms no longer waits 1.5 seconds for the first audio to decode.
- 24-bit sound in camera videos is no longer missing on phones.

#### Export and sharing

- A stuck export is no longer retried in a way that hangs again.
- Project packages and Community bundles no longer reveal your folder names.
- Cancelling an export while its file opens no longer leaves a stray file behind.
- The export time estimate is no longer badly wrong on the second export.
- Export errors now show their real message instead of 'video encoder is busy'.
- Export time estimates are now measured again when the frame rate changes.
- A failed GIF size estimate now shows Unknown instead of Calculating forever.
- The Quality bitrate now shows what the encoder really uses.
- Failed exports show the real error, and memory errors no longer name the wrong size.
- The export time estimate no longer turns to nonsense on large projects.
- Closing the app again no longer breaks a running background export.
- An interrupted bundle save no longer leaves a broken project file behind.
- Export now uses a backup route instead of failing or turning black.
- Export smoothing and datamosh now use the right video frames.
- A stalled video encoder no longer freezes the app; the export fails cleanly.
- A big GIF export no longer deletes a good GIF when the check runs out of memory.
- Video and audio exports on computers now check disk space correctly before starting.
- GIF, Image Sequence and Current Frame as... now work like Video: a resolution list, a frame rate slider and Export right on the page.
- An export whose encoder stopped in the background now tries another encoder instead of failing.

#### Projects and import

- Imported Alight keyframes on combined position-style settings are no longer lost.
- Alight multiply and subtract opacity now also applies to layers inside groups.
- Old projects no longer get effect settings doubled or dropped when opened.
- Alight projects imported earlier with old Tiles scaling now show the re-import tip.
- Projects made in AbsentMotion no longer get Alight colour handling or the re-import tip.
- A damaged SVG file no longer hangs or crashes the app.
- Importing a large non-project file now says what the file really is.
- Media names differing only in capitals no longer overwrite each other.
- A damaged project package no longer uses up memory while importing.
- Running out of memory during import now says so instead of blaming a 16 MB limit.
- Weekly backup no longer fails when a project file changes while it runs.
- Duplicating or deleting a project no longer loses some of its media.
- Linked width and height now keep the aspect ratio when a side exceeds 4096.
- Version history dates now follow your language.
- Sort by name now follows your language, so accented titles sort correctly.
- An oversized project thumbnail no longer crashes Home on every launch.
- The project card now shows the real storage size.
- A deeply nested theme file no longer crashes the app.
- Importing a 3D model as a project now says it is a model and where to add it.
- Videos the phone has no decoder for, such as AV1, are flagged on import, and export says why.

#### Community

- The Community 20 MB size check now counts imported fonts.
- The default Community title is now cut to the allowed 80 characters.
- A bundle just over 20 MB no longer shows as '20MB / 20 MB'.
- Downloading the same item twice at once no longer breaks the file.
- A community list with duplicate entries no longer crashes the app.
- Two simultaneous downloads of one item no longer report a damaged file.
- Community presets now get the same media safety check as other imports.
- Some community presets that failed to open now open correctly.
- Audio, camera and light presets now show a placeholder icon, not an empty picture.
- Community thumbnails that failed once now retry instead of staying blank.
- An empty library no longer shows a useless Clear search button.
- A very tall, thin thumbnail no longer crashes the Community card.
- The privacy notice now mentions the Community tab and what it loads.
- The Newest sort now orders items added on the same day correctly.
- The share card at the top has Don't show, and a link to the form now sits under the empty text and at the end of the lists.

#### Tools

- Add to project now works for pictures compressed with the Compress tool.
- Fast trim now works on WebM and MKV videos.
- The Enhance Image picker no longer offers videos.
- Enhance Image on Home now shows the same risk prompt as the editor.
- Multi-item links now highlight Video and audio in the greyed save modes.
- Compress on computers now keeps the colour information of HDR and older videos.
- Compress errors on computers no longer show a doubled 'Could not compress'.
- A GIF made in the Tools no longer leaves an unused copy in the project.
- Enhance Image now only offers pictures to pick.

#### AI tools

- Before/after Compare no longer crashes on a bad image address in an AI mark.
- Compare zoom now resets properly after zooming in and back out.
- Photo cut-out subject taps now apply correctly to rotated pictures.
- Video background removal now downloads and checks its model first.
- The cut-out model can now be deleted on Compatible and OpenGL.
- Photo cut-out taps now land correctly on rotated photos.
- Chinese, Japanese and Thai captions no longer disappear mid-sentence.
- AI video jobs, AI Smooth and Reverse now open AVI and WMV clips.
- On OpenGL phones, GPU AI is no longer switched off by mistake.
- AI tools on computers now check the real memory of the computer.
- Remove Background no longer runs underneath a newer Enhance and loses it.
- AI Smooth on a looped clip no longer drops the start of the loop.
- Smoothing a looping clip now renders the right range and keeps the loop.
- AI Smooth now smooths short slowdowns inside long clips.
- Cut-out on computers now handles turned photos the way the phone does.
- Enhance on transparent images no longer leaves dark, jagged edges.
- Cancelling an AI model download no longer shows 'download failed'.

#### Updates

- The privacy notes now mention the README request made at launch.
- The changelog dot no longer stays lit after a patch update.
- When security software blocks the update helper, the report dialog can be closed again.
- Pressing Later while the update starts no longer quits and installs anyway.
- The update dialog's Quit button no longer freezes the window for seconds.

#### Phone and tablet

- Closing Presets no longer leaves the editor preview frozen.
- Image sequence export now stops before storage is full.
- Picked files no longer go missing after a restart when copying fails.
- Touch and stylus now work on the timeline in Samsung DeX.
- The Add panel search on wide tablets no longer hides under the keyboard.
- In DeX the Media and Audio library now asks for permission instead of staying empty.
- Narrow DeX windows no longer collapse the preview.
- Ctrl+1 and Ctrl+2 panel shortcuts in DeX now keep working after focus changes.
- Android no longer starts a second export while one is still running.
- The phone editor now avoids display cutouts in landscape multi-window.
- Closing Try a preset no longer leaves the editor preview frozen.
- Semi-transparent pixels in preview frames no longer look too bright.
- An unrelated crash while loading media no longer marks the GPU as crashed.
- Closing the preview no longer freezes the app while effects compile.
- A passing start failure no longer shows Unsupported phone on every project.
- A failed preview render no longer removes the cached frame too early.
- Editor shortcuts no longer act on the project behind open dialogs.
- Space now stops a count-in before recording.
- Held keys like volume and Tab now work again in the computer layout.
- The Z Flip cover screen no longer switches on the computer layout.
- Exports now work on graphics chips that lack a recording feature.
- Vorbis audio now plays in FFamp mode on Android.
- New project on phones slides up from the bottom again, with the pill Create project button.
- The Project / Asset switch in New project slides between its two sides.
- Saved sizes in New project are split into pages, so swiping back to the first page is easy.
- The Frame Rate and Background lists in New project open at the chosen value.
- The About logo is centred and larger.

#### All computers

- The Add Keyframe shortcut in Crop now keys all four sides.
- Choosing a thumbnail no longer allows real edits to the project.
- Closing the thumbnail picker no longer breaks dropping media onto the timeline.
- Scrolling the wheel during a clip drag or trim no longer shifts the clip.
- Shift+click range on the timeline now starts from your current selection.
- Closing the effect browser by clicking outside no longer sends the next Add effect astray.
- Cmd+1 and Cmd+2 no longer toggle panels behind open dialogs.
- Pressing Escape during a Frame or XML export no longer hides the result.
- Exporting Current Frame from the project list now previews the frame that gets saved.
- The XML export option no longer describes itself as a Project Bundle.
- Typed project and layer names are now saved before closing or quitting.
- The Templates tab now shows a template saved from Export right away.
- A custom size field no longer undoes the aspect preset you just clicked.
- The grid scrollbar thumb no longer jumps when a full-width header row is shown.
- The Downloader's Saved to message now names the right folder on Windows and Linux.
- The Downloader now ignores your own yt-dlp settings files.
- A quick second camera action no longer leaves the camera switched on.
- Pressing Enter twice while stopping a recording no longer damages the video.
- Arabic, Persian and Urdu now lay out right to left.
- Pressing Esc while typing a layer name no longer renames it.
- A video check with lots of error output no longer hangs the app.
- AVI, WMV and ASF videos from the picker now open in Tools.
- Choosing Keep exporting after Install and restart no longer drops the update.
- Open with now keeps the import report instead of skipping it.
- Arabic, Persian and Urdu on computers now switch the layout to right-to-left.
- Opening a file that is not media no longer leaves an empty project behind.
- Dropping a project file on an open editor now imports the project instead of adding a picture.
- Cancelling AI Smooth no longer leaves the preview without pictures or videos.
- Dates and numbers on computers now follow the chosen app language.
- Ctrl+N no longer opens a hidden New Project dialog under the Template editor.
- Previous Keyframe no longer jumps to a negative frame or to keys outside the clip.
- Pressing Ctrl on a Mac no longer runs the plain shortcut.
- Undo and Redo shortcuts now work while you edit points.
- The bookmark shortcut no longer removes an existing bookmark.
- Playback > Go to End now parks on the last frame like the End key.
- The mouse wheel over an effect ruler no longer changes the value while you scroll.
- Help tables with an empty header cell now show headings in the right columns.
- A failed Duplicate no longer reports success.
- The language page now keeps the restart hint until you restart.
- Effect names and guidelines now follow the system language when none was chosen.
- Arrow keys move the right way in the Projects and Assets grids in right-to-left languages.
- The full-screen viewer's scrubber seeks where you click in right-to-left languages.
- The export window can be closed with X or a click outside after the export finishes.

#### Windows

- yt-dlp updates now install on Windows instead of downloading again every time.
- The daily yt-dlp update now installs on Windows.
- Updating no longer force-closes a minimized AbsentMotion window.
- Some videos no longer show wrong colours at the bottom on Windows.
- The bottom of the colour wheel's degrees is no longer cut off.

#### Mac

- The Mac camera now finds its supported modes on cameras with many modes.
- Exports of RGB sources such as PNG sequences no longer have wrong colours on Mac.
- High 10, 4:2:2 and 4:4:4 H.264 videos now play on Mac.
- Professional H.264 videos that failed to open on Mac now decode.
- The Mac video decoder now recovers after an error instead of losing every later frame.
- Multi-cut MP4 videos no longer show wrong frames after a jump on Mac.
- Raw YUV9 and 4:1:1 videos now show a picture on Mac instead of empty frames.
- MP1 audio files now play on Mac.
- Microphones and external cameras are now found on macOS 11 to 13.

#### Linux

- Updating the AppImage no longer force-closes a copy you reopened.
- AV1 videos with film grain now show their grain on Linux.

#### Windows and Linux

- A damaged GIF with oversized frames no longer uses huge amounts of memory.
- Colours are now right when scaling full-range or BT.601 video on Windows and Linux.
- Videos starting with undecodable pictures no longer fail to compress or reverse.
- Interlaced H.264 videos now open quickly instead of reading the whole file first.

#### Video and sound reading (FFamp)

- Scrubbing Xvid, DivX and MPEG-4 videos no longer restarts decoding on every frame.
- Reversing a clip no longer crashes the app when it closes.
- 16-bit big-endian sound (AIFF, MOV, CAF) no longer plays as loud noise.
- Stepping to the next video frame no longer repeats the work of decoding the whole group.
- Thumbnails of rotated videos read by FFamp now appear upright.
- Videos read by FFamp now use the right colour standard.
- Videos with larger pictures than declared no longer lose frames in FFamp.
- Surround sound downmix now follows the file's real speaker layout.
- Thumbnails now show the closest frame, and the last one is no longer blank.
- AIFF, CAF and WMA sound files can now be imported.
- A video with no decoder now shows a clear message and warns on import.
- Scrubbing FFamp-read videos no longer queues long decodes.
- Scrubbing videos read by FFamp no longer piles up slow decodes.
- Some videos on computers no longer show a frame too early.
- Large video frames no longer silently vanish in FFamp mode.
- A damaged H.264 AVI file no longer freezes the app.
- A damaged AVI file no longer hangs the reader.
- A damaged H.264 AVI file no longer slows to a crawl.
- A damaged AVI with E-AC-3 sound no longer grows without limit.
- A damaged MP4 file no longer uses huge amounts of memory.
- A damaged MP4 with many edits no longer freezes or runs out of memory.
- QuickTime compressed audio, including MP3, now plays properly.
- FFV1 video in MOV files now opens and plays.
- A damaged fragmented MP4 no longer uses gigabytes of memory.
- A damaged MP4 with overlapping samples no longer hangs on open.
- Track length is now correct for late-starting MKV and fragmented MP4 files.
- Key frames in fragmented MP4 files are now found like FFmpeg does.
- Fragmented MP4 files with edits no longer get shifted timing on the wrong samples.
- Fragmented MP4 files without timestamps no longer restart at zero.
- Empty gaps in the middle of an MP4 edit list are now kept.
- Sound in some WMA/ASF files no longer comes out scrambled.
- Opening ASF files with variable-size packets is no longer extremely slow.
- Scaled videos with untagged colour info no longer show garbled colours.
- Video timing is now right for Matroska, WebM, fragmented MP4 and IVF files.
- Picture and sound no longer drift apart in files that do not start at zero.
- A damaged Matroska file with huge track lists no longer freezes the app.
- Opus sound in Matroska now starts cleanly when the file does not start at zero.
- Output files with capital letters in the extension, like .MKV, now use the right format.
- 8-bit sound copied into MKV, MP4 and MOV no longer plays as noise.
- WebM now refuses an unsupported codec at once, not after the whole export.
- Big-endian float sound can now be saved into .mka and .mkv files.
- .MKV, .WEBM and .MOV outputs with capital letters now get the correct container.
- High sample-rate sound in MP4 and MOV files is no longer refused when read back.
- 8-bit sound in MP4, MOV and Matroska keeps its correct sign.
- MP4 and MOV files now keep the creation date of the source.
- Interlaced ProRes videos are no longer labelled as progressive.
- H.264 and HEVC files with in-stream settings keep their original tag when rewritten.
- A damaged FLAC file no longer freezes the app for minutes.
- Compressed videos now keep picture and sound in step when trimmed.
- H.264 transport streams with open GOPs no longer lose their video.
- Transport streams with timestamp jumps no longer play frames out of order.
- H.264 transport streams now recover quickly after lost data instead of dropping video.
- A failed export no longer deletes an existing file at the same name.
- IVF videos with two frames at the same time now open.
- A cancelled or failed audio export no longer destroys the existing file.
- A crafted video file no longer makes opening it take seconds.
- Tools can no longer overwrite the source file through a different path spelling.
- A damaged DNxHD video no longer crashes the app.
- Interlaced DNxHR videos no longer show black or green lines.
- A damaged FFV1 video no longer crashes the app.
- Grayscale FFV1 videos now show the correct brightness range.
- Some FFV1 videos that were refused now open.
- FFV1 4:4:4 videos no longer show wrong colours.
- A damaged H.264 video no longer crashes the app.
- H.264 videos no longer show wrong frames after an intra-only first slice.
- 7.1 AAC sound no longer swaps its side and back speakers after a jump.
- Long HE-AAC files now use about half the memory.
- AC-3 sound that changes its channels no longer drops or silences them.
- E-AC-3 extra channels are now mixed in instead of being lost.
- Long gaps in sound no longer use gigabytes of memory.
- Transport stream sound now stays in sync after lost data.
- A damaged Cinepak video no longer crashes the app.
- Bright pixels in some H.264 videos no longer turn black on arm64.
- Film grain no longer shows a blank picture in some H.264 videos.
- A damaged 16-bit palette PNG no longer crashes the app.
- A PNG with a huge width is now refused instead of crashing.
- A damaged WMA Lossless file no longer uses gigabytes of memory.
- ProRes 4444 videos no longer show wrong colours.
- A damaged ProRes video no longer crashes the app.
- Damaged VC-1 videos now hide errors instead of showing garbage.
- VC-1 videos now report their colour information.
- VC-1 videos no longer deliver B-frames late or out of order.
- H.263+ videos no longer break after one PB picture.
- Damaged high-bit-depth H.264 videos no longer show wrongly mended blocks.
- Damaged H.264 videos no longer show glitches from threads reading too early.
- Large HapM frames now decode instead of failing.
- Damaged WMV2 pictures now hide the unfinished rows.
- YUV9 videos now show colours in the right rows.
- A damaged video with zero width no longer crashes the app.
- Damaged VC-1 B-pictures no longer show garbled slices.
- VC-1 videos with edge quantiser settings now decode correctly.
- WMV3 range-reduced videos no longer look washed out.
- A damaged WavPack file no longer crashes the app.
- AAC sound with a long gap after its first packet no longer fills memory with silence on Windows.

#### Developer tools

- One failed translation no longer throws away the whole run.
- Writing translations no longer crashes when a key changes between text and plurals.
- Community items with a missing or bad picture are now rejected before publishing.
- Damaged or oversized Community bundles are now rejected safely.
- Multi-layer presets are no longer accepted into Community.
- One damaged file no longer stops a whole Community batch.
- The full check no longer reports success when some checks were skipped.
- Two checks can no longer take the build lock at the same time.
- A leftover build lock no longer makes the check wait forever.
- Stopping the check with Ctrl-C now stops its background jobs too.
- The translation check now catches broken percent signs in strings.
- The privacy scan now also checks commits not yet pushed.
- Frame rates like 29.97 are no longer cut to 29 in the command line tools.
- Windows output paths in folders with a dot now get the right extension.
- Two reader types with the same name no longer clash in the build.

## [5.1.1] - 2026-11-14

### Added

- Named custom sizes: save as many sizes as you like, each with a name, on a second page of the size row in New project (swipe left). The tiles take the size's shape, long names scroll, and holding a tile offers Rename and Delete.
- Use anyway: features this phone may struggle with (Optical flow High, AI Smooth, AI Enhance, AI background removal 2.0 and export sizes the phone's encoder does not list) show a warning icon instead of being locked, and ask before each use.
- Snapping when you drag a layer on the preview: it holds at the centre lines and at other layers' centres and edges, with guide lines and a small vibration, like the Move pad.

### Changed

- Crop: a small crop box is zoomed in so it is easy to adjust, and the view fits it again after each drag.
- Alight Motion import: Mirror, Matte Choker, Luma Stamper and Glow look the same as in Alight Motion, and Chromatic Zoom Blur looks very close. Projects made in AbsentMotion look as before.
- Phones with an Adreno 735 graphics chip, such as the Poco F6, start with OpenGL, since effects, text and the selection outline drew wrongly with Vulkan.
- The app's links point to absentmotion.com.
- Compatible version: the About page says which features ask first, instead of calling them left out.

### Fixed

- Motion Blur on a layer with another effect cut the layer to the bottom right when playback stopped and in exports.
- The finished export screen stayed white in Dark mode.
- The selection outline stayed flat on layers turned by Raster Extrude or Flip Layer in 3D.

## [5.1.0] - 2026-11-12

### Added

- Light and Dark mode: Select your theme on the first start or later in Settings > Editor. System default follows the device live and waits for a running export or job to finish. Dark covers the whole app, Home included, and switching fades smoothly.
- Vibration: the app's haptics also work when the system touch vibration is off, while Vibration is on in AbsentMotion.
- Time Remap: a Remap tab in Speed keyframes which moment of a video shows, to speed it up, freeze it or play it backwards inside one layer.
- Tools inside the editor: a Tools tab in the + menu, and Tools in the left rail on a computer. Compress, Trim, Extract Audio, Make a GIF and the Video Downloader add their result to the project.
- Video Downloader on Windows, Mac and Linux, with a one-time add-on.
- Video Feedback Frame by Frame: each copy shows the layer one frame earlier, videos included, so movement leaves trails. On a video the nearest copies stay sharp, and phones with more memory keep every copy sharp.
- Page Curl and Scatter Repeat effects. Page Curl's Follow Shape curls a round picture or a 3D model from its own edge.
- 3D Text: a tile in the 3D and Text effects that adds Raster Extrude, which turns a text layer into solid 3D letters.
- Turn 3D effects on the preview: with a 3D effect's settings open, drag on the preview to turn it; two fingers twist it.
- Effect previews: an effect's page opens with a big, sharp preview. Moving effect pictures play at 30 fps (12 fps on the Compatible build).
- Community bundle: Project Bundle in Export adds a title, a thumbnail picked with Choose thumbnail, a description, tags and a name. The thumbnail is square, with smooth scrubbing and a square you move and resize on the picture.
- Bundles folder: pick a folder once and every project bundle saves there.
- Hold to pick: hold the three dots on a project, slide to an entry and let go to run it, with a small vibration for each entry you pass.
- Bottom bar: a soft highlight when a tab is pressed, and hold and slide along the bar to switch tabs, with a small vibration for each one.
- Community tab on Home. Sharing arrives in 5.2.
- Favourites show a filled star with a small pop.
- Alight Motion import: Page Curl, Scatter Repeat and Ring (as Torus).

### Changed

- Tablet layout: in landscape the editor has tool rails on both sides and a side panel that stays in place, Home has a bottom bar with a round +, and New project and Export open in a centred card with a pill button. In portrait the same rails run full height with a fixed panel under the timeline. Move & Transform shows the numbers on top and a bigger pad, dial or ruler below, Media and Audio are grids, Home shows one row per project, and pressed buttons get a soft rounded highlight.
- Smaller app: the Android download went from about 80 MB to 62 MB (Compatible: 72 MB to 52 MB). Other languages unpack when picked, and background removal downloads on first use.
- Simpler Transform: four tabs on phones and tablets, double-tap Move for the pivot, and 3D rotation in the Rotate menu.
- Smoother menus: the effects panel and effect pages slide in from the right, and projects open with a soft zoom.
- Clearer Settings: an open section is shaded.
- Font filters: a cleaner filter menu in the font picker, with a Popular / Alphabetical switch and a proper language menu.
- 3D models: the 3D effects category is greyed out for them, since a model is already 3D.
- Updates on a computer: they install themselves after the download and close every open window first, and the next start says what went wrong when one fails.
- Windows updates: AbsentMotion's own installer, about 12 times faster than the Windows installer (about 11 seconds instead of about 2 minutes 15 seconds).
- Windows installer: keeps files you added to AbsentMotion's folder, names the program that keeps a file in use, and asks before an older version replaces a newer one.
- Mac updates: the app replaces itself.
- Optical flow frame interpolation: sharper at every level.

- Faster video export on a computer: the next frame is drawn while the graphics card finishes the last one (about 90 to 104 fps with effects on an M4 Mac), with the same frames.
- GIF export uses every core: a 1080x1920 GIF of 75 frames takes about 0.7 s instead of 3.1 s, with the same file.
- Image sequence export uses several cores: PNG about 4.5 times faster and JPEG about 3 times.
- Particles, ASCII, Palette Map and Retro Dither keep their fixed pictures on the graphics chip on Android, so they draw about 12% faster.
- Moving or scaling many selected layers is about 3.5 times faster, and the editor redraws less while a project plays.
- Android: projects open faster and stutter less right after the start, with a start-up profile for the app's own code.
- Mac: the app starts faster with Java's start-up class archive, like on Windows and Linux.
- Windows: PCs without a graphics-card video encoder export H.264 and HEVC with x264 and x265, for better quality than Windows' basic encoder.
- Computers: picker thumbnails read big photos at a reduced size, HDR, 10-bit and see-through video frames convert on several cores, and a video the graphics card refuses opens faster.
- Computers without a graphics card: frame blending, optical flow, motion-blur smoothing and Datamosh run on the processor instead of failing.
- Phones narrower than 384 dp: layer panels and the colour picker are drawn slightly smaller so they fit.
- Project checks and release tools: the quick check takes about 30 s instead of 140 s, the privacy check is stricter, and the release script stops early on a missing key or a failing check.

### Fixed

- Pre-render: pressing the button while it renders stops it and keeps the frames, and pressing again continues from where it stopped. A finished pre-render turns off and on with a press, a red (outdated) one clears with a press instead of staying red, and a copy Android frees in the background no longer leaves the button red.
- Export button on tablets: no white rectangle on the round orange button when pressed.
- Selected layer: tapping or clicking it again drops its focus.
- Parenting: a child stays attached when the parent turns or is resized, also when the pivots differ.
- Fonts on a computer: fonts with a space in their name can be used and deleted again, and Google Fonts such as Open Sans no longer turn into Roboto.
- Text: the preview can zoom out while a text box is edited.
- Tools: after a tool on the second page of a layer's tools, the tools stay on that page.
- Tools in the + menu: closing a tool returns to the Tools tab, and Back closes the tool instead of the project.
- Block Noise, Block Dissolve, Dissolve, Static, Glitch, Beat Glitch and Datamosh: the block size changes each block in place instead of zooming the whole pattern.
- Frame Interpolation: thin lines no longer break up into patterns with Optical flow at Medium.
- Smoothing a Time Remap clip: a trimmed clip keeps the right moments, and a clip played backwards keeps its motion blur.
- Rigged 3D models: a rigged GLB model now comes in standing up; models already in projects keep their orientation.
- 3D models with effects: Pixelate, Vignette and other effects no longer cut off or darken a turned 3D model.
- Effects panel: no white flash when it opens.
- Motion Blur: fast moves blur into one smooth streak that trails behind the layer, and the paused preview shows the real blur.
- Press highlights on phones: rounded buttons, tabs, switches and chips light up in their own shape instead of a white rectangle.
- Small phones (360 dp): every layer panel, the colour picker and the Add menu fit on screen.
- Long languages such as German and Russian fit in the editor's tabs, chips, headers and labels.
- Dark theme: the export progress bar, the heat warning, Cancel and the Done seek bar are readable, and a few switches and checkboxes are visible again.
- Visibility: the eyedropper ring, the save-colour +, the fog swatch marker, the NEW and AI badges, the active speed chip and the "Still working" overlay are easier to see.
- Screen readers: about a dozen buttons that had no label now have one.
- Motion tracking: no crash when the point count changes while a tracker box is held.
- Saving: a save that runs out of memory is kept and retried, with a warning, instead of being dropped.
- Pre-render: a resumed render's percentage carries on, and turning a partial copy back on is instant.
- Tablets: selecting several layers no longer blocks the bottom of the timeline.
- Turning a 3D effect on the preview is undone when the system cancels the gesture.
- Auto captions: the font browser shows "Loading font" while a font downloads.
- Tools: the Video Downloader add-on card no longer freezes at "Downloading" after switching tabs.
- Tutorial: Start and Continue no longer go dead after a double tap, and leaving it no longer restarts it at step 1.
- Effect settings: Keyframe and Easing no longer start on a hidden setting.
- Community bundle: with 8 tags the other tags dim, and a typed tag is kept.
- Import: big bundle thumbnails and huge Adobe Animate (FLA) library pictures no longer risk running out of memory.
- Import on Android: running out of memory after reading a project shows "Import Failed" instead of closing the app.
- Home: the changelog button stays in place when switching tabs.
- Japanese and Thai: the theme step's footer no longer says "language".
- Theme: the device theme is only followed with System default, and picking System default applies it at once.
- Status bar on Android: readable on the Export screen and after rotating, split screen or changing the font size.
- Navigation bar with three buttons: it follows the app's colours (Android 8.1 showed white buttons on white).
- Android 8: the keyboard no longer pops up over the editor when you come back to the app.
- Android 14 and newer: preview and thumbnail caches are freed again when memory runs low.
- Video Feedback on three or more video layers: every layer shows its trail (the third showed none and decoded every frame again).
- Export: one failed export no longer keeps later exports on the slow software encoder until a restart, and the check after export no longer calls a good file wrong when the decoder is busy.
- Damaged projects: an extremely deep project file or an absurd particle burst is refused instead of crashing or hanging the engine.
- Android 8 (Compatible): a small memory leak when a video surface cannot be made.
- Linux: no crash on the first hardware-decoded video frame on Ubuntu 22.04 and 24.04, Debian 12 and Mint.
- Auto Captions on a computer: no crash on processors without AVX2.
- Exports on a computer: large exports to slow or external drives no longer fail with "stalled" at the end.
- Mac: webcams that report 30 fps start.
- Quitting during Compress, Reverse, GIF or a download no longer leaves ffmpeg or yt-dlp running.
- Windows installer: reinstalling over a leftover folder works, a busy app is never force-closed by an update, the app opens again after a failed update, new and network folders work, and damaged files and files in use get clear messages.
- Updates: "Not now" on a ready update no longer shows "the update was stopped" on the next start.
- Computers: opening a stray .xfl file no longer zips its whole folder first, and a rare crash at start is gone.
- Computers: hardware video decoding keeps enough frames and falls back to software when frames cannot be copied, a video with an unknown frame rate plays past its first frame, and Frame Blending on High no longer starts a new decoder for every frame.
- Linux install: Ubuntu 24.04 and Mint 22 use libasound2t64.


## [5.0.4] - 2026-11-10

### Added

- Changelog: the notes icon on the Home screen, the button on the About page and What's new in Settings open the full list of changes at any time.
- Compact Settings: the version, Check for updates and What's new are at the top, and Report issue and Suggest a feature sit side by side.
- Frame for groups: the new Frame tile picks which frame of a group or asset shows, with keyframes, like the Frame Picker in Adobe Animate.
- What's new lists every fix: the section and group headers stay at the top while you scroll, so you can close a long list at any point.

### Changed

- Edits and drags: finishing an edit or a drag no longer writes out the whole project on the main thread, so big projects stay smooth.
- Closing the editor: the project is saved once, in the background.
- Edits with effects: edits that change no effects skip the effect and link checks.
- Text on Android: the project is no longer read back after every edit.
- Many layers at once: an edit on a multi-selection does one pass instead of one per layer.
- Playback with a layer selected: the tools, top bar, panels and handles no longer redraw on every frame.
- Timeline: keyframes, cuts and labels are worked out once instead of on every frame, and the hover highlight follows a wheel scroll.
- Video scrubbing on Android: scrubbing forward over a video keeps up instead of freezing.
- Video playback on Android: one render per new video frame instead of two, and one picture copy less with Vulkan.
- Animated text spacing: no longer rebuilds the scene on every frame.
- Groups: their contents are shared instead of copied for the camera, 3D and copies.
- Project list: opens without reading every project file first.
- Home: loads the list once, keeps the thumbnails, and keeps its scroll position after you close a project or switch tabs.
- Effects browser: keeps its scroll position when you come back from an effect or swipe between categories.
- Timeline pictures: the layer picture no longer blinks during a colour, effect or point drag, and video strips stay during a pinch zoom.
- Effect tiles on Android: show at once instead of after a blank frame.
- Big photos: a larger graphics cache, so they are not uploaded again on every frame.
- HDR video: tone-mapped on several threads.
- Media tab: video lengths are read in parallel and picture sizes from the file header.
- Window resizing: smoother.

### Fixed

- Pixelate, Mosaic, Pixel Tiles, ASCII and Halftone: they cover the whole layer: blocks at the edge no longer vanish, so the layer no longer looks cropped and zoomed in, and nothing sticks out past the layer.
- Tile Shift, Tile Rotate, Hexagon Tile Shift and Hexagon Tile Rotate: tiles at the edge of the layer no longer leave holes.
- Adobe Animate (.fla): a moving object comes in as one layer with keyframes, so moving it no longer snaps back to where the file had it, and several symbols or pictures on one Animate layer each keep their own layer.
- Animate rigs: graphic symbols keep every frame (mouths, eyes, hands), and the frame the file picks is keyed on one layer.
- Alight Motion colours: wide-colour (Display P3) photos in imported projects look as they do in Alight Motion.
- Old Alight Motion imports: a project imported with an older version shows a one-time tip to import it again for the latest look.
- Timeline zoom on desktop: resizing the window, dragging a divider or switching to the wide layout no longer resets the zoom to fit and 0:00.
- New project on desktop: the timeline fits the first clip instead of staying fully zoomed in.
- Mouse wheel on rulers: turning the wheel over Size, Skew, Tilt, Camera or Shape is one undo step, not one per notch.
- Screen timeout: the phone screen stays on while a project plays.
- WebP pictures: they can be added, and effect choice pictures load.
- Rotated photos: photos with a rotation tag look the same on desktop and phone, and desktop projects made before are repaired when opened.
- Pre-render: a finished pre-render is kept, and exports, screenshots and thumbnails no longer clear the preview cache.
- Dropping a video or picture on Home: starts a new project instead of "Import Failed".
- Dropped files: they are taken as a copy, so the folder you drag them from never deletes them.
- Full-screen viewer: scrubbing no longer starts a new ffmpeg for every mouse move.
- Export: the list of encoders is rebuilt when it was cleared.
- Still pictures: import, version history and Export still no longer leave video decoders open.

## [5.0.3] - 2026-11-09

### Added

- One-step updates on the desktop apps: Settings > Updates > Download update > Install and restart saves the project, closes, installs the new version and opens it again. It works for the Windows MSI, the macOS app (a new `-update.zip` next to the DMG), the Linux user install, AppImage and `.deb` (the system asks for the password once); a protected macOS folder still opens the installer.
- Desktop updates are only installed when the release carries a signed list of its files (`update-manifest.json` and its signature).
- Android 12 and newer install an update without a confirmation dialog when nothing is open in the app (otherwise it waits for a tap on Install), and a notification opens the app afterwards.

### Fixed

- Android update: a finished download no longer installs and closes the app while you edit or export.
- Update install on Android: the update asks for a tap when notifications are off, so the app never just vanishes.
- Cancel update: cancelling "Waiting for Android to install the update" now really stops the install.
- Update notification: the full text shows, it is removed when you open the app, and it follows the app language.
- Linux AppImage update: it no longer replaces another program's AppImage by mistake.
- Safer desktop updates: an update without the signed list is now always refused.
- Mac update: Install and restart now works on macOS 11 too.
- Install and restart: the window no longer freezes while the update is unpacked and checked.
- Closing dialog: Esc or X on the "Installing the update" dialog now cancels it like Not now.
- Update dialog: Enter does the same as the highlighted button, and wrong texts in the update steps are fixed.
- Hidden download on desktop: the dialog comes back when a hidden download finishes or fails.
- Update leftovers: old installers, staged copies and logs are cleaned up, and a finished download is reused.
- Update clean-up: clearing the update folder can no longer delete files elsewhere through a link.
- Windows update: folders with square brackets work, and the update no longer waits on the wrong program.
- Linux .deb update: it checks the app came from the package and waits while another install runs.
- Update failures: an empty or unknown result from the update helper now shows as a failure with a reason.
- Background export: it keeps running with the screen off and when the app is swiped away.
- Long exports: Android's 6-hour limit no longer cancels an export while you are watching it.
- Export in the background: if Android refuses to start it, the Export button works again instead of staying busy.
- Cloud backup: big folders such as the downloader and compress files are left out, so backup no longer fails.
- Closing the editor: it no longer closes a project you opened a moment later.
- Restoring after the app was stopped: it no longer clashes with a tap on another project.
- Help: opening Help no longer crashes when the system WebView is missing or updating.
- Camera: switching to Video no longer crashes on phones that cannot do video with that camera.
- Media access: the app no longer keeps long-term access to files when Keep a copy is on.
- Compatible build: starts with OpenGL and only switches to Vulkan after a safe test shows it draws correctly.
- Compatible build crash notice: the Vulkan test crashing on some phones no longer shows "closed unexpectedly".
- Graphics check: a graphics check that could not run is no longer treated as passed.
- Edit Points and Pen Tool: they hit the right spot on tablets in the wide layout.
- Crop: dragging a crop handle no longer closes the app when the other side is cropped to 99%.
- Deleted layer: after the selected layer goes away, preview taps and the canvas view keep working.
- Select all and Trash: the panel and canvas view reset properly when nothing stays selected.
- Timeline on desktop: pressing the selected clip, keyframe or trim edge no longer closes the open panel.
- Size: the ruler, keypad and pinch can grow a layer that was set to size 0.
- Multi-select bar: the last buttons are no longer cut off on narrow screens.
- Z drag: dragging Z in Position moves at the same speed on every screen density.
- Preview touches: a cancelled touch no longer counts as a tap or a finished drag.
- Preview mode panel: the seven toggles stay usable when the preview is short.
- Text fields on desktop: clicking the timeline or preview ends typing, so shortcuts no longer go into the field.
- Alt and mouse wheel: it no longer ends a gesture half a second later.
- Group and element handles: resizing with a canvas handle no longer drifts when the pivot is off-centre.
- Reset canvas view: the two-finger double tap works with a slower first tap.
- Small desktop window: the workspace no longer fails when the editor area is very short.
- Timeline row drag: on a short phone window it no longer also scrolls the page.
- Desktop inspector and ruler: null objects show the right icon and rulers without a lower limit draw ticks.
- Dialog previews on desktop: after Presets, Freehand, Track motion or Template, the preview returns to normal.
- Paste style: it keeps the timing, speed, loop and volume of audio layers and groups.
- In and Out animations: they follow the layer when you trim or extend it.
- Ungroup: a sped-up group keeps every layer and its animations at the right times.
- Ungroup length: the project length updates at once after ungrouping.
- Undo and redo: the message inside an open group describes the real change, and the playhead stays valid.
- Shake transition: it no longer writes a useless rotation setting.
- Speed 2x, 3x, 5x: a sped-up clip no longer ends with an extra held frame.
- Speed keyframes: toggling a curve between keys no longer crashes with an odd value.
- Speed on late layers: changing speed on a layer starting very late no longer crashes.
- Duplicate and paste: copies of a layer that follows the layer below follow the right layer.
- Deleting the last layer of a file inside a group: the unused file no longer stays in the project.
- Opening a group: a group with a broken length no longer crashes.
- Frame screenshot: the picture saved to the gallery is no longer deleted when you enter or leave a group.
- Template editor: slots inside a group seek and highlight at the right time, and the preview stays centred after a resize.
- Tiles (Blocks): they fill the whole screen when a layer or group is moved, partly off the canvas, or mirrored.
- Tiles with Glow: the full-screen fix also works when the layer has Glow or another effect that reads the background.
- Effects at the edge: Blur, Glow, Drop Shadow, Flip and Tiles use the part of a layer just outside the screen.
- Tiles on big layers: they use far less memory.
- Groups with Mirror, Kaleidoscope or Offset: they look the same as in 5.0.2 again, and Tiles on groups keep outlines.
- Fixed-size groups: they keep their frame and resolution when their content is recorded.
- HDR video on desktop: it is tone-mapped, like on the phone.
- Wide-colour photos: Display P3 photos no longer look dull on the phone.
- Picture selection on desktop: clicking a transparent part no longer selects the picture, and its outline is hit.
- Desktop video scrubbing: scrubbing backward no longer shows an older frame.
- Desktop video errors: one bad frame no longer ends the clip until you seek back.
- Desktop pictures: a picture file changed on disk while it is open no longer risks a crash.
- Deeply nested groups: very deep nesting no longer crashes the engine.
- Desktop graphics: a slow software-only Vulkan driver is no longer used as the GPU.
- Camera on desktop: the camera no longer slowly eats memory.
- Desktop video seeking: the desktop preview and export show the right frame for files with a start offset.
- Audio effects: Add Audio Effect opens the Audio page, Voice filters shows as a folder, and Back leaves the panel.
- Eye button: the eye button in the audio effects panel has its normal size.
- Desktop export: it stops with an error instead of repeating or dropping frames.
- Desktop export space: free space is checked on the export drive too, and a full drive fails early.
- Project package: export refuses a package too big or with too many files to import again.
- Package size: the estimate now counts AI originals and model files.
- Emoji import on desktop: emoji written as numbers are no longer corrupted.
- SVG import: files from Adobe Illustrator and files with a byte-order mark now import.
- Import on phone and desktop: both read project files the same way.
- Compress: clearing the "Aim for a file size" field and typing no longer gives ten times the value.
- Discord preset: small low-resolution videos are no longer refused as too long.
- Tools Save: repeated taps no longer save duplicates, and closing the sheet no longer shows a false failure.
- Saved location: tools no longer say "Saved to Movies/AbsentMotion" on Windows and Linux.
- Trim and GIF range: the frame strip is no longer mirrored in right-to-left languages.
- Trim: picking the same video again resets Fast mode.
- Backup and restore: Stop pressed at the last moment is reported correctly.
- Restore stopped: the message now says correctly that nothing was changed.
- Restored backup: a restored backup can no longer crash the project list.
- Deleted project: it leaves the list at once and can no longer be opened.
- AI Smooth: a slow-motion clip is no longer baked again every 1.5 seconds.
- AI Smooth limit: the message names the real limit, the frame count, not "5 minutes".
- AI Smooth on Android 8 to 10: it no longer fails at the end when the sound has no samples in the range.
- AI Smooth on desktop: it no longer holds up memory clean-up while it works.
- AI Enhance and video cut-out: a looped clip is processed from where its loop starts.
- AI results: replacing the media of an AI result no longer brings the old media back.
- Photo cut-out: pictures that are turned are cut out upright.
- AI video tools: they no longer fail at the very end of some clips.
- Reverse on desktop: it keeps all of its sound, no longer fails on some clips, and no longer duplicates frames.
- Reverse on rotated clips: a clip with a rotation tag is no longer turned a quarter turn.
- Reverse on long clips: long high-resolution clips show a clear message instead of "Check failed."
- Home: it returns to the tab you were on after you close an asset.
- Shared project: opening one no longer switches away from Tools.
- Downloader: install keeps running when you leave Tools, and New project cannot be tapped twice.
- Waveforms: a failed waveform load tries again later.
- Old tool outputs: compressed files older than a day are cleaned up.
- What's new: a patch without its own notes no longer shows the 5.0 notes again, and no stray symbols show.
- Capital letters and dates: they follow the app language, not the phone's language.
- Damaged project on desktop: it opens the right Version history dialog.
- Menus and labels: the menu, file dialogs and several labels follow the app language.
- Opening several projects at once: uses one window instead of several full copies.

## [5.0.2] - 2026-11-08

### Added

- Round Corners on every shape, including shapes drawn or edited point by point.
- Options for the newer shapes: gear teeth, depth, width and hole, heart notch and point, ring thickness and sweep, sun rays, cloud bumps, crown points, ticket holes, arrow and outline thickness and more. They can all be animated, and the less common ones sit behind Advanced.
- Phone and Panel shapes with border, camera, header, columns and rows.
- Edit Points on a shape with options turns it into points; Undo brings the options back.
- Multi-select bar: Trim start, Split and Trim end next to the move-to-playhead button.

### Changed

- Edit Points and Pen Tool open inside the editor: the top bar, the play bar and the timeline stay and only the bottom panel changes; the preview keeps its size.
- Dragging a point trails the finger a little and hides the other point markers while it moves, like Alight Motion.
- The effects actions of the multi-select bar moved into its More menu; Stretch Timing stays in the clip's long-press menu.

### Fixed

- Pinching several selected layers follows the fingers and stays put when you let go.
- Desktop: the Transform inspector showed only Rotate; all six tabs show again. Cut-off captions and labels, blank names of unnamed layers and stretched Crop rows are fixed, and a layout test now checks every panel at four window sizes.
- Windows: overlay, multiply, hard-light, darken and soft-light blending drew hard diagonal blocks in the preview on Vulkan graphics when the view was not on whole pixels.
- Tilted layers: the selection frame no longer stretches into a long band, tapping beyond a tilted layer does not select it, Align, Distribute, Fit and Group no longer throw it far away, and point markers and motion path dots are not mirrored behind the camera.
- Parent chains longer than 16 layers are placed the same way by the editor and the engine.
- Project packages no longer report the app's own shape attributes as unknown parts, a medium named like the import marker is not deleted, and 3D model file names stay valid on Windows.
- Phone and Panel line up with the other shapes on the last page of the shape menu; the zoom button is hidden where the view is fixed.

## [5.0.1] - 2026-11-08

### Added

- Move several selected layers together: with two or more layers selected, a box goes around all of them; drag anywhere in the preview to move them, and its corner dots make them bigger or smaller together. A pinch does the same.
- High-detail 3D models (Settings, Playback): 3D models can keep up to 100,000 triangles instead of 50,000. Adding a model with more than 50,000 asks first; the setting turns it back down.

### Changed

- A new group's anchor point sits in the middle of what is in it, so a new group rotates and scales in place.

### Fixed

- Projects, templates and presets brought back by Android's own backup after a reinstall showed the date 21 January 1970.
- Group corner and side dots grew and shrank a group around the middle of the canvas instead of the middle of the group.
- A group's thumbnail in the timeline was a white rectangle; it shows what is in the group now. Drawing layers had an empty thumbnail.
- Desktop: the effects "..." menu (Copy and Paste Effects), the Transform reset menu, the multi-selection Effects and More menus and the audio effect menu opened at the far left of the window instead of at their button.
- Updating from 4.x straight to 5.0.1 shows the 5.0 What's new.
- Groups no longer cut off what is inside them at the canvas edge: a group made smaller or moved shows the parts that were outside, and its box goes around all of it, as in Alight Motion.
- Ungroup no longer makes layers that followed the group (parented to it) jump; they keep its movement.
- Restoring a backup: a project whose imported font had to be renamed comes back as a copy that uses the backed-up font instead of being skipped. On the desktop, AI cut-outs, enhanced clips and other AI files point at the restored copies when the backup is restored in another folder or on another computer.
- Exporting an image sequence again under the same name puts it in a new folder, Name (2), instead of mixing the frames of both.
- Desktop: a video whose sound starts later than its picture keeps the sound in its place in playback, waveforms and exports.
- Desktop: a long sound mix no longer stops a video export as stalled before the picture starts, and the export checks for room for the sound first.
- Desktop: bit rates Opus and AAC cannot make in mono are no longer offered.
- Desktop: exporting a big project does less work for each frame when no text in it animates.
- 3D models over the triangle limit were simplified far more than needed (a 237,000-triangle phone kept only 5,600 triangles) and lost their sharp edges; they now stay close to the limit and keep hard edges.
- 3D model faces with many corners, such as a phone's notched screen, are split into triangles that stay inside the face.
- Inside a group, 3D layers and tilted pictures keep the project's depth order, so a picture placed on a phone model no longer goes behind it.
- The import report no longer lists settings the app writes itself, such as the Scale lock, as not recognized.

## [5.0.0] - 2026-11-07

### Added

- AbsentMotion on the computer: a desktop version for Macs (Apple silicon and Intel), Windows and Linux. It runs the phone's own editor, effects and export, laid out for a mouse and a keyboard, with a media library, an inspector, a mouse timeline, right-click menus, keyboard shortcuts, files dropped into the window, linked folders, the webcam, export in the background and in-app updates.
- Windows: a per-user installer (.msi) with Start menu and desktop shortcuts, for 64-bit Windows 10 and 11.
- Linux: a .deb package and an AppImage, each for arm64 and x86_64. They run on glibc 2.35 or newer (Ubuntu 22.04, Debian 12 and later).
- Text effects: Text Shake (letters shake on their own as Shake, Jitter, Wave, Circle or Wobble, or only some letters at a time), Text Blips (types the text out with a blip for each letter, eight voices, heard in the preview and the export) and Font Weight (animate the weight of variable fonts, imported ones too).
- ASCII effect: any layer as text art made of letters and symbols, with six presets.
- Depth order: with a camera, layers in 3D are drawn by their distance from the camera, and the camera can tilt and orbit around the scene. New projects have it on; older projects keep their look until it is turned on. A 3D layer tile lets flat layers take part.
- Light layers: Ambient, Directional and Point lights with colour, intensity and falloff, up to four at once, shade 3D models, 3D Scatter and 3D Fracture.
- Linear Repeat, Radial Repeat, Grid Repeat and Repeat Along Path: Delay per copy, and Random Position, Rotation, Scale and Opacity for each copy.
- Parenting Helper and Scale Assist effects (Move). Alight Motion projects that use them, or the 3D orientation of a layer, now import.
- Groups: Edit Group opens a group, the top bar shows where you are and leads back, and Ungroup in the layer menu puts the layers back.
- Group Re-timing: the gear of an open group has Re-timing (Off, Freeze, Stretch, Loop, Repeat & Stretch, Blank), and the copy and paste menu sets its intro and outro flags.
- Stretch Timing (More actions of a multi-selection) makes the selected clips longer or shorter together; their animations stretch with them and videos play slower or faster to fit.
- A clipped layer shows a small hooked arrow in the timeline that points down to the layer it is clipped to.
- Swipe an effect in a layer's effect list to the left to delete it, and swipe left or right inside an effect category for the next or previous category.
- Deep Glow, HSL and Drop Shadow effects.
- Alight Motion presets keep more effect settings when they are imported: Light Glow Blend, Soft Glow outside glow, Chroma Key Defringe, Long Shadow Solidity, Electric Edges Strength, Fractal Warp Intensity, Offset, Parallax and Screen Space, Gradient Map Saturation, Edge Glow Strength and Tint, and Smooth Bevel. Edge Glow, Fractal Warp, Chroma Key, Soft Glow and Electric Edges look closer to Alight Motion after import.
- Tools tab: Trim (Fast without re-encoding, or Exact), Extract Audio (M4A or MP3) and Make a GIF (up to 30 seconds, three sizes, three frame rates, loop or play once).
- Tablet layouts for big screens in portrait and landscape: panels beside the preview, a taller timeline, grids on the home screen and tool screens in two columns.
- Audio layers have a Mark Beats tile.
- Puppet Pin: pins curl the picture smoothly, pin types Position, Bend (with rotation and scale) and Stiff, Triangle Density, Mesh Expansion and Sway.
- Effect presets: a Presets button on an open effect, a picture for every preset, and favourite presets that show first in the Favorite strip.
- Several selected clips stretch and split together.
- Colour palettes in every colour picker on the phone and the desktop (Fill & Color, the full picker, frame and shadow, gradients, text, lights, captions and effect colours): make palettes from a picture (its 8 main colours, or every colour of a palette picture AbsentMotion exported) or from palette files (.gpl, .ase, .pal, .act, .hex), add, duplicate and delete colours, sort them by hue, lightness or saturation, lock a palette, and export it as a PNG strip, .gpl or .ase. Eight built-in palettes are locked; make a copy to change one. Palettes are kept in backups. On the desktop, a palette file dropped on the window joins the library.
- Real 3D part two: Camera Options > Camera View has Sort, By layer or By piece. By piece sorts the pieces of 3D Scatter and 3D Fracture, the faces of 3D models and particles with Depth one by one among the other 3D layers, so objects that pass through each other overlap where they should. Mix pieces with other layers, on the 3D Scatter, 3D Fracture and Particles cards and in Model Options, keeps one layer whole. Pieces that cut through each other are not split, and groups do not mix with the layers around them.
- Adobe Animate import: .fla files from Flash CS5 on (and XFL folders on the desktop) open through the normal import, Choose file, Share or Open with. Shapes, symbols as groups, pictures, classic tweens with eases, alpha, tint and brightness, masks and MP3 sounds on the main timeline come over; the import report lists them and what was left out, such as text, ActionScript, shape and motion tweens, filters and camera layers. Older binary .fla files get a message that says so.
- Chromebooks with Intel or AMD chips have their own x86_64 files, -foss-x86_64.apk and -compatible-x86_64.apk. The in-app update picks the right file, and the download page lists them. The Video Downloader is not in these files.
- Desktop window bar in the app's colours with the system's own window buttons, and a menu button with File, Edit, View, Playback and Help.
- Desktop media belongs to each project; right-click a folder and choose Pin to all projects to see it everywhere. Search lists the folder first.
- Desktop level meter for the whole project, right of the buttons on the left; with nothing selected, the Inspector shows the project settings and the list of layers.
- What's new: the first start after updating to a version with release notes shows what changed, once, with links to report problems.
- The level line under the play bar shows the left and right channel; tablets held sideways also show a level meter at the top left of the preview.
- Effect help on the desktop opens in its own window inside the app.
- 90 and 120 fps projects.
- Saved custom sizes in New Project and in the project settings.
- Copy and Report on crash and error screens, with links to GitHub, Discord and the bug report form.
- Join Discord: a button on the About page and links in Settings and the Tools tab.
- Desktop: Alt+Left and Alt+Right jump between bookmarks; comma and period nudge the selected clips.
- Linux: a .tar.gz with an install script for any distribution.
- Effects library: swipe left or right on the Recent and Favorite grid to switch, or press and hold the two small icons and slide your thumb.
- Eight new languages: Korean, Chinese (Traditional), Chinese (Simplified), French, German, Italian, Polish and Ukrainian, with the help pages and picture captions in each. Phones set to Chinese for Taiwan, Hong Kong or Macau get Traditional Chinese.
- The preview renders ahead while you pause, starting at the playhead and growing outward, and a thin green line at the bottom of the time ruler shows the frames that are ready. Settings has a switch to turn it off.

### Changed

- Adjustment Layer is now called Copy Background and sits in a new Other category. Projects that use it keep working, and searching for "adjustment layer" still finds it.
- Effect presets left the grid of the effects library; searching a preset's name finds its effect. The New category lists the effects of 5.x.
- While a gradient is being edited, dragging on the canvas moves the nearest gradient dot instead of the layer.
- Effect and border card headers are lighter.
- If the app closes while Vulkan or OpenGL is starting, the next start uses the other one.
- On Samsung phones, when Auto Blocker may be stopping an update, the update message says so and how to turn it off for the install.
- The source code has one folder per platform: shared, android, pc, macos, windows and linux.
- Linking a layer to a parent or unlinking it no longer adds keyframes: the layer keeps its own animation and stays where it is at the playhead.
- A short tap on an effect's reorder grip opens the effect; hold the grip to move it.
- Opening an effect card or an audio effect moves it up to the top as it opens, and the other cards slide out of the way; closing it puts it back in its place.
- Electric Edges from Alight Motion: Thickness widens the edge into a glowing band and a keyframed Seed crackles on every frame, as in Alight Motion. Projects saved before 5.0 keep their look.
- Desktop file and folder pickers use the system's current dialogs on Windows and Linux.
- Help on tablets opens in a wide panel with the pictures beside the text, instead of a phone-sized card.
- A change to one layer now throws away only the prepared preview frames of that layer and of the layers parented or linked to it, instead of all of them.
- Desktop: editing and scrubbing respond sooner, with less waiting on each step and on video frames.
- Phones and tablets: editing a project that has a video no longer waits for the video on every step; the exact frame follows when it is ready.
- Phones and tablets: compiled graphics programs are kept between launches, so effects stutter less the first time they show after a restart.
- The bug report, feature request and Discord links in Settings, Tools and the report window follow the project's README, so they stay current; long Google Forms links work too.

### Removed

- The Play build. Play installs move to the foss build when they update, and keep their projects and settings.

### Fixed

- A thin light line could show along the edge of the canvas.
- 3D Scatter, 3D Fracture and the 3D shape effects such as Cube turn with a tilted or orbiting camera like other 3D layers, and the light on Scatter and Fracture pieces stays the same when the camera rolls or zooms.
- Puppet Pin keeps the parts it pulls in from outside the canvas, also when the layer has Glow.
- On computers set to a language with decimal commas, projects could show layers in the wrong place.
- A damaged frame near the end of a video no longer freezes the rest of the clip.
- Two layers using the same video play and export smoothly.
- The background preview no longer stops for good when a project is still loading.
- Full motion blur in the preview no longer risks freezing weaker tablets.
- Long chains of linked values no longer freeze the editor.
- The background preview redraws a clipping mask, a path follower or Displacement Map when the layer under it changes.

### Licence

- 5.0 and every later version are released under GPL-3.0. 4.4 and earlier stay under Apache-2.0.

## [4.4.1] - 2026-11-01

### Added

- Fill & Color > Media works on shapes: fill any shape with a photo or a video.
- Separate Bug Report and Feature Request forms, with a link to each in Settings.
- The Tools tab says more tools are coming, with links to suggest one.

### Changed

- After swiping between the two tool pages, the first tap works straight away.
- Photos show the AI button on page 2.
- The Video Downloader is greyed out until its 25 MB add-on is downloaded; tapping it first explains what it does. On tablets it is centred, with two columns in landscape.
- Reset position, scale, rotation and the rest clear that setting's keyframes instead of adding one.
- A new project from the Assets tab starts as an Asset.

### Fixed

- When YouTube asks to confirm you are not a bot (often on a VPN), the Downloader showed the video as pictures; it now says what happened.
- On Android 8 and 9, Screenshot, Compress and the Downloader could fail to save until storage access was allowed elsewhere.
- Undo, then Redo of a Screenshot could bring back a picture whose file had been removed.
- The README and store text said 184 effects; there are 207.

## [4.4.0] - 2026-11-01 - The last 4.x

### Added

- A Tools tab on the home screen with Compress (videos and pictures to Low, Medium, High, Custom or a Discord size, with a full-screen before-and-after preview) and a Video Downloader (YouTube, Instagram, TikTok and more; needs a one-time 25 MB add-on).
- Speed curves (Montage, Hero, Bullet, Jump Cut, Flash In, Flash Out or your own points) and a Speed ruler from 0.05x to 100x.
- Reverse for video clips, sound included.
- Screenshot: saves the frame of a video or GIF under the playhead at full size without effects, to the gallery and as a new layer; deleting that layer within 10 seconds removes the picture again.
- Effects: Particles (with depth), nine Beat effects, Liquid Glass, Video Feedback, 3D Scatter and 3D Fracture. Stroke Taper also tapers the Stroke of lines and shapes.
- Nine transitions: wipes in four directions, clock wipe, glow, swirl, pixelate and noise dissolve.
- Several selected layers can get the same effect, animation or preset at once, and a transition on every cut between them.
- Auto caption outlines with a thickness and colour, and custom caption colours.
- Background removal: choose what to keep, with Softness and Grow / Shrink; Background removal 2.0 uses a newly trained model (ai-models-3).
- Pre-render, help pages with pictures, and Spanish (Spain), Urdu and Japanese.

### Changed

- The tools sit on two pages; Crop, AI, Auto Captions, Reverse and Screenshot are on the second. Move & Transform opens on Basic Controls.
- Export Cancel stops at once without asking. About links open the project page and the website.
- A phone that draws pictures wrong with Vulkan switches to OpenGL by itself after a one-time check.
- The previous background-removal model is deleted after the update.

### Fixed

- Video clips showed black on some older phone GPUs.
- Large or detailed SVG files took minutes to import.
- The screen went black for a moment after choosing a language on the first start (Android 12 and older).
- Pictures from the Video Downloader kept their original date.
- Auto captions could be up to a second off; speed could reach almost 0x; very fast clips could disappear; picking an effect while searching pushed the screen up; exact effect search matches now come first; and more.

### Licence

- 5.0 and every later version will be released under GPL-3.0. 4.4 and earlier stay under Apache-2.0.

## [4.3.0] - 2026-10-30 - Compatibility

### Added

- OpenGL ES 3.0 support: phones without Vulkan 1.1, or with a broken Vulkan driver, run AbsentMotion through OpenGL. It is used automatically when Vulkan cannot start, and Settings > Graphics can switch to it. Preview, export, motion blur, Smooth and Datamosh work on it; the heavy AI tools are locked on OpenGL.
- The Compatible version runs from Android 8, and a new Compatible 32-bit file (`-compatible-32bit.apk`) covers phones with a 32-bit system.
- Retro Dither and Pixel Tiles effects. Palette Map gets 17 preset palettes, palettes imported from pictures and from ASE, ACT, GPL, PAL, HEX and Paint.NET files, and 23 dither patterns including Floyd-Steinberg and Atkinson.
- New effects that also open Alight Motion projects using them: Energy Beam, Grunge, Hexagon Array, Luma Stamper, Matte Fringe and Key Spill Cleaner.
- Chromatic Zoom Blur effect. Color Wheels gains an Offset wheel and a By Brightness style; Split Tone gains shadow and highlight levels and a Gradient Map style. Halftone gains a Two Color style, Mirror a Flip style, Matte Choker an Invert switch, Flip Layer an Axis, Colorize a Saturation setting, Gradient Overlay an Offset and Exposure a Brighten See-Through Parts switch.
- Link several selected layers to one parent at once.
- 3D Extrude and 3D Shape follow the 3D rotation of their parent.
- A Report issue link in the app menu for people without a GitHub account.

### Changed

- Alight Motion projects look much closer to Alight: photos keep their colour space, adjustment layers keep soft edges and reach past the layer, effect directions turn with rotated layers, and many colour, blur and distortion effects follow Alight's measured look. More than 30 more Alight effects import (44 effect ids), including the 3D shapes, Flip Layer, 360 Reorient, VR Player, Tunnel, Drawing Progress, Stroke Taper, Mirror, Matte Choke, Bump Map, Iridescence, Hex Tile, Colorize, Text Transform, Lift Gamma Gain and Split Tone.
- The import report lists only what did not come over.
- Borders in Frame & Shadow open and close smoothly, and a new border unfolds out of Add Border.
- Export time left starts from the estimate, counts down smoothly and learns from past exports. The choppy playback prompt is gone.
- Crop: borders, effects and Move Along Path use the cropped picture. Transform has Basic and Advanced controls. 3D models keep their textures when simplified.
- Shutter Blur, Motion Blur, shadows, borders and blends use less memory; big photos shown small no longer shimmer; AI frame interpolation uses all fast CPU cores; dragging and sliders stay smoother on big projects.
- The Play version stays through 4.x and ends in 5.0. A Play install shows a notice on its first two starts, and its updater moves to the foss file when a release has no Play file.

### Fixed

- 62 bugs, among them: a black preview after a graphics error; a broken project in a backup restored as if it were fine; deleting a project could delete media a damaged recovery still needed; replaced sped-up or looping template videos lost their length; AI video carried the whole clip's sound; decoders could hang on some files; preview copies could come out black; relinked clips stayed marked missing; a child layer jumped when its parent's scale went through zero; the Border tab went empty after its open border was removed.

### Credits

- Thanks to [TrueMotion](https://github.com/Mushroom65/TrueMotion) by Mushroom65, a project based on AbsentMotion, for feature ideas, including the Particles effect planned for 4.4.

## [4.2.1] - 2026-10-29

### Fixed

- The app no longer crashes on the home screen when you come back to it after Android closed it in the background.
- Typing both Left and Right (or Top and Bottom) in the Crop keypad keeps both values; the first one was limited by the old value of the other side.
- An export stopped because the app was swiped away from recent apps now says "The export did not finish" instead of disappearing without a word.

## [4.2.0] - 2026-10-28 - Stability & Bugs

### Added

- GitHub: a bug report form that asks for the version, build, device and steps.
- GitHub: a feature and effect request form, so requests arrive in a clear shape.
- GitHub: an issue chooser that points to private security reporting and the guide, with blank issues turned off.
- Text Along Path, in the Text effects, sets text along an arc, a circle or the outline of the layer just beneath it (draw any path with the pen and put the text above it). Position, alignment, baseline shift, letter rotation and direction are settings that can be animated, and the arc and circle have handles on the canvas. Each letter keeps its shaping, font and colour emoji; right-to-left text keeps its order.
- Puppet Pin, in the Distort effects, bends a layer with pins. Turn on Add Pins and tap the layer, then drag a pin on the canvas or type its position; pins you do not move hold the layer in place. Each pin can be animated with keyframes, put back or deleted, up to 32 pins, with a Falloff setting: higher values keep the bend close to the pins you move, lower values spread it smoothly over the layer.
- Photos and videos have a Crop button: drag the corners, the edges or the whole frame on the canvas, pick Free, 1:1, 4:5, 3:4, 16:9 or 9:16, or type each side. The crop is a new Crop effect (Key), so it can be animated, rounded and feathered, works on any layer and leaves the layer where it is.
- Transform has a Pivot tab: X and Y boxes, a grid for the corners, edges and centre of the layer, a trackpad, and a marker on the canvas that can be dragged. Moving the pivot keeps the layer where it is, its child layers stay put, and the pivot can be animated. Reset Pivot is in More, and Reset Transform also resets the pivot.
- Updates download inside the app. When a newer version is found, the update dialog shows its version and download size, and Download update fetches the matching Latest, Play services or Compatible app file from the GitHub release with a progress bar, Cancel and Try again, instead of opening a browser.
- Before installing, the app checks the download against the SHA-256 published with the release and checks that it is the same app, the same version type, newer than the installed one and signed with the same key. Anything else is deleted and not installed.
- Android always asks you to confirm the update. If installing updates from AbsentMotion is not allowed yet, the app explains why and opens the Allow from this source setting as soon as you choose Download update, before anything is downloaded; the download starts when you come back with it turned on, and the setting is checked again before the install. Devices that already allow it are not asked. Your projects and settings stay as they are.
- An update download that loses its connection tries again by itself and continues where it stopped.
- If AbsentMotion is closed during an update download, the next start offers to continue it.
- The update dialog says when the connection is metered, for example mobile data.
- `tools/device_matrix.py` runs the emulator checks on a chosen emulator, or boots a matrix of Android versions, including one with 16 KB memory pages.

### Changed

- The small dots around a selected picture or video have a bigger grab area, so they are easier to catch. On small layers the area stays smaller so the layer can still be moved.
- The Rotate tab always shows the 360 degree dial for turning a layer in 2D, also on tilted layers; 3D turning stays on the Tilt tab.
- The New category in Effects only lists effects from the last five updates, so older effects no longer carry the NEW tag.
- The Curl effect thumbnail rolls up and back down instead of jumping back to the start.
- The Transform tabs are now Move, Rotate, Size, Tilt, Skew and Pivot: Rotate comes before Size. Each tab shows its name under the icon.
- The corner and side handles on the canvas scale a layer around its pivot, so the pivot stays still.
- The Size ruler keeps gliding after a flick, like the Tilt and Skew rulers.
- Whether width and height are linked is remembered for each layer, saved with the project and undone with Undo. With the link off, width and height keep their own values, also in keyframes.
- Cut-outs now drop faint haze and stray specks away from the subject, so less of the old background shows as a ghost.
- Updates check free space before downloading and before installing, and first remove leftover files of other or abandoned updates.
- Copies installed from Google Play, or signed with another key, say where their updates come from instead of downloading one in the app.
- Release checks refuse APKs that are not signed with the release key, and the full check also checks that the native libraries are aligned for 16 KB memory pages.

### Fixed

- Selection dots on a full-width picture can be grabbed at the screen edge; Android's back gesture no longer takes the touch.
- A photo, video or sound picked in the file picker is no longer lost if Android closes the app while the picker is open.
- Restoring a backup keeps a changed template or preset as a separate copy instead of skipping it silently, and no longer swaps in a different font of the same name.
- A backup now fails, instead of saying "saved", when a file disappeared while it was being written.
- Importing a scene keeps Move Along Path and other layer links when layer numbers have to change.
- The export check compares the video and the sound separately, so a short track can no longer hide behind a long one, and it also compares the first frame.
- When the phone cannot play an export back to check it, the done screen now says so.
- Saving an animated layer to My Elements no longer crops it, for example a long bar that rotates.
- Adding many beat markers no longer freezes the editor for a moment.
- Cut-outs: a memory read past the end, a result made from a grey placeholder after a low-memory moment, and a cancel that was ignored are fixed.
- Exporting a still or GIF on a low-memory phone can no longer close the app.
- Cancelling and restarting an update download no longer deletes the new download.
- The speed panel's four retime buttons and the point editor's controls have names for screen readers, and the point handles are easier to hit.
- Camera: closing or going back offers to discard a recording instead of saving it.
- Starting an export, or going back while an AI job runs, can no longer leave the app stuck; a stalled export is stopped with a clear notice.
- Retained media with a name ending in .part or .tmp is no longer deleted when the app starts.
- A cancelled layer reorder no longer commits, and the preview no longer restarts on every rendered frame.
- The first keyframe is no longer a dead zone when trimming.
- Importing: texture names with spaces in OBJ models are kept, project numbers are reserved safely, glTF triangle strips and fans are read, and root SVG opacity is applied.
- Damaged or oversized project files and unreadable indexes no longer block cleanup or use up memory.
- Large font sizes no longer cut off the new-project rows.

## [4.1.2] - 2026-10-27

### Changed

- Auto captions open as a scrolling panel over the lower part of the editor instead of covering the whole screen. While captions are being made, the panel shows only the progress, the time left and Cancel.
- The text editor keeps the keyboard open while you type, move or resize the text, and pick a size, a colour or a font from the strip. Only the tick, Back and the full font list close it; the number keypad and the full colour picker hide it and bring it back when closed, and it comes back only if it was open before. The tick uses the app's accent colour.
- Tapping the timeline ruler just above the Add menu closes the menu. Dragging and scrubbing there still work.
- Version History has square, app-coloured panels with clearer rows, buttons and confirmations.
- In the tutorial, the Add button shows one red pulse with a white flash, the white pointer glides from one control to the next, and steps that ask for a drag or a scrub finish when you lift your finger.
- Settings show the installed version under Check for update.
- Rays is redone: rays use the layer's own colours, reach further from the Center and follow the bright parts more closely. It has new Threshold Add and Blend controls and a Style choice. Effects already saved in projects keep their old look (Classic); new effects use Standard. Imported Alight Motion Rays use Standard and now keep their Threshold colour and Blend.
- In host benchmarks on a Mac GPU, the new Rays costs 1.12 to 1.17 times the old one per 1080 x 1080 frame; there are no phone numbers.

### Fixed

- A cancelled export or export-size estimate could leave the editor preview frozen until the project was closed.
- Preparing preview frames in the background no longer redraws the unchanged preview after every frame it prepares.

## [4.1.1] - 2026-10-26

### Fixed

- Photo and video thumbnails on the timeline no longer fall back to stripes for a moment when rows scroll back into view or another picture or font loads. A thumbnail is only reused while its file, the engine and the fonts are unchanged.
- During the tutorial's play and scrub step, the clip tools and the add button can no longer be used.

## [4.1.0] - 2026-10-26

### Added

- The editor prepares preview frames by itself while it is idle, starting at the playhead. An edit only clears the frames it changes, and preparation pauses during playback, gestures, export, high temperature and while the app is in the background.
- Captions open full screen with a choice of clip or whole project, a progress bar with the time left and a Cancel button, and Auto captions on video and audio clips. A more precise multilingual speech model (about 82 MB) is an optional download.
- Voice filters are a category of the audio effects.

### Changed

- Motion Blur and Shutter Blur draw layers that do not change during the shutter once instead of once per sample; in host benchmarks of blur-heavy scenes, exports were 1.09 to 1.65 times faster.
- Prepared preview frames follow the preview's Fast or Full motion blur choice, and exports keep the chosen quality.
- The tutorial only lets the current step's control be used, marks it in red, and hides the other editor controls from screen readers.
- Error messages, default layer names and effect names follow the app language and change right away after a language switch.
- Offline Help is available in all 14 languages, and many translations were corrected.

### Fixed

- The preview no longer shows an empty picture while a video frame is still loading, or a frame from an older seek.

## [4.0.1] - 2026-10-25

### Changed

- Swipe the Transition button left to hide it until the next timeline scrub; Settings can move it into the preview area.
- Transition and animation thumbnails use the existing bundled photo images, and their grids compose visible tiles as needed.
- Export filenames start with the project title on each visit and can be edited. Image and video settings, including current-frame PNG, offer fresh random numeric names.
- Export no longer shows the temperature stat or a successful playback-check message; thermal protection and verification warnings remain active.
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
