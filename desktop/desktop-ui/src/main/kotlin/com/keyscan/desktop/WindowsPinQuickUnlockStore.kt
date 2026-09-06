package com.keyscan.desktop

import com.keyscan.core.security.KeyDerivation
import com.keyscan.core.security.SecretStore
import com.keyscan.core.security.VaultAuthenticationException
import com.keyscan.core.security.VaultSession
import com.keyscan.core.security.WindowsDpapiSecretStore
import com.keyscan.core.vault.VaultBootstrapPath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** Windows equivalent of Android's locally protected vault session key.
 * The database key is DPAPI-bound to the current Windows user; a PBKDF verifier gates its use.
 * The data protection key is never persisted here and remains necessary for backup recovery. */
class WindowsPinQuickUnlockStore(
    private val file: Path = defaultFile(),
    private val secrets: SecretStore = WindowsDpapiSecretStore(WindowsDpapiSecretStore.defaultDirectory()),
) {
    fun isEnabled(): Boolean = Files.isRegularFile(file)

    fun enable(pin: String, databaseKey: CharArray) {
        require(pin.matches(Regex("\\d{4,6}")))
        val verifier = verifier(pin)
        try {
            secrets.put(SECRET_ID, databaseKey)
            write(Properties().apply { setProperty("formatVersion", "1"); setProperty("pinVerifier", verifier) })
        } catch (error: Exception) {
            secrets.remove(SECRET_ID)
            throw error
        }
    }

    fun unlock(pin: String): VaultSession {
        if (!pin.matches(Regex("\\d{4,6}"))) throw VaultAuthenticationException("Incorrect PIN")
        val properties = read()
        val expected = properties.getProperty("pinVerifier") ?: throw VaultAuthenticationException("PIN quick unlock is incomplete")
        val actual = verifier(pin)
        if (!MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), actual.toByteArray(StandardCharsets.UTF_8)))
            throw VaultAuthenticationException("Incorrect PIN")
        val key = secrets.get(SECRET_ID) ?: throw VaultAuthenticationException("PIN quick unlock key is unavailable")
        return try { VaultSession(String(key)) } finally { key.fill('\u0000') }
    }

    private fun verifier(pin: String): String = KeyDerivation.deriveRootKey(pin, VERIFIER_DOMAIN)
    private fun read(): Properties = try {
        Properties().also { if (!Files.isRegularFile(file)) throw VaultAuthenticationException("PIN quick unlock is not enabled"); Files.newInputStream(file).use(it::load)
            if (it.getProperty("formatVersion") != "1") throw VaultAuthenticationException("Unsupported PIN quick unlock configuration") }
    } catch (error: VaultAuthenticationException) { throw error }
      catch (error: Exception) { throw VaultAuthenticationException("PIN quick unlock configuration could not be read", error) }
    private fun write(properties: Properties) {
        Files.createDirectories(file.parent); val temp = Files.createTempFile(file.parent, "pin-", ".tmp")
        try { Files.newOutputStream(temp).use { properties.store(it, "KeyScan PIN quick unlock; no PIN or data protection key") }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: Exception) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
    companion object {
        private const val SECRET_ID = "vault_database_key"
        private const val VERIFIER_DOMAIN = "KeyScan.Desktop.PinVerifier.v1"
        fun defaultFile(): Path = VaultBootstrapPath.baseDirectory().resolve("windows-pin-unlock.properties")
        fun defaultMacFile(): Path = VaultBootstrapPath.baseDirectory().resolve("macos-pin-unlock.properties")
    }
}
