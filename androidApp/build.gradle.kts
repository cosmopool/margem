import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release key: outside the repo, its path and passwords in local.properties (gitignored):
// release.storeFile, release.storePassword, release.keyAlias, release.keyPassword.
// Without all four, release builds come out unsigned and everything else still builds.
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
val releaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { local.getProperty("release.$it").orEmpty() }
    .takeIf { it.values.all(String::isNotBlank) }

// Version from the latest vX.Y.Z tag reachable from HEAD: versionName "X.Y.Z",
// versionCode X*10000 + Y*100 + Z (so each component stays below 100). No tag, or no git: 0.0.0.
val versionTag = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().removePrefix("v")
val (major, minor, patch) = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(versionTag)
    ?.destructured?.toList()?.map(String::toInt) ?: listOf(0, 0, 0)

android {
    namespace = "com.kaiodelphino.margem"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kaiodelphino.margem"
        minSdk = 29
        targetSdk = 36
        versionCode = major * 10000 + minor * 100 + patch
        versionName = "$major.$minor.$patch"
    }

    // Debug key in the repo, so every machine signs debug builds the same way and an install
    // over another machine's build doesn't need an uninstall (which would erase the data).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getValue("storeFile"))
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey.getValue("keyPassword")
        }
    }

    buildTypes {
        // Margem-Dev: its own package id and name, so it installs next to the release build.
        debug {
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Hosts createComposeRule's activity in the Robolectric UI tests (D35).
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Robolectric runs on the Mac JVM, where sqlite-bundled's Android AAR has no native lib (D28).
    testImplementation(libs.androidx.sqlite.framework)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
