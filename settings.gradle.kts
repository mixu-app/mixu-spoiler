// SPDX-License-Identifier: Apache-2.0

rootProject.name = "mixu-spoiler"

pluginManagement {
    includeBuild("build-logic-src/build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("libs") {
            from(files("build-logic-src/gradle/libs.versions.toml"))
        }
    }
}

if (gradle.parent == null) {
    includeBuild("libs/mixu-graph")
}
