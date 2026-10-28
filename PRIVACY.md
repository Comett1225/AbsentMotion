# Privacy policy

AbsentMotion is a free, open-source video and motion graphics editor for Android. It does not collect, store or share personal
data. There is no account, no advertising, no analytics and no tracking.

## Your projects and media

Projects, imported pictures, videos and audio, and everything you export stay on your device. The photo, video and audio
permissions let the app show your library and use the files you pick, on the device. Nothing is uploaded.

The camera and microphone permissions are used only by the in-app camera, while it is open, to take the photos and
videos you add to a project. Recordings are saved on the device. Backups of your projects are written to
`Documents/AbsentMotion` on the device and stay there.

While an export runs, the app shows its progress in a notification, so the export can continue when you switch to another app.
On Android 13 and newer it asks for the notification permission at the first export; exports work the same if you say no.

If the app closes unexpectedly, it keeps a crash log in its own storage on the device: when it happened, the reason in one sentence,
the app and Android versions, the phone model, what you were doing and the technical error. Only the last 20 are kept, they are not
included in phone backups and they are never sent anywhere; you can read or copy them in Settings under Crash logs.

## When the app uses the internet

The app asks for the internet permission for these things only:

- **Fonts.** When you pick one of the downloadable fonts, the app downloads it from Google Fonts (`fonts.googleapis.com`, `fonts.gstatic.com`).
  Google receives the request for that font and your IP address, as for any web request.
- **Auto captions.** The first time you generate captions, the app tells you the size of the speech model (about 60 MB) and
  downloads it from Hugging Face (`huggingface.co`). Speech recognition itself runs on the device.
- **AI Enhance and AI Smooth.** The first time you use one of them, the app tells you the size of the model it needs (about 1 to
  11 MB) and downloads it from GitHub (`github.com` and `raw.githubusercontent.com`). The AI work itself runs on the device.
- **AI background removal 2.0.** Only if you choose AI background removal 2.0 for removing backgrounds, the app downloads its
  model once (about 62 MB) from GitHub (`github.com`). The cut-out itself runs on the device.
- **Update check.** While "Check for a new version each time the app opens" is on (the app asks you once, on its first screens or at the first start after an update, and the switch is also in Settings), and when you tap Check for Update, the app makes one normal HTTPS request to GitHub (`api.github.com`) to read the latest version number; nothing else is sent, and GitHub sees your IP address, as for any web request. Only if a newer version is shown and you tap Download update, the app downloads that version's app file (about 60 MB) from the GitHub release (`github.com`, which serves it from `release-assets.githubusercontent.com` or `objects.githubusercontent.com`), and for older releases its `SHA256SUMS.txt`; it is installed only after you confirm it in Android's installer.

Apart from the Google Play build described below, nothing else is sent anywhere. Text to speech uses the voices already installed on your device and works offline.

## The Google Play build

The `play` build published on the GitHub releases page removes backgrounds with Google ML Kit, through Google Play services.
The removal itself runs on the device, but Google Play services (not AbsentMotion) downloads the model when it is first needed
or earlier, and may send diagnostics to Google (such as device information, performance figures and error codes) under Google's
own policy. The build without Google libraries (`foss`) is not affected: its own offline model is part of the app, so
nothing is downloaded or sent for background removal.

## Children

The app is not directed at children and collects nothing from anyone.

## Changes and contact

If this policy changes, the new text appears in this file with the release that changes it. Questions can be asked through
the issues page of the repository: https://github.com/Comett1225/AbsentMotion/issues
