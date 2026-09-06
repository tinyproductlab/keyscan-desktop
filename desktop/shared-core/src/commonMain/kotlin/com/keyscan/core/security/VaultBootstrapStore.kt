package com.keyscan.core.security

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties

class VaultSession(databaseKey: String) : AutoCloseable {
    private val value = databaseKey.toCharArray()
    private var closed = false
    fun useDatabaseKey(block: (CharArray) -> Unit) {
        check(!closed) { "Vault session is closed" }
        val copy = value.copyOf()
        try { block(copy) } finally { copy.fill('\u0000') }
    }
    override fun close() { if (!closed) { value.fill('\u0000'); closed = true } }
}

class VaultBootstrapStore(private val configFile: Path) {
    fun isConfigured(): Boolean = Files.isRegularFile(configFile)

    fun create(pin: String, dataProtectionKey: String): VaultSession {
        check(!isConfigured()) { "Vault is already configured" }
        val rootKey = KeyDerivation.deriveRootKey(pin, dataProtectionKey)
        val databaseKey = VaultCrypto.newDatabaseKey()
        val envelope = VaultCrypto.wrapDatabaseKey(databaseKey, rootKey)
        val properties = Properties().apply {
            setProperty("formatVersion", "1")
            setProperty("databaseKeyEnvelope", envelope)
        }
        writeAtomically(properties)
        return VaultSession(databaseKey)
    }

    fun unlock(pin: String, dataProtectionKey: String): VaultSession {
        try {
            val properties = readProperties()
            if (properties.getProperty("formatVersion") != "1") throw VaultAuthenticationException("Unsupported vault configuration version")
            val envelope = properties.getProperty("databaseKeyEnvelope")
                ?: throw VaultAuthenticationException("Vault configuration is incomplete")
            val rootKey = KeyDerivation.deriveRootKey(pin, dataProtectionKey)
            return VaultSession(VaultCrypto.unwrapDatabaseKey(envelope, rootKey))
        } catch (error: VaultAuthenticationException) {
            throw error
        } catch (error: Exception) {
            throw VaultAuthenticationException("Vault configuration could not be read", error)
        }
    }

    /**
     * Re-wraps the existing database key with a new Android-compatible data
     * protection key. The encrypted vault itself is not rewritten, and the
     * bootstrap file is atomically replaced only after the old key succeeds.
     */
    fun changeDataProtectionKey(pin: String, currentDataProtectionKey: String, nextDataProtectionKey: String) {
        try {
            val properties = readProperties()
            if (properties.getProperty("formatVersion") != "1") throw VaultAuthenticationException("Unsupported vault configuration version")
            val envelope = properties.getProperty("databaseKeyEnvelope")
                ?: throw VaultAuthenticationException("Vault configuration is incomplete")
            val currentRootKey = KeyDerivation.deriveRootKey(pin, currentDataProtectionKey)
            val databaseKey = VaultCrypto.unwrapDatabaseKey(envelope, currentRootKey)
            val nextRootKey = KeyDerivation.deriveRootKey(pin, nextDataProtectionKey)
            try {
                properties.setProperty("databaseKeyEnvelope", VaultCrypto.wrapDatabaseKey(databaseKey, nextRootKey))
                writeAtomically(properties)
            } finally {
                databaseKey.toCharArray().fill('\u0000')
            }
        } catch (error: VaultAuthenticationException) {
            throw error
        } catch (error: Exception) {
            throw VaultAuthenticationException("Data protection key could not be changed", error)
        }
    }

    private fun readProperties(): Properties = Properties().also { properties ->
        Files.newInputStream(configFile, StandardOpenOption.READ).use { input: InputStream -> properties.load(input) }
    }

    private fun writeAtomically(properties: Properties) {
        Files.createDirectories(configFile.parent)
        val temporary = Files.createTempFile(configFile.parent, "vault-", ".tmp")
        try {
            Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING).use { output: OutputStream ->
                properties.store(output, "KeyScan vault bootstrap - contains no PIN or data protection key")
            }
            try {
                Files.move(temporary, configFile, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        fun defaultConfigPath(): Path {
            val os = System.getProperty("os.name").lowercase()
            val base = when {
                os.contains("win") -> System.getenv("APPDATA")?.let(Path::of)
                os.contains("mac") -> Path.of(System.getProperty("user.home"), "Library", "Application Support")
                else -> System.getenv("XDG_CONFIG_HOME")?.let(Path::of)
                    ?: Path.of(System.getProperty("user.home"), ".config")
            } ?: Path.of(System.getProperty("user.home"))
            return base.resolve("KeyScan").resolve("vault.properties")
        }
    }
}
