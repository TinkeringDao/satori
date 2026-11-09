plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

kotlin {
    jvmToolchain(17)
}

// The reader's data source: its files' check, its paced load and release, and one line's reading,
// over lib-reader-onnx's native reader and domain/reading's text pipeline (0.8.0, R3).
android {
    namespace = "com.satori.feature.reader"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The data source logs as it loads and releases; in a JVM test android.util.Log does nothing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":lib-reader-onnx"))

    implementation(libs.kotlinx.coroutines.android)
    // Reads the model's config.json: its vocabularies' sizes, its positions and its special ids.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
