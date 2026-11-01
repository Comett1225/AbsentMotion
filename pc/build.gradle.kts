plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.10.3"
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("src/shared-shims")
        kotlin.srcDir("../app/src/main/kotlin")
        kotlin.include("app/absentmotion/pc/**", "app/absentmotion/model/**", "app/absentmotion/compat/ForeignEffectIds.kt", "app/absentmotion/effects/EffectDefinition.kt", "desktop_shims/**")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.json:json:20250517")
    implementation("net.sf.kxml:kxml2:2.3.0")
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
