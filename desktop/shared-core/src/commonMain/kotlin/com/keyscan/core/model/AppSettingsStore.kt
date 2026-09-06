package com.keyscan.core.model

import com.keyscan.core.vault.VaultBootstrapPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

class AppSettingsStore(private val file: Path = defaultFile()) {
    fun load(): AppSettings {
        val p = Properties(); if (Files.isRegularFile(file)) Files.newInputStream(file).use(p::load)
        return AppSettings(
            language = enumValue(p.getProperty("language"), AppLanguage.SYSTEM),
            themeMode = enumValue(p.getProperty("theme"), ThemeMode.SYSTEM),
            viewOnly = p.getProperty("viewOnly", "true").toBooleanStrictOrNull() ?: true,
            autoLockMinutes = p.getProperty("autoLockMinutes", "5").toIntOrNull()?.coerceIn(1, 120) ?: 5,
            clipboardClearSeconds = p.getProperty("clipboardClearSeconds", "30").toIntOrNull()?.coerceIn(5, 300) ?: 30
        )
    }

    fun save(settings: AppSettings) {
        val p = Properties().apply {
            setProperty("language", settings.language.name); setProperty("theme", settings.themeMode.name)
            setProperty("viewOnly", settings.viewOnly.toString()); setProperty("autoLockMinutes", settings.autoLockMinutes.coerceIn(1, 120).toString())
            setProperty("clipboardClearSeconds", settings.clipboardClearSeconds.coerceIn(5, 300).toString())
        }
        val parent = file.toAbsolutePath().normalize().parent ?: error("Settings file requires a parent")
        Files.createDirectories(parent); val temporary = Files.createTempFile(parent, "settings-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { p.store(it, "KeyScan non-secret settings") }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: Exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback
    companion object { fun defaultFile(): Path = VaultBootstrapPath.baseDirectory().resolve("settings.properties") }
}
