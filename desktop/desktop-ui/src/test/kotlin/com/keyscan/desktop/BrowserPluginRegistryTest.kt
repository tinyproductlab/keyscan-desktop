package com.keyscan.desktop

import com.keyscan.core.security.SecretStore
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserPluginRegistryTest {
    @Test fun pairingTokenIsSecretAndCanBeRevoked() {
        val root = Files.createTempDirectory("keyscan-browser-pairing")
        val secrets = MemorySecrets()
        val registry = BrowserPluginRegistry(root.resolve("plugins.properties"), secrets)
        val caller = "chrome-extension://abcdefghijklmnopabcdefghijklmnop"
        val token = registry.authorize(caller, "Chrome", "abcdefghijklmnopabcdefghijklmnop", "0.1.0")

        assertTrue(registry.tokenMatches(caller, token))
        assertFalse(Files.readString(root.resolve("plugins.properties")).contains(token))
        assertEquals("Chrome", registry.records().single().browser)

        registry.revoke(caller)
        assertFalse(registry.tokenMatches(caller, token))
        assertTrue(registry.records().isEmpty())
    }

    @Test fun connectionStateUsesTheHeartbeatTimeout() {
        val registry = BrowserPluginRegistry(Files.createTempDirectory("keyscan-browser-state").resolve("plugins.properties"), MemorySecrets())
        val record = BrowserPluginRecord("caller", "chrome", "id", "1", Instant.EPOCH, Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals(BrowserPluginConnectionState.ONLINE, registry.connectionState(record, Instant.parse("2026-01-01T00:01:30Z")))
        assertEquals(BrowserPluginConnectionState.OFFLINE, registry.connectionState(record, Instant.parse("2026-01-01T00:01:31Z")))
        assertEquals(BrowserPluginConnectionState.NEVER_CONNECTED, registry.connectionState(record.copy(lastSeenAt = null)))
    }

    @Test fun pairingSecretIdUsesWindowsDpapiSafeCharacters() {
        val registry = BrowserPluginRegistry(Files.createTempDirectory("keyscan-browser-dpapi-safe").resolve("plugins.properties"), ValidatingSecrets())
        val caller = "chrome-extension://eiaedaglbbchjdgcbnklddopdjanimaj"
        val token = registry.authorize(caller, "chrome", "eiaedaglbbchjdgcbnklddopdjanimaj", "0.1.0")
        assertTrue(registry.tokenMatches(caller, token))
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        override fun put(id: String, secret: CharArray) { values.remove(id)?.fill('\u0000'); values[id] = secret.copyOf() }
        override fun get(id: String): CharArray? = values[id]?.copyOf()
        override fun remove(id: String) { values.remove(id)?.fill('\u0000') }
    }

    private class ValidatingSecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
        override fun put(id: String, secret: CharArray) {
            require(idPattern.matches(id)) { "Invalid secret identifier" }
            values.remove(id)?.fill('\u0000')
            values[id] = secret.copyOf()
        }
        override fun get(id: String): CharArray? = values[id]?.copyOf()
        override fun remove(id: String) { values.remove(id)?.fill('\u0000') }
    }
}
