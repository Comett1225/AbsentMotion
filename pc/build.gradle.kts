plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.10.3"
}

val androidJar = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: rootDir.resolve("../local.properties").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=") ?: "${System.getProperty("user.home")}/Library/Android/sdk")
    .let { sdk -> file(sdk).resolve("platforms").listFiles().orEmpty().filter { it.resolve("android.jar").isFile }.maxByOrNull { it.name }?.resolve("android.jar") }

val androidApiJar = tasks.register<Jar>("androidApiJar") {
    destinationDirectory.set(layout.buildDirectory.dir("android-api"))
    archiveFileName.set("android-api.jar")
    if (androidJar != null) from(zipTree(androidJar)) { include("android/**") }
}
val androidApi = System.getenv("AM_ANDROID_API")?.let { files(it) } ?: files(layout.buildDirectory.file("android-api/android-api.jar")).builtBy(androidApiJar)

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("src/standins/kotlin")
        kotlin.srcDir("../shared/kotlin")
        kotlin.srcDir(layout.buildDirectory.dir("generated/strings"))
    }
}

val runtimeStandins: SourceSet = sourceSets.create("runtimeStandins")

val generateStrings = tasks.register("generateStrings") {
    val source = file("../shared/res/values/strings.xml")
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
    inputs.property("versionName", findProperty("amVersionName")?.toString() ?: "5.0.0 pre")
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText("package app.absentmotion\n\nobject BuildConfig {\n    const val APPLICATION_ID = \"app.absentmotion\"\n    const val FLAVOR = \"desktop\"\n    const val DEBUG = false\n    const val COMPATIBLE = false\n    const val VERSION_NAME = \"${findProperty("amVersionName") ?: "5.0.0 pre"}\"\n    const val VERSION_CODE = ${version["versionCode"] ?: "500"}\n}\n") }
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
    if (androidJar != null || System.getenv("AM_ANDROID_API") != null) compileOnly(androidApi)
    runtimeOnly(runtimeStandins.output)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:1.10.3")
}

val os = System.getProperty("os.name").lowercase()
val isWindows = os.startsWith("windows")
val isMac = os.startsWith("mac")
val nativeName = when { isWindows -> "amdesktop.dll"; isMac -> "libamdesktop.dylib"; else -> "libamdesktop.so" }

val nativeBuild = tasks.register<Exec>("nativeBuild") {
    when {
        isWindows -> commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "../windows/build.ps1")
        isMac -> commandLine("sh", "../macos/build.sh")
        else -> commandLine("sh", "../linux/build.sh")
    }
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    dependsOn(nativeBuild)
    systemProperty("am.assets", file("../shared/assets").absolutePath)
    systemProperty("am.bgmodel", file("../android/src/foss/assets/absent_bg.abg").absolutePath)
    systemProperty("am.native", file("build/native/$nativeName").absolutePath)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("skiko.data.path", file("build/skiko").absolutePath)
    systemProperty("apple.awt.application.name", "AbsentMotion")
    jvmArgs("-XX:+UseZGC", "-XX:+ZGenerational", "-XX:MaxRAMPercentage=60")
    System.getenv("AM_DATA")?.let { systemProperty("am.data", it) }
    System.getenv("AM_LOG")?.let { systemProperty("am.log", it) }
}

compose.desktop {
    application {
        mainClass = "app.absentmotion.pc.MainKt"
        nativeDistributions {
            targetFormats(when {
                isWindows -> org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
                isMac -> org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg
                else -> org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            })
            packageName = "AbsentMotion"
            packageVersion = "5.0.0"
            jvmArgs += listOf("-XX:+UseZGC", "-XX:+ZGenerational", "-XX:MaxRAMPercentage=60", "-Dapple.awt.application.name=AbsentMotion")
            if (isMac) jvmArgs += "-XX:SharedArchiveFile=\$APPDIR/app.jsa"
            macOS { bundleID = "app.absentmotion.desktop"; iconFile.set(file("../macos/AbsentMotion.icns")) }
            windows { menuGroup = "AbsentMotion"; upgradeUuid = "5b0e7a52-3c1f-4d8e-9a6b-2f4c8d1e7a30" }
            linux { packageName = "absentmotion" }
            appResourcesRootDir.set(project.layout.buildDirectory.dir("app-resources"))
        }
    }
}

val prepareResources = tasks.register<Sync>("prepareResources") {
    dependsOn(nativeBuild)
    into(layout.buildDirectory.dir("app-resources/common"))
    from("../shared/assets") { into("assets") }
    from("../android/src/foss/assets/absent_bg.abg") { into("assets") }
    from("../shared/res") { into("res"); include("values*/strings.xml") }
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
    systemProperty("am.assets", file("../shared/assets").absolutePath)
    systemProperty("am.bgmodel", file("../android/src/foss/assets/absent_bg.abg").absolutePath)
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
    systemProperty("am.assets", file("../shared/assets").absolutePath)
    systemProperty("am.bgmodel", file("../android/src/foss/assets/absent_bg.abg").absolutePath)
    systemProperty("am.native", file("build/native/$nativeName").absolutePath)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("am.output", file("build").absolutePath)
}

tasks.test {
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    systemProperty("am.data", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    dependsOn(nativeBuild)
    inputs.file(layout.buildDirectory.file("native/$nativeName"))
    systemProperty("am.native", layout.buildDirectory.file("native/$nativeName").get().asFile.absolutePath)
    systemProperty("am.assets", file("../shared/assets").absolutePath)
    systemProperty("am.bgmodel", file("../android/src/foss/assets/absent_bg.abg").absolutePath)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("skiko.data.path", layout.buildDirectory.dir("skiko").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}

tasks.named<Jar>("jar") { from(runtimeStandins.output) }

val appCds = tasks.register("appCds") {
    onlyIf { isMac }
    doLast {
        val app = layout.buildDirectory.dir("compose/binaries/main/app/AbsentMotion.app").get().asFile
        val launcher = app.resolve("Contents/MacOS/AbsentMotion")
        val archive = app.resolve("Contents/app/app.jsa")
        val list = layout.buildDirectory.file("cds/classes.lst").get().asFile.apply { parentFile.mkdirs(); delete() }
        archive.delete()
        fun launch(options: String, vararg args: String) = ProcessBuilder(launcher.path, *args).apply { environment()["JAVA_TOOL_OPTIONS"] = options }
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        launch("-XX:DumpLoadedClassList=${list.path}", "--warmup")
        if (list.length() > 0) launch("-Xshare:dump -XX:SharedClassListFile=${list.path} -XX:SharedArchiveFile=${archive.path}")
        logger.lifecycle(if (archive.isFile) "appCds: ${archive.length() / 1_000_000} MB, ${list.readLines().size} classes" else "appCds: no archive (the app starts without it)")
    }
}
tasks.matching { it.name == "createDistributable" }.configureEach { finalizedBy(appCds) }
tasks.matching { it.name == "packageDmg" }.configureEach { dependsOn(appCds) }

