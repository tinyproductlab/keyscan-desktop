import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val isWindowsBuild = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val windowsHelloHelper = layout.projectDirectory.dir("../windows-hello-helper/build").file("KeyScanWindowsHello.exe")
val buildWindowsHelloHelper by tasks.registering(Exec::class) {
    // Read the flag into a local: an onlyIf lambda that touches a build-script property
    // captures the script object itself, which the configuration cache cannot serialise.
    val windowsBuild = isWindowsBuild
    onlyIf { windowsBuild }
    inputs.files(
        layout.projectDirectory.file("../windows-hello-helper/KeyScanWindowsHello.cs"),
        layout.projectDirectory.file("../windows-hello-helper/Build-WindowsHelloHelper.ps1"),
    )
    outputs.file(windowsHelloHelper)
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", layout.projectDirectory.file("../windows-hello-helper/Build-WindowsHelloHelper.ps1").asFile.absolutePath)
}

tasks.named<Copy>("processResources") {
    if (isWindowsBuild) {
        dependsOn(buildWindowsHelloHelper)
        from(windowsHelloHelper) { into("native") }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    // Keep Material3 on the exact Compose plugin dependency train. An explicit
    // version pulled a second Compose runtime into the Windows distribution.
    implementation(compose.material3)
    implementation("net.java.dev.jna:jna:5.17.0")
    // DPAPI credential storage is invoked from the desktop process. Keep the
    // Windows JNA platform classes as a direct runtime dependency so jpackage
    // cannot omit Crypt32Util from the EXE image.
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.zxing:javase:3.5.4")
    testImplementation(kotlin("test"))
}

val nativeHostAppImage = project(":native-host").layout.buildDirectory.dir("windows-app-image/KeyScanNativeHost")

val packageBundledNativeHost by tasks.registering(Zip::class) {
    val windowsBuild = isWindowsBuild
    onlyIf { windowsBuild }
    dependsOn(":native-host:packageWindowsAppImage")
    archiveFileName.set("KeyScanNativeHost.zip")
    destinationDirectory.set(layout.buildDirectory.dir("bundled-native-host"))
    from(nativeHostAppImage)
}

val prepareBundledAppResources by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("app-resources"))
    if (isWindowsBuild) {
        dependsOn(packageBundledNativeHost)
        // Keep the native host as an opaque archive. Compose Desktop adds every
        // nested JAR under app resources to the desktop classpath, so embedding
        // the unpacked app image would load a second shared-core at runtime.
        from(packageBundledNativeHost.flatMap { it.archiveFile }) { into("windows") }
        from(layout.projectDirectory.dir("../packaging/windows/native-messaging")) {
            include("Install-KeyScanNativeHost.ps1", "Uninstall-KeyScanNativeHost.ps1")
            into("windows")
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(prepareBundledAppResources)
}

compose.desktop {
    application {
        dependsOn("prepareBundledAppResources")
        mainClass = "com.keyscan.desktop.MainKt"
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("app-resources"))
            // jpackage only creates installers for the current host OS.  Keeping the
            // target list host-specific lets the same Gradle project build on an Intel
            // Mac without asking its local jpackage to prepare Windows installers.
            targetFormats(*(if (isWindowsBuild) arrayOf(TargetFormat.Msi, TargetFormat.Exe) else arrayOf(TargetFormat.Dmg)))
            // WebDAV uses java.net.http.HttpClient. Compose's minimized jlink
            // runtime does not reliably infer this module from the application,
            // so keep it explicitly in every desktop distribution.
            modules("java.net.http", "jdk.httpserver")
            packageName = "KeyScan"
            // Bump the Windows installer version so a fixed runtime image is an
            // actual upgrade instead of being treated as the already-installed 0.1.0.
            packageVersion = if (isWindowsBuild) "0.1.69" else "1.0.20"
            description = "KeyScan password manager"
            vendor = "KeyScan"
            windows {
                menuGroup = "KeyScan"
                shortcut = true
                iconFile.set(project.file("src/main/resources/icons/keyscan-app.ico"))
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icons/keyscan-app.icns"))
            }
        }
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
    }
}
