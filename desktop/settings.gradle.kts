// Auto-provisions the JDK 17 toolchain the modules ask for, so a fresh clone builds
// without hand-installing a matching JDK first.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "KeyScan-Desktop"
include(":shared-core", ":desktop-ui", ":native-host")
