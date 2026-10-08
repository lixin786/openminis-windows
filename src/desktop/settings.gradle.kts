// OpenMinis Desktop (Windows) — Compose Multiplatform for Desktop port.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "openminis-desktop"
// The module lives in ./app but is exposed as ":desktop" so that
// `gradlew :desktop:compileKotlin` / `:desktop:packageMsi` work from this directory.
include(":desktop")
project(":desktop").projectDir = file("app")
