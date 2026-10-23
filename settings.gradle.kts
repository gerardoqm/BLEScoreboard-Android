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
    }
}

rootProject.name = "BLEScoreboard-Android"

include(":core")   // pure Kotlin: Bluetooth protocol, panel drawing, texts shared by the phone and the watch
include(":app")    // the phone app
include(":wear")   // the Wear OS app
