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

rootProject.name = "HiFiPlayer"

// ---------- Application ----------
include(":app")

// ---------- Presentation ----------
include(":presentation:navigation")
include(":presentation:library")
include(":presentation:playback")
include(":presentation:settings")

// ---------- Domain ----------
include(":domain:model")
include(":domain:repository")
include(":domain:usecase")

// ---------- Data ----------
include(":data:local")
include(":data:metadata")
include(":data:audio")
include(":data:repository")

// ---------- Core ----------
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:storage")
include(":core:metadata")
include(":core:permissions")
include(":core:usb")
include(":core:audio")

// ---------- Native / DSP ----------
include(":native:dsp")
include(":native:audio_engine")
