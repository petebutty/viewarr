pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipe Extractor (YouTube trailers) is only published on JitPack.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\..*") } }
    }
}

rootProject.name = "Viewarr"
include(":app")
