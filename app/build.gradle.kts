import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "app.absentmotion"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.absentmotion"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("boolean", "COMPATIBLE", "false")
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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
            proguardFiles(file("proguard-rules.pro"))
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
        create("play") {
            dimension = "distribution"
            manifestPlaceholders["variant"] = "play"
        }
        create("foss") {
            dimension = "distribution"
            manifestPlaceholders["variant"] = "foss"
        }
        create("compat") {
            dimension = "distribution"
            manifestPlaceholders["variant"] = "compat"
            minSdk = 28
            buildConfigField("boolean", "COMPATIBLE", "true")
            externalNativeBuild { cmake { arguments += "-DAM_COMPAT=ON" } }
        }
    }

    sourceSets {
        named("compat") {
            kotlin.directories += "src/foss/kotlin"
            assets.directories += "src/foss/assets"
        }
        named("testCompat") { kotlin.directories += "src/testFoss/kotlin" }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("Test")) compilerOptions.allWarningsAsErrors.set(true)
}

tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets").withPropertyName("mainAssets").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/res").withPropertyName("mainRes").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("../testdata").withPropertyName("testData").withPathSensitivity(PathSensitivity.RELATIVE)
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
    "playImplementation"("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20260814")
}
