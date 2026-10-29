---
title: Troubleshooting and questions
group: Help
order: 1
summary: Answers to common problems: crashes, missing media, export errors, storage and slow playback.
---
## The app closed by itself

Android closes apps that use too much memory. This is most likely with 4K video, many heavy effects, or other apps open at the same time.

1. Open AbsentMotion again. If it shows **AbsentMotion closed unexpectedly**, tap **Restore** to get your last edits back.
2. Close other apps, and try the project again.
3. Lower **Preview Quality** to Half or Quarter in the preview strip or in Settings.
4. Switch on **Use preview copies of large videos** in Settings (it is on by default).
5. Export at a lower resolution, or split a very long project.

Settings, **Crash logs** shows what happened in one sentence for each crash. **Copy** copies it so you can send it with a bug report on [GitHub](@issues). Nothing is sent automatically.

## This phone cannot run the editor

AbsentMotion draws the preview and the export with the graphics chip through Vulkan 1.1 or, when the phone does not have it, through OpenGL ES 3.0. If neither starts, the app shows **This phone cannot run the editor** with the Android version and the graphics chip instead of closing. Your projects, settings, backups and crash logs still open. **Copy details** copies the facts so you can add them to a bug report on [GitHub](@issues). See [Requirements and versions](@requirements). If the editor opens but the preview or exports look wrong, choose **OpenGL** in **Settings**, **Graphics**.

## Missing media

A layer is empty, or export says **Media Missing**: a photo, video or audio file the project used is no longer on the phone.

- Select the layer, open **Media Info** in the layer menu and tap **Swap media file** to pick the file again.
- Use **Collect media into project** in the project card's menu while the files are still there, so the project keeps its own copies.
- Keep **Keep a copy of imported media in the project** switched on in Settings.

If you export anyway, the layers that use missing files are left out.

## Export problems

| Message | What to do |
|---|---|
| **Not enough storage** | Free space on the phone or pick a smaller resolution. The estimated size is on the export screen. |
| **Not supported on this phone** | The phone's encoder cannot make that size or codec. Tap **Use** on the suggestion, or choose H.264 and a smaller size. |
| **Slow export** | Only a software encoder is available or the hardware encoder is slow for these settings. Continue, or pick the suggested settings. |
| The export slows down | The phone is warm. It slows down on purpose to cool. Let it cool, or take the phone out of its case. |
| **Export Failed**, frame could not be drawn | Try again. If it repeats, lower the resolution or turn **Frame Interpolation** Off. |
| **Export Failed**, the video encoder is busy or cannot make this size | Close other apps that play or record video, then try again. If it repeats, pick a lower resolution or frame rate. |
| **Export Failed**, the video decoder or encoder stopped working | Another app is using the phone's video hardware. Close apps that play or record video and try again. |
| "The phone's video encoder is busy, so this export uses the slower software encoder" | Nothing is wrong: the export takes longer. Close other video apps to get the faster hardware encoder back next time. |
| The finished video shows a still picture with a play button | The phone could not play it right away. Tap the play button to try again; the file itself is saved. |
| The exported video has no sound | A hidden layer, or a clip at 0 % volume, is silent. The line "Checked: ..." on the **Saved to storage** screen reports a missing sound track. |

The export keeps running when you switch to another app, and a notification shows its progress. Closing AbsentMotion from the recent apps list stops it.

## Storage is full

- Delete unused projects from the home screen and old exports from Movies/AbsentMotion.
- **Clear preview copies** in Settings frees up to 2 GB.
- Backups in Documents/AbsentMotion can be large: copy them to a computer and delete them from the phone.
- If saving fails, the app says so. Free space and carry on.

## It is slow

- Lower **Preview Quality**.
- Use the **Fast Blur** option in the preview strip.
- Large photos and videos are heavy. Use preview copies for video, and shrink very large photos before adding them.
- Many layers with effects add up. Hide layers you are not working on (tap the eye).

## The text uses the wrong font

The project uses a font that is not on this phone. Open the project and tap **Download** in **Fonts not on this phone**. Fonts need an internet connection once.

## Questions

**Is it really free?** Yes. There is no subscription, no account, no watermark and no advertising. It is open source under the Apache License 2.0.

**Does it need the internet?** No. Only optional things use it: downloading a Google Font, downloading the caption model once, and the optional update check. See [Privacy](@privacy).

**Where are my projects?** In the app's private storage, with every media file you added. Back them up from Settings. Exports are in Movies, Pictures, Music and Documents under AbsentMotion.

**Can I move a project to another phone?** Yes: export a **Project Bundle**, copy the `.absproj` file to the other phone and open it there.

**Can I open projects from other editors?** Some. See [Projects, backup and import](@projects).

**Is there a computer version?** No. AbsentMotion is made for Android phones.

**Which languages are there?** English, Indonesian, Hindi, Portuguese (Brazil), Spanish, Filipino, Vietnamese, Thai, Malay, Arabic, Turkish and Russian. Change it in Settings.

**How do I report a problem or ask for a feature?** Open an issue on [GitHub](@issues). Please do not include private information in it.

**Where is the source code?** On the [project page on GitHub](@repo).
