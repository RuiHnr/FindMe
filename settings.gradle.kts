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
include(":kmp")
include(":spikes:androidlocationspike:app")
project(":spikes:androidlocationspike:app").projectDir = file("spikes/androidlocationspike/app")