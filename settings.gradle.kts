pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Satori"
include(":app")
include(":domain")
include(":data")
include(":presentation")
include(":feature-stt")
include(":feature-llm")
include(":feature-reader")
include(":lib-sherpa-onnx")
include(":lib-llama-android")
include(":lib-reader-onnx")
