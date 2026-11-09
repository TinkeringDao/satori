plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.plugin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.satori.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // The app strips the native libraries of every module, so it needs an installed NDK too.
    // Without this AGP looks for its default NDK, does not find it, and packages them unstripped.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.satori"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 19
        versionName = "0.8.0-dev3"

        // The app speaks English and German only; drop other translations from libraries too.
        resourceConfigurations += listOf("en", "de")
        // Restrict the AAB/APK to arm64-v8a only.
        // This applies to all native libraries including prebuilt jniLibs in lib-sherpa-onnx.
        // Without this filter, jniLibs/armeabi-v7a (and x86/x86_64) are bundled in the AAB,
        // causing the Play Store to serve the app to 32-bit-only devices (Android Go, budget phones).
        // Those devices cannot load libai-chat.so (arm64-v8a only) → UnsatisfiedLinkError crash.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // The instrumented tests (IcuRegexTest) run in a plain Application, without the app's start-up.
        testInstrumentationRunner = "com.satori.regex.PlainApplicationRunner"
    }

    sourceSets {
        // The runs of the classes with regular expressions (RegexRunsTest) are the unit tests' and
        // the instrumented tests' both: the same answers read by the JVM's regex engine and by
        // Android's, ICU, which reads some patterns differently and rejects some the JVM takes.
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    buildFeatures {
        compose = true
        // The version name for the About row of Settings.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Force extraction of native libraries to nativeLibraryDir.
        // Required because lib-llama-android and lib-sherpa-onnx use
        // dlopen() on backend .so files at runtime via nativeLibraryDir path.
        // Without this, minSdk >= 23 defaults to extractNativeLibs=false,
        // keeping .so files inside the APK where dlopen() cannot find them.
        jniLibs {
            useLegacyPackaging = true
            // sherpa-onnx ships C and C++ API libraries for apps that do not use JNI; the JNI
            // library needs neither (jniLibs/arm64-v8a/README.md). Keeps them out if an upstream
            // sherpa-onnx update brings them back.
            excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

dependencies {
    // New architecture modules
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":presentation"))
    implementation(project(":feature-stt"))
    implementation(project(":feature-llm"))
    implementation(project(":feature-reader"))
    implementation(project(":lib-sherpa-onnx"))
    implementation(project(":lib-llama-android"))
    implementation(project(":lib-reader-onnx"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.core)

    // UI Enhancements
    implementation(libs.richtext.ui.material3)  // RichText UI
    implementation(libs.richtext.commonmark)  // Markdown parsing

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Android Studio's Compose tooling (Layout Inspector), debug builds only
    debugImplementation(libs.androidx.ui.tooling)
    // The reader's debug selftests read and write golden.py's TSVs, whose fields are JSON.
    debugImplementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
