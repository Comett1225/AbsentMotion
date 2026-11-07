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
    inputs.property("versionName", findProperty("amVersionName")?.toString() ?: version["versionName"].orEmpty())
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText("package app.absentmotion\n\nobject BuildConfig {\n    const val APPLICATION_ID = \"app.absentmotion\"\n    const val FLAVOR = \"desktop\"\n    const val DEBUG = false\n    const val COMPATIBLE = false\n    const val VERSION_NAME = \"${findProperty("amVersionName") ?: version["versionName"]}\"\n    const val VERSION_CODE = ${version["versionCode"] ?: "500"}\n}\n") }
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
    implementation("org.jetbrains.runtime:jbr-api:1.5.0")
    if (androidJar != null || System.getenv("AM_ANDROID_API") != null) compileOnly(androidApi)
    runtimeOnly(runtimeStandins.output)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:1.10.3")
}

val os = System.getProperty("os.name").lowercase()
val isWindows = os.startsWith("windows")
val isMac = os.startsWith("mac")
val isLinux = !isWindows && !isMac
val nativeName = when { isWindows -> "amdesktop.dll"; isMac -> "libamdesktop.dylib"; else -> "libamdesktop.so" }
val target = providers.gradleProperty("target").orNull
val macArch = target?.removePrefix("macos-")
val jdkArch = mapOf("x64" to "x86_64", "arm64" to "aarch64")
if (target != null && !(isMac && macArch in jdkArch)) throw GradleException("-Ptarget=$target cannot be built on this system")
if (macArch != null && System.getProperty("os.arch") != jdkArch[macArch]) throw GradleException("-Ptarget=$target needs Gradle on a ${jdkArch[macArch]} JDK 21 (JAVA_HOME; for x64 also arch -x86_64)")
val nativeDir = macArch?.let { "native-$it" } ?: "native"
val gcFlags = listOf("-XX:+UseZGC", "-XX:+ZGenerational", "-XX:MaxRAMPercentage=60")
val appArchive = "\$APPDIR/app-${(System.currentTimeMillis() / 1000).toString(16)}.jsa"
val bundledTools = if (macArch != null) listOf("ffmpeg", "ffprobe").associate { "am.$it" to file("build/$nativeDir/$it").absolutePath } else emptyMap()

val nativeBuild = tasks.register<Exec>("nativeBuild") {
    when {
        isWindows -> commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "../windows/build.ps1")
        isMac -> commandLine(listOfNotNull("sh", "../macos/build.sh", macArch))
        else -> commandLine("sh", "../linux/build.sh")
    }
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    dependsOn(nativeBuild)
    systemProperty("am.assets", file("../shared/assets").absolutePath)
    systemProperty("am.bgmodel", file("../android/src/foss/assets/absent_bg.abg").absolutePath)
    systemProperty("am.native", file("build/$nativeDir/$nativeName").absolutePath)
    systemProperties(bundledTools)
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
            packageVersion = rootDir.resolve("../version.properties").readLines().first { it.startsWith("versionName=") }.substringAfter('=').trim()
            jvmArgs += gcFlags + "-Dapple.awt.application.name=AbsentMotion"
            if (isMac) jvmArgs += "-XX:SharedArchiveFile=\$APPDIR/app.jsa"
            if (isWindows) jvmArgs += listOf("-XX:SharedArchiveFile=$appArchive", "-XX:+AutoCreateSharedArchive", "-Dam.cdsArchive=$appArchive")
            modules("jdk.localedata")
            if (macArch != null) jvmArgs += listOf("ffmpeg", "ffprobe").map { "-Dam.$it=\$APPDIR/resources/$it" }
            macOS {
                bundleID = "app.absentmotion.desktop"
                iconFile.set(file("../macos/AbsentMotion.icns"))
                infoPlist {
                    extraKeysRawXml = "<key>NSCameraUsageDescription</key><string>AbsentMotion uses the camera to take photos and record videos for your project.</string><key>NSMicrophoneUsageDescription</key><string>AbsentMotion uses the microphone to record sound with your videos.</string>"
                }
                fileAssociation("application/x-absentmotion-project", "absproj", "AbsentMotion project")
                fileAssociation("application/x-absentmotion-project", "amproj", "AbsentMotion project")
                fileAssociation("text/xml", "xml", "AbsentMotion scene")
            }
            if (!isMac) vendor = "AbsentMotion"
            windows {
                iconFile.set(file("../windows/AbsentMotion.ico"))
                perUserInstall = true
                dirChooser = true
                menu = true
                shortcut = true
                menuGroup = "AbsentMotion"
                upgradeUuid = "5b0e7a52-3c1f-4d8e-9a6b-2f4c8d1e7a30"
                fileAssociation("application/x-absentmotion-project", "absproj", "AbsentMotion project")
                fileAssociation("application/x-absentmotion-project", "amproj", "AbsentMotion project")
            }
            linux {
                packageName = "absentmotion"
                iconFile.set(file("../linux/AbsentMotion.png"))
                shortcut = true
                menuGroup = "AudioVideo;Video"
                appCategory = "video"
                debMaintainer = "noreply@absentmotion.invalid"
                fileAssociation("application/x-absentmotion-project", "absproj", "AbsentMotion project")
                fileAssociation("application/x-absentmotion-project", "amproj", "AbsentMotion project")
                fileAssociation("text/xml", "xml", "AbsentMotion scene")
            }
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
    from("build/$nativeDir/$nativeName")
    if (macArch != null || isLinux) from("build/$nativeDir") { exclude(nativeName) }
    if (isWindows) from("build/native") { include("icudtl.dat", "av*.dll", "sw*.dll", "ffmpeg.exe", "ffprobe.exe", "ffmpeg-LICENSE.txt") }
}
tasks.matching { it.name == "createDistributable" || it.name.startsWith("prepareAppResources") }.configureEach { dependsOn(prepareResources) }
tasks.matching { Regex("create(Release)?Distributable|package(Release)?(Dmg|Msi|Deb|Exe|Pkg|Rpm)").matches(it.name) }.configureEach { outputs.upToDateWhen { false } }
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    inputs.property("javaHome", System.getProperty("java.home"))
    if (!isMac) doLast {
        val runtime = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
        fun archives() = runtime.walk().filter { it.name.startsWith("classes") && it.extension == "jsa" }.toList()
        archives().forEach { it.setWritable(true); it.delete() }
        val name = if (isWindows) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$name").copyTo(runtime.resolve("bin/$name"), overwrite = true)
        java.setExecutable(true)
        val exit = try {
            ProcessBuilder(listOf(java.path) + gcFlags + "-Xshare:dump").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        } finally { java.delete() }
        val base = archives().firstOrNull()
        logger.lifecycle("start-up archive: " + (base?.let { "${it.relativeTo(runtime)}, ${it.length() / 1_000_000} MB" } ?: "not made (java exit $exit)"))
    }
}
if (macArch != null) tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        val app = layout.buildDirectory.dir("compose/binaries/main/app/AbsentMotion.app").get().asFile
        listOf("ffmpeg", "ffprobe").forEach { check(app.resolve("Contents/app/resources/$it").setExecutable(true, false)) }
        check(ProcessBuilder("codesign", "--force", "--deep", "-s", "-", app.path).inheritIO().start().waitFor() == 0)
    }
}

if (isLinux) tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        listOf("ffmpeg", "ffprobe").map { layout.buildDirectory.file("compose/binaries/main/app/AbsentMotion/lib/app/resources/$it").get().asFile }
            .filter { it.isFile }.forEach { check(it.setExecutable(true, false)) }
    }
}

val renderCheck = tasks.register<JavaExec>("renderCheck") {
    dependsOn(nativeBuild, tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "app.absentmotion.pc.MainKt"
    args("--render-check")
    systemProperty("java.awt.headless", "true")
    systemProperty("am.assets", file("../shared/assets").absolutePath)
    systemProperty("am.bgmodel", file("../android/src/foss/assets/absent_bg.abg").absolutePath)
    systemProperty("am.native", file("build/$nativeDir/$nativeName").absolutePath)
    systemProperties(bundledTools)
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
    systemProperty("am.native", file("build/$nativeDir/$nativeName").absolutePath)
    systemProperties(bundledTools)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("am.output", file("build").absolutePath)
}

tasks.test {
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    systemProperty("am.onboarding", "done")
    systemProperty("am.whatsNew", "off")
    systemProperty("am.fontOffer", "off")
    systemProperty("am.preload", "off")
    systemProperty("am.shell", "off")
    systemProperty("am.data", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    dependsOn(nativeBuild)
    inputs.file(layout.buildDirectory.file("$nativeDir/$nativeName"))
    systemProperty("am.native", layout.buildDirectory.file("$nativeDir/$nativeName").get().asFile.absolutePath)
    systemProperties(bundledTools)
    systemProperty("am.assets", file("../shared/assets").absolutePath)
    systemProperty("am.bgmodel", file("../android/src/foss/assets/absent_bg.abg").absolutePath)
    systemProperty("am.repo", file("..").absolutePath)
    systemProperty("skiko.data.path", layout.buildDirectory.dir("skiko").get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
}

tasks.named<Jar>("jar") { from(runtimeStandins.output) }
tasks.processResources { from("../windows/AbsentMotion.ico") }

tasks.register("installMac") {
    onlyIf { isMac }
    dependsOn("createDistributable")
    doLast {
        val built = layout.buildDirectory.dir("compose/binaries/main/app/AbsentMotion.app").get().asFile
        val app = File(providers.gradleProperty("installDir").getOrElse("/Applications"), "AbsentMotion.app")
        if (ProcessHandle.allProcesses().anyMatch { it.info().command().orElse("").startsWith(app.path + "/") }) throw GradleException("Quit AbsentMotion first: ${app.path} is running")
        fun run(vararg command: String) = check(ProcessBuilder(*command).inheritIO().start().waitFor() == 0) { command.joinToString(" ") }
        run("rm", "-rf", app.path)
        run("ditto", built.path, app.path)
        val launcher = app.resolve("Contents/MacOS/AbsentMotion")
        val archive = app.resolve("Contents/app/app.jsa")
        val list = layout.buildDirectory.file("cds/classes.lst").get().asFile.apply { parentFile.mkdirs(); delete() }
        fun launch(options: String) = ProcessBuilder(launcher.path, "--warmup").apply { environment()["JAVA_TOOL_OPTIONS"] = options }
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        launch("-XX:DumpLoadedClassList=${list.path}")
        if (list.length() > 0) launch("-Xshare:dump -XX:SharedClassListFile=${list.path} -XX:SharedArchiveFile=${archive.path}")
        run("codesign", "--force", "--deep", "-s", "-", app.path)
        logger.lifecycle("installMac: ${app.path}, " + if (archive.isFile) "start-up archive ${archive.length() / 1_000_000} MB" else "no start-up archive")
    }
}

