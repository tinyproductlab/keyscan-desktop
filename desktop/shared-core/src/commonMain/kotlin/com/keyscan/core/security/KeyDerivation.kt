package com.keyscan.core.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object KeyDerivation {
    private const val ROOT_DOMAIN = "KeyScanVaultRoot:v1"
    private const val ROOT_ITERATIONS = 600_000
    private const val KEY_BITS = 256

    /** Kept byte-for-byte compatible with Android. Inputs are intentionally not trimmed or normalized. */
    fun deriveRootKey(pin: String, dataProtectionKey: String): String {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        require(dataProtectionKey.isNotEmpty()) { "Data protection key must not be empty" }
        val material = "$ROOT_DOMAIN\nPIN=$pin\nDEK=$dataProtectionKey"
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(ROOT_DOMAIN.toByteArray(StandardCharsets.UTF_8))
        val materialChars = material.toCharArray()
        val spec = PBEKeySpec(materialChars, salt, ROOT_ITERATIONS, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            try { Base64.getEncoder().encodeToString(encoded) } finally { encoded.fill(0) }
        } finally {
            spec.clearPassword(); materialChars.fill('\u0000'); salt.fill(0)
        }
    }
}
