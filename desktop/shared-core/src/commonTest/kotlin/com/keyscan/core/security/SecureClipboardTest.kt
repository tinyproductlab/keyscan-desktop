package com.keyscan.core.security

import kotlin.test.Test
import kotlin.test.assertEquals

class SecureClipboardTest {
    @Test fun `clears only clipboard value still owned by KeyScan`() {
        val access = MemoryClipboard(); val clipboard = SecureClipboard(access)
        clipboard.copy("password", 30); clipboard.clearIfOwned(); assertEquals("", access.value)
        clipboard.copy("password", 30); access.value = "user copied something else"; clipboard.clearIfOwned()
        assertEquals("user copied something else", access.value); clipboard.close()
    }
    private class MemoryClipboard : ClipboardAccess {
        var value = ""
        override fun readText() = value
        override fun writeText(value: String) { this.value = value }
    }
}
