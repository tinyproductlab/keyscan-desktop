package com.keyscan.core.totp

import com.keyscan.core.model.TotpEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class TotpGeneratorTest {
    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    @Test fun matchesRfc6238Sha1Vectors() {
        val token = TotpEntry("rfc", "test", "RFC", rfcSecret, digits = 8, period = 30)
        mapOf(59L to "94287082", 1_111_111_109L to "07081804", 1_111_111_111L to "14050471", 1_234_567_890L to "89005924", 2_000_000_000L to "69279037").forEach { (seconds, expected) ->
            assertEquals(expected, TotpGenerator.code(token, seconds * 1000L))
        }
    }
    @Test fun remainingSecondsMatchesAndroidBoundary() {
        val token = TotpEntry("1", "a", "i", rfcSecret, period = 30)
        assertEquals(30, TotpGenerator.remainingSeconds(token, 60_000L)); assertEquals(1, TotpGenerator.remainingSeconds(token, 89_000L))
    }
}
