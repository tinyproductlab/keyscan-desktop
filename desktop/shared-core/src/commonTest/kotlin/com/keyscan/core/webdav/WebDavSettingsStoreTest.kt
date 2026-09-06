package com.keyscan.core.webdav

import com.keyscan.core.security.SecretStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebDavSettingsStoreTest {
    @Test fun `ordinary settings never contain passwords`() {
        val root = Files.createTempDirectory("webdav-settings"); val file = root.resolve("webdav.properties"); val secrets = MemorySecrets()
        val store = WebDavSettingsStore(file, secrets); val password = "very-secret".toCharArray()
        store.save(WebDavSettings(WebDavTargetSettings(true, "https://dav.example.test/root/", "alice")), password)
        val raw = Files.readString(file)
        assertFalse(raw.contains("very-secret")); assertEquals("https://dav.example.test/root", store.load().primary.url)
        assertContentEquals("very-secret".toCharArray(), store.credentials("primary", "alice")!!.password)
        assertContentEquals(CharArray(password.size), password)
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        override fun put(id: String, secret: CharArray) { values[id] = secret.copyOf() }
        override fun get(id: String) = values[id]?.copyOf()
        override fun remove(id: String) { values.remove(id)?.fill('\u0000') }
    }
}
