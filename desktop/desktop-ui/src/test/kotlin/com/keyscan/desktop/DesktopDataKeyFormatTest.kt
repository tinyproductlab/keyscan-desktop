package com.keyscan.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDataKeyFormatTest {
    @Test fun `generated data protection keys match Android alphabet and supported lengths`() {
        listOf(8, 16, 32).forEach { requestedLength ->
            repeat(5) {
                val key = generateDesktopDataKey(requestedLength)
                val compact = key.replace("-", "")
                assertEquals(requestedLength, compact.length)
                assertTrue(compact.matches(Regex("[A-Z0-9]+")))
                assertTrue(compact.all { it !in "0O1I" })
                assertTrue(key.split("-").dropLast(1).all { it.length == 4 })
            }
        }
    }

    @Test fun `generated data protection keys clamp to Android supported range`() {
        assertEquals(8, generateDesktopDataKey(1).replace("-", "").length)
        assertEquals(32, generateDesktopDataKey(99).replace("-", "").length)
    }

    @Test fun `import parser accepts Android style key documents and normalizes case`() {
        val document = "KeyScan data protection key\nKeep this file private\n\n abcd-efgh-2345-6789 \n"
        assertEquals("ABCD-EFGH-2345-6789", parseDesktopDataKey(document))
        assertEquals(null, parseDesktopDataKey("KeyScan\nnot a key"))
        assertEquals(null, parseDesktopDataKey("ABCD-!!!!-2345"))
    }
}
