# android

The Android app (phones and tablets). Gradle module `:app`, built from the repository root:

    ./gradlew :app:assembleFossDebug
    ./gradlew :app:testFossDebugUnitTest

This folder holds what only Android uses: the manifest, activities and services, the Android engine thread, the updater, the
camera, launcher icons, the `foss`, `play`, `compat` and `compat32` flavours, and the unit tests. Everything shared with the
desktop is in `../shared`. See `../docs/BUILD.md`.
