---
title: Settings
group: Interface
order: 8
summary: Every setting in the drawer on the home screen, with its default.
---
Tap the menu button at the top left of the home screen to open the Settings drawer. It has four groups that start closed (tap a group to open it) and a few more rows below them.

## Editor

- **Theme.** **AbsentMotion** (the default: a dark editor and a warm light home screen), **Daylight** (light everywhere) or **Custom**. **Import theme file...** loads a theme from a small file (see below).
- **New Layer Length.** How long new layers last: 1 to 10, 15, 20, 25, 30, 45 or 60 seconds. The default is 2.
- **Vibration.** Small vibrations on the timeline for snaps, long presses and reordering. On by default.
- **Emoji Style.** **SYSTEM** (your phone's emoji, the default) or **ANDROID** (the emoji font that comes with the app, the same on every phone).

### Custom themes

A theme file is a small text file in JSON format of up to 64 KB with an optional `name` and any of the colors you want to change, written as `#RRGGBB` or `#AARRGGBB`. Colors you leave out keep the AbsentMotion look. For example:

```
{"name": "Mint", "Accent": "#5BD6A4", "HomeAccent": "#1B7A4A"}
```

The color names are the editor and home screen colors: TopBar, PreviewBg, TimelineBg, Panel, PanelInset, Button, Field, FieldHi, Chosen, Well, Deepest, Line, MenuLine, KeyDigit, KeySymbol, Scrim, PressEditor, OnDark, OnDarkDim, Caption, Faint, Inactive, OnScrim, Accent, AccentLit, AccentGlow, AccentDeep, OnAccent, Cool, CoolDeep, Gold, Warning, Bookmark, LayerBar, LayerBarStripe, TextBar, TextBarStripe, AudioBar, AudioWave, OnBar, OnAudioBar, HomeBg, Card, SheetBg, Navy, HomeAccent, OnNavy, OnNavyAccent, NavyHi, NavyLine, HomeInk, HomeMid, HomeDim, HomeFaint, HomeLine, HomePanel, HomeDrawer, SwitchOff, Thumb, MenuScrim and PressHome. One custom theme is kept at a time; importing a new file replaces it and applies it at once.

## Playback and preview

- **Preview Quality.** **FULL**, **HALF** or **QUARTER** (default Full): how sharp the editor preview is. Lower is faster on a slow phone and does not change exports. The same switch is in the preview strip of the editor.
- **Playback Resampling.** **LOW**, **HIGH** or **ULTRA** (default Ultra): the quality of the sound resampling, when the editor plays and when the sound is mixed for an export.
- **Use preview copies of large videos.** On by default. For videos bigger than 1920 x 1080, or with few keyframes, the app makes a small, light copy in the background and uses it for the preview. Exports always use the original file. The copies are kept in a cache of up to 2 GB.
- **Clear preview copies** deletes them. The row shows the space they use.

## Projects and media

- **Show the import card in the project list.** Off by default. Adds a card with **Choose file** at the top of the project list.
- **Keep a copy of imported media in the project.** On by default. The photo, video or audio you add is copied inside the project, so it keeps working if the original is moved or deleted. Turn it off to save storage if you never move your files.
- **Remove caption model.** Only shown after the speech model for captions has been downloaded. It shows the size of the file and deletes it; the app downloads it again if you use captions later.
- **Delete AI ... model.** One row for each downloaded AI Enhance or AI Smooth model, with its size. Tapping it deletes the model; the app downloads it again the next time it is needed.
- **Use the graphics chip for AI.** On by default only for newer graphics chips known to be safe for AI Smooth and AI Enhance; on other phones these run on the processor. Turning it on for another chip asks first, because it can freeze some phones. See [AI tools](@ai-tools).

## Backup

- **Back up all projects**, **Restore from backup** and **Weekly automatic backup (projects only)** (off by default). See [Projects, backup and import](@projects).

## Language

The **Language** row shows the current language and opens the list of the 13 languages. Picking one applies it at once.

## About, Help and other rows

- **Help** opens this guide inside the app, without a connection.
- **About** shows the version, the license (Apache License 2.0), a note that AbsentMotion is an independent project, **Open source licenses** (the full license text of every library the app uses) and the **Project home page** address.
- **Crash logs** lists the crashes the app recorded. Tap one to read it and use **Copy** to copy it, for example to send with a bug report. See [Troubleshooting](@troubleshooting).
- **Check for a new version each time the app opens.** The app asks you once whether it may do this. If it is on, the app makes one request to GitHub for the latest version number when it opens. It sends nothing else and never downloads or installs anything by itself.
- **Check for Update** does the same check once, now. If a newer version exists, **Download** opens its page in your browser.
