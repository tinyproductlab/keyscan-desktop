package com.keyscan.core.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsDpapiSecretStoreTest {
    @Test fun `DPAPI protects round trips and deletes credential`() {
        if (!WindowsDpapiSecretStore.isWindows()) return
        val directory = Files.createTempDirectory("keyscan-dpapi-test")
        val store = WindowsDpapiSecretStore(directory); val input = "密码-Secret-123".toCharArray()
        store.put("webdav-primary-password", input)
        val protected = Files.readAllBytes(directory.resolve("webdav-primary-password.dpapi"))
        assertFalse(protected.toString(Charsets.UTF_8).contains("Secret-123"))
        val restored = store.get("webdav-primary-password")
        assertContentEquals(input, restored); restored?.fill('\u0000')
        store.remove("webdav-primary-password")
        assertNull(store.get("webdav-primary-password")); assertTrue(Files.notExists(directory.resolve("webdav-primary-password.dpapi")))
    }
}
