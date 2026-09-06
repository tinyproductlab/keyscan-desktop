package com.keyscan.desktop

import com.keyscan.core.security.SecretStore
import com.keyscan.core.vault.VaultBootstrapPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.Duration
import java.util.Base64
import java.util.Properties

/**
 * Local browser-extension pairing records. Tokens deliberately live only in
 * [SecretStore] (DPAPI on Windows); the properties file contains no secret.
 */
data class BrowserPluginRecord(
    val caller: String,
    val browser: String,
    val extensionId: String,
    val version: String,
    val authorizedAt: Instant,
    val lastSeenAt: Instant?,
)

enum class BrowserPluginConnectionState { ONLINE, OFFLINE, NEVER_CONNECTED }

class BrowserPluginRegistry(
    private val metadataFile: Path = defaultMetadataFile(),
    private val secretStore: SecretStore,
    private val random: SecureRandom = SecureRandom(),
) {
    fun records(): List<BrowserPluginRecord> = load().values.sortedBy { it.browser.lowercase() }

    fun connectionState(record: BrowserPluginRecord, now: Instant = Instant.now()): BrowserPluginConnectionState = when (val seen = record.lastSeenAt) {
        null -> BrowserPluginConnectionState.NEVER_CONNECTED
        else -> if (Duration.between(seen, now).seconds <= 90) BrowserPluginConnectionState.ONLINE else BrowserPluginConnectionState.OFFLINE
    }

    fun tokenMatches(caller: String, token: String?): Boolean {
        if (token.isNullOrBlank() || caller.isBlank()) return false
        val expected = secretStore.get(tokenSecretId(caller)) ?: return false
        val expectedBytes = expected.concatToString().toByteArray(Charsets.US_ASCII)
        return try {
            java.security.MessageDigest.isEqual(expectedBytes, token.toByteArray(Charsets.US_ASCII))
        } finally {
            expected.fill('\u0000')
            expectedBytes.fill(0)
        }
    }

    fun callerForToken(token: String?): String? = records().asSequence().map { it.caller }
        .firstOrNull { caller -> tokenMatches(caller, token) }

    /** Creates a fresh opaque pairing token, replacing any prior token for the same caller. */
    fun authorize(caller: String, browser: String, extensionId: String, version: String): String {
        require(caller.isNotBlank() && caller.length <= 256)
        require(browser.isNotBlank() && browser.length <= 64)
        require(extensionId.isNotBlank() && extensionId.length <= 256)
        require(version.length <= 64)
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        tokenBytes.fill(0)
        secretStore.put(tokenSecretId(caller), token.toCharArray())
        val now = Instant.now()
        val current = load().toMutableMap()
        current[caller] = BrowserPluginRecord(caller, browser, extensionId, version, now, now)
        save(current)
        return token
    }

    fun markSeen(caller: String) {
        val current = load().toMutableMap()
        val record = current[caller] ?: return
        current[caller] = record.copy(lastSeenAt = Instant.now())
        save(current)
    }

    fun revoke(caller: String) {
        secretStore.remove(tokenSecretId(caller))
        val current = load().toMutableMap()
        if (current.remove(caller) != null) save(current)
    }

    private fun load(): Map<String, BrowserPluginRecord> {
        if (!Files.isRegularFile(metadataFile)) return emptyMap()
        val p = Properties().also { Files.newInputStream(metadataFile).use(it::load) }
        val callers = p.getProperty("callers", "").split(',').map(String::trim).filter(String::isNotBlank).distinct()
        return callers.mapNotNull { caller ->
            val prefix = "plugin.${Base64.getUrlEncoder().withoutPadding().encodeToString(caller.toByteArray(Charsets.UTF_8))}."
            val browser = p.getProperty(prefix + "browser") ?: return@mapNotNull null
            val extensionId = p.getProperty(prefix + "extensionId") ?: return@mapNotNull null
            val version = p.getProperty(prefix + "version", "")
            val authorizedAt = p.getProperty(prefix + "authorizedAt")?.let { text -> runCatching { Instant.parse(text) }.getOrNull() } ?: return@mapNotNull null
            val lastSeenAt = p.getProperty(prefix + "lastSeenAt")?.let { text -> runCatching { Instant.parse(text) }.getOrNull() }
            caller to BrowserPluginRecord(caller, browser, extensionId, version, authorizedAt, lastSeenAt)
        }.toMap()
    }

    private fun save(records: Map<String, BrowserPluginRecord>) {
        val parent = metadataFile.toAbsolutePath().normalize().parent ?: error("Pairing metadata requires a parent")
        Files.createDirectories(parent)
        val p = Properties().apply {
            setProperty("formatVersion", "1")
            setProperty("callers", records.keys.sorted().joinToString(","))
            records.values.forEach { record ->
                val prefix = "plugin.${Base64.getUrlEncoder().withoutPadding().encodeToString(record.caller.toByteArray(Charsets.UTF_8))}."
                setProperty(prefix + "browser", record.browser)
                setProperty(prefix + "extensionId", record.extensionId)
                setProperty(prefix + "version", record.version)
                setProperty(prefix + "authorizedAt", record.authorizedAt.toString())
                record.lastSeenAt?.let { setProperty(prefix + "lastSeenAt", it.toString()) }
            }
        }
        val temporary = Files.createTempFile(parent, "browser-plugins-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { p.store(it, "KeyScan browser pairing metadata; tokens are DPAPI protected") }
            try { Files.move(temporary, metadataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: Exception) { Files.move(temporary, metadataFile, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun tokenSecretId(caller: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(caller.toByteArray(Charsets.UTF_8))
        return "browser_pairing_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        fun defaultMetadataFile(): Path = VaultBootstrapPath.baseDirectory().resolve("browser-plugins.properties")
    }
}
