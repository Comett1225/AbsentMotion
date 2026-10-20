# Privacy policy

AbsentMotion is a free, open-source video and motion graphics editor for Android. It does not collect, store or share personal
data. There is no account, no advertising, no analytics and no tracking.

## Your projects and media

Projects, imported pictures, videos and audio, and everything you export stay on your device. The photo, video and audio
permissions let the app show your library and use the files you pick, on the device. Nothing is uploaded.

The camera and microphone permissions are used only by the in-app camera, while it is open, to take the photos and
videos you add to a project. Recordings are saved on the device. Backups of your projects are written to
`Documents/AbsentMotion` on the device and stay there.

## When the app uses the internet

The app asks for the internet permission for these things only:

- **Fonts.** When you pick one of the downloadable fonts, the app downloads it from Google Fonts (`fonts.googleapis.com`, `fonts.gstatic.com`).
  Google receives the request for that font and your IP address, as for any web request.
- **Auto captions.** The first time you generate captions, the app tells you the size of the speech model (about 60 MB) and
  downloads it from Hugging Face (`huggingface.co`). Speech recognition itself runs on the device.
- **Update check.** While "Check for a new version each time the app opens" is on (the app asks you once, on its first screens or at the first start after an update, and the switch is also in Settings), and when you tap Check for Update, the app makes one normal HTTPS request to GitHub (`api.github.com`) to read the latest version number; nothing else is sent, and GitHub sees your IP address, as for any web request.

Nothing else is sent anywhere. Text to speech uses the voices already installed on your device and works offline.

## The Google Play build

The `play` build published on the GitHub releases page removes backgrounds with Google ML Kit. This also runs on the device.
The build without Google libraries (`foss`) removes backgrounds with its own offline model.

## Children

The app is not directed at children and collects nothing from anyone.

## Changes and contact

If this policy changes, the new text appears in this file with the release that changes it. Questions can be asked through
the issues page of the repository: https://github.com/Comett1225/AbsentMotion/issues
