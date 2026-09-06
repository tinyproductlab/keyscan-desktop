package com.keyscan.core.totp

import com.keyscan.core.model.TotpEntry
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpGenerator {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Mirrors Android OtpHelper exactly: stored algorithm is preserved, but code generation uses HMAC-SHA1. */
    fun code(token: TotpEntry, epochMillis: Long = System.currentTimeMillis()): String {
        val period = token.period.coerceAtLeast(1)
        val digits = if (token.digits <= 0) 6 else token.digits
        require(digits in 1..9) { "TOTP digits must be between 1 and 9" }
        val counter = epochMillis / 1000L / period
        val key = base32Decode(token.secret)
        val data = ByteBuffer.allocate(8).putLong(counter).array()
        return try {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(data)
            try {
                val offset = hash.last().toInt() and 0x0f
                val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                    ((hash[offset + 1].toInt() and 0xff) shl 16) or
                    ((hash[offset + 2].toInt() and 0xff) shl 8) or
                    (hash[offset + 3].toInt() and 0xff)
                val modulo = 10.0.pow(digits).toInt()
                String.format(Locale.US, "%0${digits}d", binary % modulo)
            } finally { hash.fill(0) }
        } finally { key.fill(0); data.fill(0) }
    }

    fun remainingSeconds(token: TotpEntry, epochMillis: Long = System.currentTimeMillis()): Int {
        val period = token.period.coerceAtLeast(1)
        return period - ((epochMillis / 1000L) % period).toInt()
    }

    fun normalizeSecret(secret: String): String = secret.replace(" ", "").uppercase(Locale.US)

    private fun base32Decode(secret: String): ByteArray {
        val normalized = normalizeSecret(secret).replace("=", "")
        val output = ByteArray(normalized.length * 5 / 8 + 8)
        var outputSize = 0; var bits = 0; var valueBuffer = 0
        for (char in normalized) {
            val value = ALPHABET.indexOf(char)
            if (value < 0) continue
            valueBuffer = (valueBuffer shl 5) or value; bits += 5
            if (bits >= 8) { output[outputSize++] = ((valueBuffer shr (bits - 8)) and 0xff).toByte(); bits -= 8 }
        }
        if (outputSize == 0) return "empty".toByteArray(StandardCharsets.UTF_8)
        return output.copyOf(outputSize).also { output.fill(0) }
    }
}
