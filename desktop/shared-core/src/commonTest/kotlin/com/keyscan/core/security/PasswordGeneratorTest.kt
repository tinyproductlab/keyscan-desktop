package com.keyscan.core.security

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordGeneratorTest {
    @Test fun `generated password has requested length and every selected class`() {
        val value = PasswordGenerator.generate(PasswordGeneratorOptions(length = 64), deterministicRandom())
        assertEquals(64, value.length)
        assertTrue(value.any(Char::isUpperCase)); assertTrue(value.any(Char::isLowerCase))
        assertTrue(value.any(Char::isDigit)); assertTrue(value.any { !it.isLetterOrDigit() })
        assertFalse(value.any { it in "0O1Il" })
    }

    @Test fun `single selected class and confusing character option are honored`() {
        val value = PasswordGenerator.generate(PasswordGeneratorOptions(32, uppercase = false, lowercase = false, digits = true, symbols = false, excludeConfusing = true), deterministicRandom())
        assertTrue(value.all { it in '2'..'9' })
    }

    @Test fun `invalid configurations are rejected`() {
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordGeneratorOptions(length = 3)) }
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordGeneratorOptions(12, false, false, false, false)) }
    }

    private fun deterministicRandom() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(7, 2, 9, 4)) }
}
