# shared

Code that every AbsentMotion platform runs.

- `kotlin/`: the Compose UI, editor, project model, export and audio code.
- `cpp/`: the C++ engine on Skia.
- `assets/`: effects, fonts, shapes, emoji and help pages.
- `res/values*/strings.xml`: every UI text, in all languages.

Android (`../android`) and the desktop build (`../pc`) both compile this folder. Differences between platforms go through
`platform/AndroidPlatform.kt` (in `../android`) and `platform/DesktopPlatform.kt` (in `../pc`), which declare the same functions.
Desktop-only UI is wrapped in `PlatformFeatures.desktop`, which is `false` on Android.
