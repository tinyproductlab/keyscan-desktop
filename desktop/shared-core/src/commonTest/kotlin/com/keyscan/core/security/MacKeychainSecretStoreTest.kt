package com.keyscan.core.security

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MacKeychainSecretStoreTest {
    @Test fun `mac keychain round trip keeps the secret out of project files`() {
        if (!MacKeychainSecretStore.isMacOs()) return
        val service = "com.keyscan.desktop.test.${UUID.randomUUID()}"
        val store = MacKeychainSecretStore(service)
        val id = "round_trip"
        val secret = "KeyScan-秘密-123".toCharArray()
        try {
            store.put(id, secret)
            val restored = store.get(id)
            try { assertContentEquals(secret, restored) }
            finally { restored?.fill('\u0000') }
            store.remove(id)
            assertNull(store.get(id))
        } finally {
            runCatching { store.remove(id) }
            secret.fill('\u0000')
        }
    }

    @Test fun `mac keychain rejects unsafe identifiers`() {
        if (!MacKeychainSecretStore.isMacOs()) return
        val store = MacKeychainSecretStore("com.keyscan.desktop.test.validation")
        assertFailsWith<IllegalArgumentException> { store.get("../unsafe") }
    }
}
