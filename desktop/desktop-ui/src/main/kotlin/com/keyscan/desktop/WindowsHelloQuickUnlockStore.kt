package com.keyscan.desktop

import com.keyscan.core.security.VaultAuthenticationException
import com.keyscan.core.security.VaultCrypto
import com.keyscan.core.security.VaultSession
import com.keyscan.core.vault.VaultBootstrapPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

class WindowsHelloQuickUnlockStore(
    private val file: Path = defaultFile(),
    private val random: SecureRandom = SecureRandom(),
) {
    data class Enrollment(val keyName: String, val challenge: ByteArray)
    fun isEnabled(): Boolean = Files.isRegularFile(file)

    fun newEnrollment(): Enrollment = Enrollment(KEY_NAME, ByteArray(32).also(random::nextBytes))

    fun enable(enrollment: Enrollment, databaseKey: CharArray, signature: ByteArray) {
        require(enrollment.keyName == KEY_NAME && enrollment.challenge.size == 32 && signature.size in 128..1024)
        val signatureKey = Base64.getEncoder().encodeToString(signature)
        val databaseKeyString = String(databaseKey)
        try {
            val properties = Properties().apply {
                setProperty("formatVersion", "1")
                setProperty("keyName", enrollment.keyName)
                setProperty("challenge", Base64.getEncoder().encodeToString(enrollment.challenge))
                setProperty("databaseKeyEnvelope", VaultCrypto.wrapDatabaseKey(databaseKeyString, signatureKey))
            }
            writeAtomically(properties)
        } finally { /* Strings cannot be zeroed; callers zero mutable inputs immediately. */ }
    }

    fun enrollment(): Enrollment {
        val properties = readValidated()
        val challenge = runCatching { Base64.getDecoder().decode(properties.getProperty("challenge")) }.getOrNull()
            ?.takeIf { it.size == 32 } ?: throw VaultAuthenticationException("Invalid Windows Hello challenge")
        return Enrollment(KEY_NAME, challenge)
    }

    fun unlock(signature: ByteArray): VaultSession {
        require(signature.size in 128..1024)
        val properties = readValidated(); val signatureKey = Base64.getEncoder().encodeToString(signature)
        val envelope = properties.getProperty("databaseKeyEnvelope") ?: throw VaultAuthenticationException("Windows Hello enrollment is incomplete")
        return VaultSession(VaultCrypto.unwrapDatabaseKey(envelope, signatureKey))
    }

    fun disable() { Files.deleteIfExists(file) }

    private fun read() = Properties().also { if (!Files.isRegularFile(file)) throw VaultAuthenticationException("Windows Hello is not enabled"); Files.newInputStream(file).use(it::load) }
    private fun readValidated(): Properties = try {
        read().also {
            if (it.getProperty("formatVersion") != "1" || it.getProperty("keyName") != KEY_NAME)
                throw VaultAuthenticationException("Unsupported Windows Hello quick-unlock configuration")
        }
    } catch (error: VaultAuthenticationException) { throw error }
      catch (error: Exception) { throw VaultAuthenticationException("Windows Hello quick-unlock configuration could not be read", error) }
    private fun writeAtomically(properties: Properties) {
        Files.createDirectories(file.parent); val temporary = Files.createTempFile(file.parent, "hello-", ".tmp")
        try {
            Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING).use { properties.store(it, "KeyScan Windows Hello envelope; contains no PIN or data protection key") }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: Exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        const val KEY_NAME = "KeyScan.Desktop.QuickUnlock.v1"
        fun defaultFile(): Path = VaultBootstrapPath.baseDirectory().resolve("windows-hello.properties")
    }
}
