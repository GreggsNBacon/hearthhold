import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.photobackup"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.photobackup"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.1"
        // Phones only: drops the x86/x86_64 copies of native libraries (FFmpeg decoder, ~3 MB), used by emulators.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Optional release signing (owner-managed, gitignored). Only key NAMES are ever reported, never values.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        val missing = required.filter { props.getProperty(it).isNullOrBlank() }
        if (missing.isEmpty()) {
            signingConfigs.create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        } else {
            // Debug builds and tests still work; any release task fails with a clear message instead of
            // silently producing an unsigned (or wrongly signed) APK.
            val message = "keystore.properties is missing: ${missing.joinToString(", ")}. " +
                "Add them (see README, Build), or delete keystore.properties to build unsigned."
            gradle.taskGraph.whenReady {
                if (allTasks.any { it.project == project && it.name.contains("Release") }) {
                    throw GradleException(message)
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // README screenshots: ./gradlew testDebugUnitTest --tests "*ReadmeScreenshots*" -PscreenshotsDir=docs/screenshots
        unitTests.all { test ->
            (project.findProperty("screenshotsDir") as String?)?.let {
                test.systemProperty("hh.screenshots.dir", rootProject.file(it).absolutePath)
                test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.smbj)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.jmdns)
    implementation(libs.dcerpc)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
