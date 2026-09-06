package com.keyscan.core.backup

import com.keyscan.core.security.KeyDerivation
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a desktop V6 container for verification by Android production code. */
object DesktopV6CompatibilityVectorGenerator {
    private const val pin = "001234"
    private const val dataProtectionKey = "Android Vector Data Key 2026!"
    private const val databaseKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    private const val payloadJson = "{\"version\":5,\"records\":[],\"passwordGroups\":[],\"passwords\":[{\"title\":\"Android compatibility\",\"username\":\"user@example.com\",\"password\":\"not-a-real-password\",\"notes\":\"中文 · 한국어 · 日本語\"}],\"otpTokens\":[],\"passwordGenerations\":[],\"vaultItems\":[],\"vaultAttachments\":[]}"

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Usage: <output-file>" }
        val destination = Path.of(arguments.single()).toAbsolutePath()
        destination.parent?.let(Files::createDirectories)
        val archive = zipPayload()
        try {
            val rootKey = KeyDerivation.deriveRootKey(pin, dataProtectionKey)
            Files.write(destination, SecureBackupCipher.encryptV6(archive, rootKey, databaseKey))
        } finally {
            archive.fill(0)
        }
    }

    private fun zipPayload(): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("payload.json").apply { time = 0L })
            zip.write(payloadJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        output.toByteArray()
    }
}
