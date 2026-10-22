-dontobfuscate
-dontoptimize
-keepclasseswithmembers class * {
    native <methods>;
}
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
