plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

kotlin {
    jvmToolchain(17)
}

// The reader: IndicTrans2's ONNX graphs on sherpa-onnx's ONNX Runtime 1.28.2, with SentencePiece
// (third_party/sentencepiece, a submodule) built static into libreader-onnx.so. The module packages
// only that library; libonnxruntime.so comes from lib-sherpa-onnx.
android {
    namespace = "com.satori.reader"
    compileSdk = libs.versions.compileSdk.get().toInt()

    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Optimized in debug builds too: the debug APK is the one the phone checks time.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // Only the reader: the plugin would otherwise build every target CMake knows,
                // SentencePiece's command-line tools included.
                targets += "reader-onnx"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ReaderEngineHostTest runs the JNI on the development machine against a macOS build of the same
// sources, which src/test/run_host_tests.sh makes and names with SATORI_READER_HOST_LIB_DIR.
tasks.withType<Test>().configureEach {
    System.getenv("SATORI_READER_HOST_LIB_DIR")?.let { systemProperty("java.library.path", it) }
}

dependencies {
    testImplementation(libs.junit)
}
