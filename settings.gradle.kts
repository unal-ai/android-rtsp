pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RTSPCamera"
include(":app")

// RTSP-Server modules
include(":rtspserver")
project(":rtspserver").projectDir = file("app/libraries/RTSP-Server/rtspserver")

// RootEncoder modules
include(":library", ":encoder", ":rtmp", ":rtsp", ":srt", ":udp", ":common", ":extra-sources")
project(":library").projectDir = file("app/libraries/RootEncoder/library")
project(":encoder").projectDir = file("app/libraries/RootEncoder/encoder")
project(":rtmp").projectDir = file("app/libraries/RootEncoder/rtmp")
project(":rtsp").projectDir = file("app/libraries/RootEncoder/rtsp")
project(":srt").projectDir = file("app/libraries/RootEncoder/srt")
project(":udp").projectDir = file("app/libraries/RootEncoder/udp")
project(":common").projectDir = file("app/libraries/RootEncoder/common")
project(":extra-sources").projectDir = file("app/libraries/RootEncoder/extra-sources")

