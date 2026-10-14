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

rootProject.name = "Orbit"

// Include shared KMP module and Android app:
include(":kmp")
project(":kmp").projectDir = file("kmp")
include(":spikes:androidlocationspike:app")
project(":spikes:androidlocationspike:app").projectDir = file("spikes/androidlocationspike/app")