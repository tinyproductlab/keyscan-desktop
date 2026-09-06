package com.keyscan.core.backup

import com.keyscan.core.security.VaultAuthenticationException
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecureBackupCipher {
    val V5_MAGIC = byteArrayOf('K'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), '5'.code.toByte(), 'A'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte(), 0)
    val V6_MAGIC = byteArrayOf('K'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), '6'.code.toByte(), 'A'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte(), 0)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ITERATIONS = 240_000

    fun encryptV5(payload: ByteArray, backupPassword: String?, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes); val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = derive(backupPassword.orEmpty(), salt)
        return try { V5_MAGIC + salt + iv + crypt(Cipher.ENCRYPT_MODE, payload, key, iv) }
        finally { salt.fill(0); iv.fill(0); key.encoded.fill(0) }
    }

    fun decryptV5(container: ByteArray, backupPassword: String?): ByteArray {
        requireMagic(container, V5_MAGIC)
        if (container.size < V5_MAGIC.size + SALT_BYTES + IV_BYTES + TAG_BYTES) throw VaultAuthenticationException("Truncated V5 backup")
        val salt = container.copyOfRange(8, 24); val iv = container.copyOfRange(24, 36); val ciphertext = container.copyOfRange(36, container.size)
        val key = derive(backupPassword.orEmpty(), salt)
        return try { crypt(Cipher.DECRYPT_MODE, ciphertext, key, iv) }
        finally { salt.fill(0); iv.fill(0); ciphertext.fill(0); key.encoded.fill(0) }
    }

    fun encryptV6(payload: ByteArray, rootKey: String, databaseKey: String, random: SecureRandom = SecureRandom()): ByteArray {
        require(rootKey.isNotEmpty() && databaseKey.isNotEmpty())
        val wrapSalt = ByteArray(SALT_BYTES).also(random::nextBytes); val wrapIv = ByteArray(IV_BYTES).also(random::nextBytes)
        val payloadSalt = ByteArray(SALT_BYTES).also(random::nextBytes); val payloadIv = ByteArray(IV_BYTES).also(random::nextBytes)
        val wrapKey = derive(rootKey, wrapSalt); val payloadKey = derive(databaseKey, payloadSalt)
        val databaseKeyBytes = databaseKey.toByteArray(StandardCharsets.UTF_8)
        return try {
            val wrapped = crypt(Cipher.ENCRYPT_MODE, databaseKeyBytes, wrapKey, wrapIv)
            require(wrapped.size in 16..1024)
            val encryptedPayload = crypt(Cipher.ENCRYPT_MODE, payload, payloadKey, payloadIv)
            ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { output ->
                output.write(V6_MAGIC); output.write(wrapSalt); output.write(wrapIv); output.writeInt(wrapped.size); output.write(wrapped)
                output.write(payloadSalt); output.write(payloadIv); output.write(encryptedPayload)
            }; bytes.toByteArray() }
        } finally {
            wrapSalt.fill(0); wrapIv.fill(0); payloadSalt.fill(0); payloadIv.fill(0); wrapKey.encoded.fill(0); payloadKey.encoded.fill(0); databaseKeyBytes.fill(0)
        }
    }

    fun encryptingV6(destination: OutputStream, rootKey: String, databaseKey: String, random: SecureRandom = SecureRandom()): CipherOutputStream {
        val wrapSalt = ByteArray(SALT_BYTES).also(random::nextBytes); val wrapIv = ByteArray(IV_BYTES).also(random::nextBytes)
        val payloadSalt = ByteArray(SALT_BYTES).also(random::nextBytes); val payloadIv = ByteArray(IV_BYTES).also(random::nextBytes)
        val wrapKey = derive(rootKey, wrapSalt); val databaseKeyBytes = databaseKey.toByteArray(StandardCharsets.UTF_8)
        try {
            val wrapped = crypt(Cipher.ENCRYPT_MODE, databaseKeyBytes, wrapKey, wrapIv)
            val header = DataOutputStream(destination); header.write(V6_MAGIC); header.write(wrapSalt); header.write(wrapIv); header.writeInt(wrapped.size); header.write(wrapped); header.write(payloadSalt); header.write(payloadIv); header.flush(); wrapped.fill(0)
            val payloadKey = derive(databaseKey, payloadSalt)
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, payloadKey, GCMParameterSpec(TAG_BITS, payloadIv)); CipherOutputStream(destination, cipher)
            } finally { payloadKey.encoded.fill(0) }
        } finally { wrapSalt.fill(0); wrapIv.fill(0); payloadSalt.fill(0); payloadIv.fill(0); wrapKey.encoded.fill(0); databaseKeyBytes.fill(0) }
    }

    data class V6Input(val stream: CipherInputStream, val databaseKey: String)

    fun decryptingV6(source: InputStream, rootKey: String): V6Input {
        val header = DataInputStream(source); val magic = ByteArray(8); header.readFully(magic)
        if (!magic.contentEquals(V6_MAGIC)) throw VaultAuthenticationException("Invalid V6 backup magic")
        val wrapSalt = ByteArray(16); val wrapIv = ByteArray(12); header.readFully(wrapSalt); header.readFully(wrapIv)
        val wrappedLength = header.readInt(); if (wrappedLength !in 16..1024) throw VaultAuthenticationException("Invalid wrapped database key length")
        val wrapped = ByteArray(wrappedLength); header.readFully(wrapped)
        val wrapKey = derive(rootKey, wrapSalt)
        val databaseKeyBytes = try { crypt(Cipher.DECRYPT_MODE, wrapped, wrapKey, wrapIv) } finally { wrapSalt.fill(0); wrapIv.fill(0); wrapped.fill(0); wrapKey.encoded.fill(0) }
        val databaseKey = try { String(databaseKeyBytes, StandardCharsets.UTF_8) } finally { databaseKeyBytes.fill(0) }
        val payloadSalt = ByteArray(16); val payloadIv = ByteArray(12); header.readFully(payloadSalt); header.readFully(payloadIv)
        val payloadKey = derive(databaseKey, payloadSalt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, payloadKey, GCMParameterSpec(TAG_BITS, payloadIv)); V6Input(CipherInputStream(source, cipher), databaseKey)
        } finally { payloadSalt.fill(0); payloadIv.fill(0); payloadKey.encoded.fill(0) }
    }

    data class V6Decryption(val payload: ByteArray, val databaseKey: String)

    fun decryptV6(container: ByteArray, rootKey: String): V6Decryption {
        requireMagic(container, V6_MAGIC)
        if (container.size < 8 + 16 + 12 + 4 + 16 + 16 + 12 + 16) throw VaultAuthenticationException("Truncated V6 backup")
        val input = DataInputStream(container.inputStream())
        val magic = ByteArray(8); input.readFully(magic)
        val wrapSalt = ByteArray(16); input.readFully(wrapSalt); val wrapIv = ByteArray(12); input.readFully(wrapIv)
        val wrappedLength = input.readInt(); if (wrappedLength !in 16..1024 || wrappedLength > input.available() - 44) throw VaultAuthenticationException("Invalid wrapped database key length")
        val wrapped = ByteArray(wrappedLength); input.readFully(wrapped)
        val payloadSalt = ByteArray(16); input.readFully(payloadSalt); val payloadIv = ByteArray(12); input.readFully(payloadIv)
        val encryptedPayload = input.readBytes(); if (encryptedPayload.size < TAG_BYTES) throw VaultAuthenticationException("Truncated V6 payload")
        val wrapKey = derive(rootKey, wrapSalt)
        var databaseKeyBytes: ByteArray? = null
        try {
            databaseKeyBytes = crypt(Cipher.DECRYPT_MODE, wrapped, wrapKey, wrapIv)
            val databaseKey = String(databaseKeyBytes, StandardCharsets.UTF_8)
            val payloadKey = derive(databaseKey, payloadSalt)
            return try { V6Decryption(crypt(Cipher.DECRYPT_MODE, encryptedPayload, payloadKey, payloadIv), databaseKey) }
            finally { payloadKey.encoded.fill(0) }
        } finally {
            wrapSalt.fill(0); wrapIv.fill(0); wrapped.fill(0); payloadSalt.fill(0); payloadIv.fill(0); encryptedPayload.fill(0); wrapKey.encoded.fill(0); databaseKeyBytes?.fill(0)
        }
    }

    fun detectVersion(container: ByteArray): Int = when {
        container.size >= 8 && container.copyOfRange(0, 8).contentEquals(V6_MAGIC) -> 6
        container.size >= 8 && container.copyOfRange(0, 8).contentEquals(V5_MAGIC) -> 5
        else -> throw VaultAuthenticationException("Unknown KeyScan backup format")
    }

    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }

    private fun crypt(mode: Int, input: ByteArray, key: SecretKeySpec, iv: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(TAG_BITS, iv)) }.doFinal(input)
    } catch (_: AEADBadTagException) { throw VaultAuthenticationException("Backup authentication failed") }

    private fun requireMagic(container: ByteArray, expected: ByteArray) {
        if (container.size < expected.size || !container.copyOfRange(0, expected.size).contentEquals(expected)) throw VaultAuthenticationException("Invalid backup magic")
    }
}
