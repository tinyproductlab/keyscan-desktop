plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared-core"))
    testImplementation(project(":desktop-ui"))
    // NativeBridgeServer exposes its pending pairing through StateFlow. Tests
    // exercise that public bridge contract directly, so the type must be on
    // this module's test compilation classpath as well.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

application { mainClass.set("com.keyscan.nativehost.MainKt") }

val cleanWindowsAppImage by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("windows-app-image"))
}

tasks.register<Exec>("packageWindowsAppImage") {
    group = "distribution"
    description = "Builds the Windows native-messaging host app image with jpackage."
    dependsOn("installDist", cleanWindowsAppImage)
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    doFirst {
        val javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get().metadata.installationPath.asFile
        executable(javaHome.resolve("bin/jpackage.exe"))
        args(
            "--type", "app-image",
            "--name", "KeyScanNativeHost",
            "--dest", layout.buildDirectory.dir("windows-app-image").get().asFile.absolutePath,
            "--input", layout.buildDirectory.dir("install/native-host/lib").get().asFile.absolutePath,
            "--main-jar", "native-host-${project.version}.jar",
            "--main-class", "com.keyscan.nativehost.MainKt",
            "--vendor", "KeyScan",
            "--app-version", project.version.toString(),
        )
    }
}
