---
title: Privacy and license
group: Help
order: 2
summary: What the app keeps, what it sends, and the license it is released under.
---
AbsentMotion does not collect, store or share personal data. There is no account, no advertising, no analytics and no tracking. This page summarizes the privacy policy; the full text is in the file [PRIVACY.md](@privacy-file) of the source code.

## Your projects and media

Projects, imported pictures, videos and audio, and everything you export stay on your phone. The photo, video and audio permissions let the app show your library and use the files you pick, on the phone. Nothing is uploaded.

The camera and microphone permissions are only used by the in-app camera, while it is open. Recordings are saved on the phone. Backups are written to Documents/AbsentMotion on the phone and stay there.

While an export runs, the app shows its progress in a notification, so the export can continue when you switch to another app. On Android 13 and newer it asks for the notification permission at the first export; exports work the same if you say no.

While an export runs, the app shows its progress in a notification, so the export can continue when you switch to another app. On Android 13 and newer it asks for the notification permission at the first export; exports work the same if you say no.

If the app closes unexpectedly it keeps a crash log in its own storage: when it happened, the reason in one sentence, the app and Android versions, the phone model, what you were doing and the technical error. Only the last 20 are kept, they are not included in phone backups and they are never sent anywhere. You can read, copy or delete them in Settings.

## When the app uses the internet

The app asks for the internet permission for these things only:

- **Fonts.** When you pick one of the downloadable fonts, the app downloads it from Google Fonts. Google receives the request for that font and your IP address, as for any web request.
- **Auto captions.** The first time you generate captions, the app tells you the size of the speech model (about 57 MB) and downloads it from Hugging Face. Recognizing speech runs on the phone.
- **AI Enhance and AI Smooth.** The first time you use one of them, the app tells you the size of the model it needs (about 1 to 11 MB) and downloads it from GitHub. The AI work itself runs on the phone.
- **Best quality cut-out.** Only if you choose Best quality for removing backgrounds, the app downloads its model once (about 62 MB) from GitHub. The cut-out itself runs on the phone.
- **Update check.** If you allow it (the app asks once; the switch is in Settings), the app makes one normal HTTPS request to GitHub each time it opens, to read the latest version number. Nothing else is sent. GitHub sees your IP address, as for any web request.

Apart from the Play-services build described below, nothing else is sent anywhere. Text to speech uses the voices already on the phone and works offline.

## The Play-services build

The build with Google Play services (`-play.apk`) removes backgrounds with Google ML Kit. The removal itself runs on the phone, but Google Play services downloads the model when it is first needed and may send diagnostics to Google, such as device information, performance figures and error codes, under Google's own policy. The standard build (`-foss.apk`) has no Google libraries: its own offline model is part of the app, so nothing is downloaded or sent for background removal.

## Children

The app is not directed at children and collects nothing from anyone.

## License and source

AbsentMotion is free and open source, released under the [Apache License 2.0](@license-file). Its source code is on the [project page on GitHub](@repo). The third-party software it uses (Skia, whisper.cpp, RNNoise, LAME, AndroidX Compose and others) and their licenses are listed in [THIRD_PARTY_NOTICES.md](@notices-file) and in the app: Settings, About, **Open source licenses**.

AbsentMotion is an independent project. It is not affiliated with, endorsed by or sponsored by any other app maker; names of other products belong to their owners.

## Questions

Ask through the [issues page](@issues) of the repository.
