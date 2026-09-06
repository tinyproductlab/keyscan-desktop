package com.keyscan.core.webdav

import com.keyscan.core.security.SecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

data class WebDavTargetSettings(val enabled: Boolean = false, val url: String = "", val username: String = "")
data class WebDavSettings(val primary: WebDavTargetSettings = WebDavTargetSettings(), val secondary: WebDavTargetSettings = WebDavTargetSettings(), val prefix: String = "filebackup")

/** Persists non-secret WebDAV settings; passwords are delegated exclusively to SecretStore. */
class WebDavSettingsStore(private val file: Path, private val secrets: SecretStore) {
    fun load(): WebDavSettings {
        val properties = Properties()
        if (Files.isRegularFile(file)) Files.newInputStream(file).use(properties::load)
        return WebDavSettings(readTarget(properties, "primary"), readTarget(properties, "secondary"),
            DualWebDavBackupService.sanitizePrefix(properties.getProperty("prefix", "filebackup")))
    }

    fun save(settings: WebDavSettings, primaryPassword: CharArray? = null, secondaryPassword: CharArray? = null) {
        val safe = settings.copy(prefix = DualWebDavBackupService.sanitizePrefix(settings.prefix),
            primary = validate(settings.primary), secondary = validate(settings.secondary))
        val properties = Properties().apply {
            setProperty("prefix", safe.prefix); writeTarget(this, "primary", safe.primary); writeTarget(this, "secondary", safe.secondary)
        }
        val parent = file.toAbsolutePath().normalize().parent ?: error("Settings file requires a parent")
        Files.createDirectories(parent); val temporary = Files.createTempFile(parent, "webdav-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "KeyScan WebDAV non-secret settings") }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: Exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
            updateSecret(PRIMARY_SECRET, safe.primary.enabled, primaryPassword)
            updateSecret(SECONDARY_SECRET, safe.secondary.enabled, secondaryPassword)
        } finally { Files.deleteIfExists(temporary) }
    }

    fun credentials(id: String, username: String): WebDavCredentials? {
        val secret = secrets.get(secretId(id)) ?: return null
        return WebDavCredentials(username, secret)
    }

    private fun updateSecret(id: String, enabled: Boolean, value: CharArray?) {
        if (!enabled) secrets.remove(id) else if (value != null) try { secrets.put(id, value) } finally { value.fill('\u0000') }
    }
    private fun validate(value: WebDavTargetSettings): WebDavTargetSettings {
        val clean = value.copy(url = value.url.trim().trimEnd('/'), username = value.username.trim())
        if (clean.enabled) { require(clean.url.isNotBlank() && clean.username.isNotBlank()); HttpWebDavClient(clean.url, WebDavCredentials(clean.username, charArrayOf('x'))) }
        return clean
    }
    private fun readTarget(p: Properties, key: String) = WebDavTargetSettings(p.getProperty("$key.enabled", "false").toBooleanStrictOrNull() ?: false, p.getProperty("$key.url", ""), p.getProperty("$key.username", ""))
    private fun writeTarget(p: Properties, key: String, value: WebDavTargetSettings) { p.setProperty("$key.enabled", value.enabled.toString()); p.setProperty("$key.url", value.url); p.setProperty("$key.username", value.username) }
    private fun secretId(id: String) = when (id) { "primary" -> PRIMARY_SECRET; "secondary" -> SECONDARY_SECRET; else -> error("Unknown WebDAV target") }

    companion object {
        private const val PRIMARY_SECRET = "webdav-primary-password"
        private const val SECONDARY_SECRET = "webdav-secondary-password"
        fun defaultFile(): Path = com.keyscan.core.vault.VaultBootstrapPath.baseDirectory().resolve("webdav.properties")
    }
}
