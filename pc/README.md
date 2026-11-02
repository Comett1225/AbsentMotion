# pc

The desktop layer that macOS, Windows and Linux share. It compiles `../shared/kotlin` with Compose Multiplatform and adds:

- `src/standins/kotlin/android/**`: desktop versions of the Android classes the shared code uses.
- `src/standins/kotlin/app/absentmotion/desk/`: the window, menus, shortcuts and files. `DeskOs.kt` holds every difference
  between desktop systems.
- `native/`: the JNI glue, video decoding, the Vulkan GPU code (Windows and Linux) and the one `CMakeLists.txt` for every
  desktop. Each system adds its own part from `../macos/native`, `../windows/native` or `../linux/native`.

Build and run (JDK 21):

    ./gradlew run
    ./gradlew test
    ./gradlew createDistributable

The native library is built by `../macos/build.sh`, `../windows/build.ps1` or `../linux/build.sh`, whichever matches the system.
