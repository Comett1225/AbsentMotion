---
title: AI tools
group: Sound and AI
order: 2
summary: Auto captions, background removal and noise reduction. They run on your phone, offline, and the page says exactly what is downloaded.
---
Everything in this page **runs on your phone**. Your photos, videos and sound are never uploaded. The only downloads are listed in the table at the end.

The AI tools are in two places: the **AI** category of the Effects Library (**Remove Background**, **Remove Background (video)** and **Reduce Noise**), and the **Captions** button of the Add menu. For a sound clip, the **AI** button in the tool grid opens the noise tool. A tool that does not fit the selected layer is grayed with a note such as "Select an image layer". One AI job runs at a time, with a progress bar and a **Cancel** button.

## Auto captions

Auto captions turns speech into text layers that appear in time with the voice.

1. Tap **+**, then **Captions**. The **Auto Captions** sheet opens.
2. Choose the **Source**: the **Whole project** or one sound or video layer. It starts on the selected layer if that layer has sound.
3. Choose the settings below and tap **Generate**.

| Setting | Choices |
|---|---|
| **Language** | **Auto** (the speech decides the language) or **English** |
| **Font** | Opens the font browser |
| **Size** | 50 to 200 % (default 100 %) |
| **Color** | Eight swatches: white (default), yellow, orange, red, pink, green, blue and black |
| **Outline** | A black border around the letters, on by default |
| **Position** | **Top**, **Middle** or **Bottom** (default) |
| **Words per caption** | 1 to 10 (default 4) |

The first time, the app tells you it needs a speech model: "Captions are recognized on your phone by a speech model, downloaded once", with its name and size, about 57 MB. Tap **Download** (or **Not now**). The model is the Whisper base model, downloaded from Hugging Face over a normal secure connection and checked before use. After that, recognition runs on the phone's processor with no internet. In **Settings**, **Remove caption model** deletes it again.

While it works the sheet shows "Reading the audio..." and "Recognizing speech..." with a percentage. Long recordings are processed in ten-minute parts. The result is one text layer per caption, named "Caption 1", "Caption 2" and so on, centerd, in time with the speech and selected together so that you can restyle them all at once. A caption ends at a pause longer than half a second, at the end of a sentence, after three seconds or at the word limit.

If there is no sound the app says "There is no sound to caption", and if no speech is found, "No speech was found".

## Remove Background

### Photos

Select a photo layer and open **Effects**, **Add Effect**, **AI**, **Remove Background**. The app cuts out the main subject and replaces the picture with a version with a transparent background. The original file is kept: the effect list shows a **Remove Background** row with an AI badge, and its button (**Undo this AI change and bring back the original**) restores the original picture.

### Videos

Select a video layer and choose **Remove Background (video)**. A dialog says "Every frame of this clip is processed on this phone. The result is a group with the video and a mask layer above it." You choose the size of the mask: **Small 360p**, **Medium 540p** (default) or **Large 720p**, and the dialog estimates the number of frames and the storage needed. Tap **Start**.

- A clip can be up to five minutes. For longer clips, trim or split it first.
- The app needs free storage for the mask video and warns if there is not enough.
- The result is a group called "name cut out" holding the original clip and a mask layer. To undo it, delete or ungroup the group; this tool has no restore button.

### Which build?

- The **standard build** (`-foss.apk`, recommended) has its own small offline model inside the app (about 3 MB). Nothing is downloaded and no Google libraries are used.
- The **Play-services build** (`-play.apk`) uses Google Play services. Google Play services downloads its own model the first time (so you need an internet connection once) and may send diagnostics to Google under Google's own policy. If the model is not ready, the app says "The background removal model is not ready yet. Connect to the internet and retry shortly; first use needs Google Play services."

## Reduce Noise

Select a sound clip and tap **AI** in the tool grid (or **Reduce Noise** in the AI category). Choose what the noise is:

- **Voice recording**: "Removes the background noise behind speech."
- **Wind and rumble**: "Cuts low rumble and wind, then cleans up the rest."
- **Music or mixed sound**: "A gentle clean-up that keeps more of the original."

Tap **Start**. The cleaned sound is saved as a new audio file for the clip, in mono, and the original stays untouched: the clip's effect list shows a **Reduce Noise** row with an AI badge and a button to bring back the original. For a video, use **Extract Audio** first and then clean the new audio layer.

## What is downloaded

| Item | When | Size | From |
|---|---|---|---|
| Speech model for captions | The first time you generate captions, after you tap Download | about 57 MB | Hugging Face |
| Background removal model, standard build | Never: it is inside the app | about 3 MB | |
| Background removal model, Play-services build | First use | Not shown by the app | Google Play services |
| Noise reduction | Never: it is inside the app | | |

Nothing else is downloaded for AI. The [Privacy](@privacy) page lists every use of the internet.

## Speed

The tools use the phone's processor and graphics chip. A short clip on a recent phone takes seconds to minutes; a long video or an older phone takes longer. Keep the app open and the screen on while a tool runs.
