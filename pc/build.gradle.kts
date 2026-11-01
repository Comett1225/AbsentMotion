plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.10.3"
}

val phoneOnly = listOf(
    "app/absentmotion/MainActivity.kt", "app/absentmotion/ImportActivity.kt",
    "app/absentmotion/ui/camera/CameraScreen.kt",
    "app/absentmotion/platform/AndroidPlatform.kt", "app/absentmotion/platform/OldAndroid.kt", "app/absentmotion/platform/StorageAccess.kt",
    "app/absentmotion/engine/EngineThread.kt", "app/absentmotion/engine/NativeEngine.kt",
    "app/absentmotion/fonts/TextLayout.kt",
    "app/absentmotion/audio/AudioEngine.kt", "app/absentmotion/audio/PcmDecoder.kt",
    "app/absentmotion/i18n/AppLanguage.kt", "app/absentmotion/i18n/UiText.kt",
    "app/absentmotion/ui/home/UpdateDialog.kt", "app/absentmotion/media/VideoLibrary.kt",
    "app/absentmotion/ui/home/Updater.kt", "app/absentmotion/ui/home/UpdateVerify.kt", "app/absentmotion/ui/home/UnsupportedPhone.kt",
    "app/absentmotion/export/ExportService.kt", "app/absentmotion/ui/tutorial/Grip.kt", "app/absentmotion/ui/editor/panels/EffectRuler.kt",
)

val androidJar = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: rootDir.resolve("../local.properties").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=") ?: "${System.getProperty("user.home")}/Library/Android/sdk")
    .let { sdk -> file(sdk).resolve("platforms").listFiles().orEmpty().filter { it.resolve("android.jar").isFile }.maxByOrNull { it.name }?.resolve("android.jar") }

val androidApiJar = tasks.register<Jar>("androidApiJar") {
    destinationDirectory.set(layout.buildDirectory.dir("android-api"))
    archiveFileName.set("android-api.jar")
    if (androidJar != null) from(zipTree(androidJar)) { include("android/**") }
}
val androidApi = files(layout.buildDirectory.file("android-api/android-api.jar")).builtBy(androidApiJar)

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("src/standins/kotlin")
        kotlin.srcDir("../app/src/main/kotlin")
        kotlin.srcDir("../app/src/foss/kotlin")
        kotlin.srcDir(layout.buildDirectory.dir("generated/strings"))
        kotlin.exclude(phoneOnly)
    }
}

val generateStrings = tasks.register("generateStrings") {
    val source = file("../app/src/main/res/values/strings.xml")
    val out = layout.buildDirectory.file("generated/strings/app/absentmotion/R.kt")
    inputs.file(source)
    outputs.file(out)
    doLast {
        val text = source.readText()
        val names = Regex("<(string|plurals)\\s+name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val strings = names.filter { it.first == "string" }.map { it.second }.distinct()
        val plurals = names.filter { it.first == "plurals" }.map { it.second }.distinct()
        val body = StringBuilder("package app.absentmotion\n\nobject R {\n    object string {\n")
        strings.forEachIndexed { i, n -> body.append("        const val $n = ${0x7f100000 + i}\n") }
        body.append("    }\n    object plurals {\n")
        plurals.forEachIndexed { i, n -> body.append("        const val $n = ${0x7f110000 + i}\n") }
        body.append("    }\n    object drawable {\n        const val ic_notification = 0x7f080000\n    }\n}\n\nobject RNames {\n    val strings = arrayOf(")
        body.append(strings.joinToString(",") { "\"$it\"" }).append(")\n    val plurals = arrayOf(").append(plurals.joinToString(",") { "\"$it\"" }).append(")\n}\n")
        out.get().asFile.apply { parentFile.mkdirs(); writeText(body.toString()) }
    }
}
tasks.matching { it.name == "compileKotlin" }.configureEach { dependsOn(generateStrings) }

val generateBuildConfig = tasks.register("generateBuildConfig") {
    val out = layout.buildDirectory.file("generated/strings/app/absentmotion/BuildConfig.kt")
    val version = rootDir.resolve("../version.properties").takeIf { it.isFile }?.readLines()?.associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }.orEmpty()
    inputs.property("version", version.toString())
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText("package app.absentmotion\n\nobject BuildConfig {\n    const val APPLICATION_ID = \"app.absentmotion\"\n    const val FLAVOR = \"desktop\"\n    const val DEBUG = false\n    const val COMPATIBLE = false\n    const val VERSION_NAME = \"${version["versionName"] ?: "5.0.0"}\"\n    const val VERSION_CODE = ${version["versionCode"] ?: "500"}\n}\n") }
    }
}
tasks.matching { it.name == "compileKotlin" }.configureEach { dependsOn(generateBuildConfig) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
    implementation("org.json:json:20250517")
    implementation("net.sf.kxml:kxml2:2.3.0")
    if (androidJar != null) compileOnly(files(androidApi))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:1.10.3")
}

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeName = if (isWindows) "amdesktop.dll" else "libamdesktop.dylib"

val nativeBuild = tasks.register<Exec>("nativeBuild") {
    if (isWindows) commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "native/build_win.ps1")
    else commandLine("sh", "native/build_mac.sh")
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    dependsOn(nativeBuild)
    systemProperty("am.assets", file("../app/src/main/assets").absolutePath)
    systemProperty("am.native", file("build/native/$nativeName").absolutePath)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("skiko.data.path", file("build/skiko").absolutePath)
    System.getenv("AM_DATA")?.let { systemProperty("am.data", it) }
    System.getenv("AM_LOG")?.let { systemProperty("am.log", it) }
}

compose.desktop {
    application {
        mainClass = "app.absentmotion.pc.MainKt"
        nativeDistributions {
            targetFormats(if (isWindows) org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi else org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "AbsentMotion"
            packageVersion = "5.0.0"
            macOS { bundleID = "app.absentmotion.desktop" }
            appResourcesRootDir.set(project.layout.buildDirectory.dir("app-resources"))
        }
    }
}

val prepareResources = tasks.register<Sync>("prepareResources") {
    dependsOn(nativeBuild)
    into(layout.buildDirectory.dir("app-resources/common"))
    from("../app/src/main/assets") { into("assets") }
    from("build/native/$nativeName")
    if (isWindows) from("build/native/icudtl.dat")
}
tasks.matching { it.name == "createDistributable" || it.name.startsWith("prepareAppResources") }.configureEach { dependsOn(prepareResources) }

val renderCheck = tasks.register<JavaExec>("renderCheck") {
    dependsOn(nativeBuild, tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "app.absentmotion.pc.MainKt"
    args("--render-check")
    systemProperty("java.awt.headless", "true")
    systemProperty("am.assets", file("../app/src/main/assets").absolutePath)
    systemProperty("am.native", file("build/native/$nativeName").absolutePath)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("am.output", file("build").absolutePath)
}

val sceneCheck = tasks.register<JavaExec>("sceneCheck") {
    dependsOn(nativeBuild, tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "app.absentmotion.pc.MainKt"
    args("--scene-check")
    systemProperty("java.awt.headless", "true")
    systemProperty("am.assets", file("../app/src/main/assets").absolutePath)
    systemProperty("am.native", file("build/native/$nativeName").absolutePath)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("am.output", file("build").absolutePath)
}

tasks.test {
    dependsOn(nativeBuild)
    systemProperty("am.native", layout.buildDirectory.file("native/$nativeName").get().asFile.absolutePath)
    systemProperty("am.assets", file("../app/src/main/assets").absolutePath)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("skiko.data.path", layout.buildDirectory.dir("skiko").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}
