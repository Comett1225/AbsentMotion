import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appAbi = providers.gradleProperty("amAbi").getOrElse("arm64-v8a")
require(appAbi in setOf("arm64-v8a", "x86_64")) { "amAbi must be arm64-v8a or x86_64, not $appAbi" }

android {
    namespace = "app.absentmotion"

    packaging {
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }

    androidResources {
        ignoreAssetsPatterns += listOf("!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~", "!absent_bg.abg")
    }
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.absentmotion"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        buildConfigField("boolean", "COMPATIBLE", "false")
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../shared/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    val signingFile = rootProject.file("signing.properties")
    if (signingFile.isFile) {
        val keys = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            vcsInfo.include = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), file("proguard-rules.pro"))
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        baseline = file("lint-baseline.xml")
        checkReleaseBuilds = false
        warning += "MissingTranslation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            manifestPlaceholders["variant"] = "foss"
            ndk { abiFilters += appAbi }
        }
        create("compat") {
            dimension = "distribution"
            manifestPlaceholders["variant"] = "compat"
            minSdk = 26
            ndk { abiFilters += appAbi }
            buildConfigField("boolean", "COMPATIBLE", "true")
            externalNativeBuild { cmake { arguments += "-DAM_COMPAT=ON" } }
        }
        create("compat32") {
            dimension = "distribution"
            manifestPlaceholders["variant"] = "compat32"
            minSdk = 26
            ndk { abiFilters += "armeabi-v7a" }
            buildConfigField("boolean", "COMPATIBLE", "true")
            externalNativeBuild { cmake { arguments += "-DAM_COMPAT=ON" } }
        }
    }

    sourceSets {
        named("main") {
            kotlin.directories += "../shared/kotlin"
            res.directories += "../shared/res"
        }
        named("compat") {
            kotlin.directories += "src/foss/kotlin"
            assets.directories += "src/foss/assets"
        }
        named("testCompat") { kotlin.directories += "src/testFoss/kotlin" }
        named("compat32") {
            kotlin.directories += "src/foss/kotlin"
            assets.directories += "src/foss/assets"
            res.directories += "src/compat/res"
            manifest.srcFile("src/compat/AndroidManifest.xml")
        }
        named("testCompat32") { kotlin.directories += "src/testFoss/kotlin" }
    }
}

abstract class PackLanguages : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val slow: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun pack() {
        val from = source.get().asFile
        val to = output.get().asFile
        to.deleteRecursively()
        val lighter = slow.orNull?.asFile
        val languages = from.resolve("help").listFiles().orEmpty().filter { it.isDirectory && it.name !in setOf("assets", "effects") }.map { it.name }.sorted()
        val packed = languages.map { "help/$it/" } + "effects/i18n/"
        from.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { file ->
            val path = file.relativeTo(from).invariantSeparatorsPath
            if (packed.none(path::startsWith)) file.copyTo(to.resolve(path))
        }
        lighter?.walkTopDown()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { it.copyTo(to.resolve(it.relativeTo(lighter).invariantSeparatorsPath), overwrite = true) }
        for (language in languages) {
            val files = (from.resolve("help/$language").walkTopDown() + from.resolve("effects/i18n/$language.json"))
                .filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(from).invariantSeparatorsPath to it.readBytes() }.sortedBy { it.first }.toList()
            val digest = MessageDigest.getInstance("SHA-256")
            files.forEach { (path, bytes) -> digest.update(path.toByteArray()); digest.update(bytes) }
            val target = to.resolve("langpacks/$language.pack").apply { parentFile.mkdirs() }
            val zipped = object : GZIPOutputStream(target.outputStream().buffered()) { init { def.setLevel(Deflater.BEST_COMPRESSION) } }
            DataOutputStream(zipped).use { out ->
                out.writeUTF(digest.digest().joinToString("") { "%02x".format(it) }.take(16))
                for ((path, bytes) in files) { out.writeUTF(path); out.writeInt(bytes.size); out.write(bytes) }
                out.writeUTF("")
            }
        }
    }
}

val packLanguages = tasks.register<PackLanguages>("packLanguages") {
    source.set(rootProject.layout.projectDirectory.dir("shared/assets"))
    output.set(layout.buildDirectory.dir("generated/amAssets"))
}

val packLanguagesCompat = tasks.register<PackLanguages>("packLanguagesCompat") {
    source.set(rootProject.layout.projectDirectory.dir("shared/assets"))
    slow.set(layout.projectDirectory.dir("src/compat/slow-assets"))
    output.set(layout.buildDirectory.dir("generated/amAssetsCompat"))
}

androidComponents {
    onVariants { variant ->
        val pack = if (variant.flavorName?.startsWith("compat") == true) packLanguagesCompat else packLanguages
        variant.sources.assets?.addGeneratedSourceDirectory(pack, PackLanguages::output)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("Test")) compilerOptions.allWarningsAsErrors.set(true)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging.quiet.events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
    inputs.dir("../shared/assets").withPropertyName("mainAssets").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("../shared/res").withPropertyName("mainRes").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("../testdata").withPropertyName("testData").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/compat/slow-assets").withPropertyName("slowAssets").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("build.gradle.kts", "../version.properties", "src/main/res/xml/locales_config.xml", "src/foss/assets").withPropertyName("filesTheTestsRead").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("build/hostfx") { include("libamfx.*") }, fileTree("build/hostpivot") { include("libampivot.*") }, fileTree("../build/tracker-host") { include("libtracker_host.so") })
        .withPropertyName("hostLibraries").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20260814")
}
