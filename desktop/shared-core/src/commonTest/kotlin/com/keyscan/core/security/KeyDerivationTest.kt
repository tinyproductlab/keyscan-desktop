package com.keyscan.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KeyDerivationTest {
    @Test fun deterministicAndSensitiveToBothInputs() {
        val first = KeyDerivation.deriveRootKey("123456", "ABCD-1234")
        assertEquals(first, KeyDerivation.deriveRootKey("123456", "ABCD-1234"))
        assertNotEquals(first, KeyDerivation.deriveRootKey("123457", "ABCD-1234"))
        assertNotEquals(first, KeyDerivation.deriveRootKey("123456", "abcd-1234"))
    }
}
