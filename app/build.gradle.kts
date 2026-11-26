plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.helloworld"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.helloworld"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // ABIs cargo-ndk builds for (see rust_core/ and .github/workflows/android.yml).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    sourceSets {
        getByName("main") {
            // Native libs built by cargo-ndk land here, one folder per ABI:
            // src/main/jniLibs/<abi>/librust_core.so
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

// Optional local Rust build: `./gradlew assembleDebug -PbuildRust`
// runs cargo-ndk (release) and drops librust_core.so into
// src/main/jniLibs/<abi>/ before the APK is assembled. Requires a Rust
// toolchain with cargo-ndk and an Android NDK (ANDROID_NDK_HOME set).
// Without -PbuildRust, Gradle builds the Kotlin app as-is; CI always
// builds the Rust targets first (see .github/workflows/android.yml).
val buildRust = tasks.register("buildRust", Exec::class.java) {
    description = "Build rust_core with cargo-ndk for all four ABIs."
    group = "rust"
    workingDir = file("$rootDir/rust_core")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-t", "x86",
        "-o", "app/src/main/jniLibs",
        "build", "--release"
    )
    environment("ANDROID_NDK_HOME", System.getenv("ANDROID_NDK_HOME") ?: "")
}
if (project.hasProperty("buildRust")) {
    tasks.named("preBuild") { dependsOn(buildRust) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Zoomable image viewing (Coil) and audio/video playback (ExoPlayer).
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
