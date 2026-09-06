package com.keyscan.desktop

import com.keyscan.core.security.SecretStore
import com.keyscan.core.security.VaultAuthenticationException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WindowsPinQuickUnlockStoreTest {
    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        override fun put(id: String, secret: CharArray) { values[id] = secret.copyOf() }
        override fun get(id: String): CharArray? = values[id]?.copyOf()
        override fun remove(id: String) { values.remove(id)?.fill('\u0000') }
    }

    @Test fun quickUnlockUsesPinVerifierWithoutPersistingPinOrDataKey() {
        val file = Files.createTempDirectory("keyscan-pin-unlock").resolve("pin.properties")
        val store = WindowsPinQuickUnlockStore(file, MemorySecrets())
        store.enable("1234", "database-key".toCharArray())
        val text = Files.readString(file)
        assertFalse(text.contains("1234")); assertFalse(text.contains("database-key"))
        store.unlock("1234").useDatabaseKey { assertEquals("database-key", String(it)) }
        assertFailsWith<VaultAuthenticationException> { store.unlock("1235") }
    }
}
