plugins { kotlin("jvm"); kotlin("plugin.serialization") }

kotlin {
    jvmToolchain(17)
    sourceSets {
        main { kotlin.srcDir("src/commonMain/kotlin") }
        test {
            kotlin.srcDir("src/commonTest/kotlin")
            resources.srcDir("src/commonTest/resources")
        }
    }
}

dependencies {
    implementation("com.google.guava:guava:33.4.8-jre")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("generateDesktopV6CompatibilityVector") {
    group = "verification"
    description = "Writes a desktop V6 backup container for Android compatibility verification."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.keyscan.core.backup.DesktopV6CompatibilityVectorGenerator")
    args(providers.gradleProperty("keyScanV6Vector").getOrElse(
        layout.buildDirectory.file("compatibility/desktop-v6-backup.ksb").get().asFile.absolutePath
    ))
}
