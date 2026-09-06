package com.keyscan.core.backup

import com.keyscan.core.security.KeyDerivation
import com.keyscan.core.security.VaultAuthenticationException
import java.security.SecureRandom
import java.util.Base64
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.*

class SecureBackupCipherTest {
    private fun random(seed: Int) = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(seed.toByte(), 2, 3, 4)) }
    private val payload = "{\"version\":5,\"passwords\":[],\"otpTokens\":[],\"vaultItems\":[]}".toByteArray()

    @Test fun v5RoundTripLayoutAndAuthentication() {
        val encrypted = SecureBackupCipher.encryptV5(payload, "backup password", random(1))
        assertContentEquals(SecureBackupCipher.V5_MAGIC, encrypted.copyOfRange(0, 8)); assertEquals(5, SecureBackupCipher.detectVersion(encrypted))
        assertContentEquals(payload, SecureBackupCipher.decryptV5(encrypted, "backup password"))
        assertFailsWith<VaultAuthenticationException> { SecureBackupCipher.decryptV5(encrypted, "wrong") }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertFailsWith<VaultAuthenticationException> { SecureBackupCipher.decryptV5(encrypted, "backup password") }
    }

    @Test fun v6RoundTripWrapsDatabaseKeyAndAuthenticatesBothLayers() {
        val root = KeyDerivation.deriveRootKey("001234", "Data Key With Spaces")
        val databaseKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val encrypted = SecureBackupCipher.encryptV6(payload, root, databaseKey, random(2))
        assertContentEquals(SecureBackupCipher.V6_MAGIC, encrypted.copyOfRange(0, 8)); assertEquals(6, SecureBackupCipher.detectVersion(encrypted))
        val result = SecureBackupCipher.decryptV6(encrypted, root)
        assertEquals(databaseKey, result.databaseKey); assertContentEquals(payload, result.payload)
        assertFailsWith<VaultAuthenticationException> { SecureBackupCipher.decryptV6(encrypted, KeyDerivation.deriveRootKey("wrong", "Data Key With Spaces")) }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertFailsWith<VaultAuthenticationException> { SecureBackupCipher.decryptV6(encrypted, root) }
    }

    @Test fun malformedLengthsAndUnknownMagicAreRejected() {
        val root = KeyDerivation.deriveRootKey("123456", "DataProtectionKey")
        val db = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val encrypted = SecureBackupCipher.encryptV6(payload, root, db, random(3))
        encrypted[36] = 0x7f; encrypted[37] = 0x7f; encrypted[38] = 0x7f; encrypted[39] = 0x7f
        assertFailsWith<VaultAuthenticationException> { SecureBackupCipher.decryptV6(encrypted, root) }
        assertFailsWith<VaultAuthenticationException> { SecureBackupCipher.detectVersion("not a backup".toByteArray()) }
    }

    @Test fun decryptsAndroidProductionV5GoldenBackup() {
        val archive = SecureBackupCipher.decryptV5(androidGolden("android-v5-backup.ksb"), ANDROID_DATA_PROTECTION_KEY)
        assertAndroidPayloadJson(payloadJsonFrom(archive))
    }

    @Test fun decryptsAndroidProductionV6GoldenBackup() {
        val root = KeyDerivation.deriveRootKey(ANDROID_PIN, ANDROID_DATA_PROTECTION_KEY)
        assertEquals("mvE/U3jzXYjRQEwNjPFVDW5P1+BnysM02dGayNxVIhE=", root)
        val result = SecureBackupCipher.decryptV6(androidGolden("android-v6-backup.ksb"), root)
        assertEquals(ANDROID_DATABASE_KEY, result.databaseKey)
        assertAndroidPayloadJson(payloadJsonFrom(result.payload))
        var attachmentContent = ""
        val portable = SecureBackupPackage.readV6(ByteArrayInputStream(androidGolden("android-v6-backup.ksb")), root) { attachment, input ->
            assertEquals("attachment-android", attachment.id)
            assertEquals("android-note.txt", attachment.filename)
            attachmentContent = input.readBytes().toString(Charsets.UTF_8)
        }
        assertEquals(5, portable.version)
        assertEquals("Android compatibility", portable.passwords.single().title)
        assertEquals("user@example.com", portable.passwords.single().username)
        assertEquals("not-a-real-password", portable.passwords.single().password)
        assertEquals("Android document", portable.vaultItems.single().title)
        assertEquals("attachments/attachment-android.bin", portable.vaultAttachments.single().contentReference)
        assertEquals("Android attachment bytes: 中文 · 한국어 · 日本語", attachmentContent)
    }

    private fun androidGolden(name: String): ByteArray = checkNotNull(
        javaClass.classLoader.getResourceAsStream("backup/$name")
    ) { "Missing Android compatibility vector: $name" }.use { it.readBytes() }

    private fun payloadJsonFrom(archive: ByteArray): String = ZipInputStream(archive.inputStream()).use { zip ->
        val entry = checkNotNull(zip.nextEntry) { "Android backup archive is missing payload.json" }
        assertEquals("payload.json", entry.name)
        zip.readBytes().toString(Charsets.UTF_8)
    }

    private fun assertAndroidPayloadJson(json: String) {
        assertTrue(json.contains("\"version\":5"))
        assertTrue(json.contains("Android compatibility"))
        assertTrue(json.contains("attachments/attachment-android.bin"))
    }

    private companion object {
        const val ANDROID_PIN = "001234"
        const val ANDROID_DATA_PROTECTION_KEY = "Android Vector Data Key 2026!"
        const val ANDROID_DATABASE_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    }
}
