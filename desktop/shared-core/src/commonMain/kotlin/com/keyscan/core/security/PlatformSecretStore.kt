package com.keyscan.core.security

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale

interface SecretStore {
    fun put(id: String, secret: CharArray)
    fun get(id: String): CharArray?
    fun remove(id: String)
}

/** Stores secrets with Windows DPAPI, bound to the current Windows user account. */
class WindowsDpapiSecretStore(private val directory: Path) : SecretStore {
    init { require(isWindows()) { "Windows DPAPI is only available on Windows" }; Files.createDirectories(directory) }

    override fun put(id: String, secret: CharArray) {
        val target = path(id); val plain = String(secret).toByteArray(StandardCharsets.UTF_8)
        try {
            val protected = Crypt32Util.cryptProtectData(plain, ENTROPY, 0, "KeyScan credential", null)
            val temporary = Files.createTempFile(directory, "secret-", ".tmp")
            try {
                Files.write(temporary, protected, StandardOpenOption.TRUNCATE_EXISTING)
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: Exception) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary); protected.fill(0) }
        } finally { plain.fill(0) }
    }

    override fun get(id: String): CharArray? {
        val source = path(id); if (!Files.isRegularFile(source)) return null
        val protected = Files.readAllBytes(source)
        return try {
            val plain = Crypt32Util.cryptUnprotectData(protected, ENTROPY, 0, null)
            try { plain.toString(StandardCharsets.UTF_8).toCharArray() } finally { plain.fill(0) }
        } catch (_: Exception) {
            throw VaultAuthenticationException("Stored credential cannot be decrypted")
        } finally { protected.fill(0) }
    }

    override fun remove(id: String) { Files.deleteIfExists(path(id)) }

    private fun path(id: String): Path {
        require(ID.matches(id)) { "Invalid secret identifier" }
        val value = directory.resolve("$id.dpapi").toAbsolutePath().normalize()
        require(value.parent == directory.toAbsolutePath().normalize())
        return value
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val ENTROPY = "KeyScan.Desktop.SecretStore.v1".toByteArray(StandardCharsets.US_ASCII)
        fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("windows")
        fun defaultDirectory(): Path = com.keyscan.core.vault.VaultBootstrapPath.baseDirectory().resolve("secrets")
    }
}
