package com.keyscan.core.security

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object VaultCrypto {
    const val ENVELOPE_PREFIX = "CQR1:AES-GCM:"
    private const val KDF_ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val DATABASE_KEY_BYTES = 32

    fun newDatabaseKey(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(DATABASE_KEY_BYTES).also(random::nextBytes)
        return try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
    }

    fun wrapDatabaseKey(databaseKey: String, rootKey: String, random: SecureRandom = SecureRandom()): String {
        require(databaseKey.isNotEmpty()) { "Database key must not be empty" }
        require(rootKey.isNotEmpty()) { "Root key must not be empty" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveAesKey(rootKey, salt)
        val plaintext = databaseKey.toByteArray(StandardCharsets.UTF_8)
        var encrypted: ByteArray? = null
        var container: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            encrypted = cipher.doFinal(plaintext)
            container = salt + iv + checkNotNull(encrypted)
            ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(container)
        } finally {
            plaintext.fill(0); encrypted?.fill(0); container?.fill(0)
            salt.fill(0); iv.fill(0); key.encoded.fill(0)
        }
    }

    /** Accepts both CQR1-prefixed and legacy unprefixed Android AES-GCM envelopes. */
    fun unwrapDatabaseKey(envelope: String, rootKey: String): String {
        require(rootKey.isNotEmpty()) { "Root key must not be empty" }
        val encoded = when {
            envelope.startsWith(ENVELOPE_PREFIX) -> envelope.substring(ENVELOPE_PREFIX.length)
            envelope.startsWith("CQR1:") -> throw IllegalArgumentException("Unsupported KeyScan envelope algorithm")
            else -> envelope
        }
        val payload = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            throw VaultAuthenticationException("Invalid vault envelope")
        }
        if (payload.size < SALT_BYTES + IV_BYTES + 16) throw VaultAuthenticationException("Truncated vault envelope")
        val salt = payload.copyOfRange(0, SALT_BYTES)
        val iv = payload.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val encrypted = payload.copyOfRange(SALT_BYTES + IV_BYTES, payload.size)
        val key = deriveAesKey(rootKey, salt)
        var plaintext: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            plaintext = cipher.doFinal(encrypted)
            String(checkNotNull(plaintext), StandardCharsets.UTF_8)
        } catch (_: AEADBadTagException) {
            throw VaultAuthenticationException("PIN or data protection key is incorrect")
        } finally {
            plaintext?.fill(0); payload.fill(0); salt.fill(0); iv.fill(0); encrypted.fill(0); key.encoded.fill(0)
        }
    }

    private fun deriveAesKey(password: String, salt: ByteArray): SecretKeySpec {
        val passwordChars = password.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, KDF_ITERATIONS, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(encoded, "AES") } finally { encoded.fill(0) }
        } finally { spec.clearPassword(); passwordChars.fill('\u0000') }
    }
}

class VaultAuthenticationException(message: String, cause: Throwable? = null) : SecurityException(message, cause)
