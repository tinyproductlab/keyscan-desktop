package com.keyscan.nativehost

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.nativebridge.NativeMessagingProtocol
import com.keyscan.core.security.SecretStore
import com.keyscan.core.vault.EncryptedVaultStore
import com.keyscan.desktop.NativeBridgeServer
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeHostIntegrationTest {
    @Test fun authenticatedHostReportsLockAndReturnsMetadataAfterUnlock() {
        val root = Files.createTempDirectory("keyscan-native-host")
        val endpoint = root.resolve("endpoint.properties")
        val allowlist = root.resolve("allowlist.txt")
        Files.writeString(allowlist, "chrome-extension://release-id/\n")
        val secrets = MemorySecrets()
        NativeBridgeServer.start(endpoint, secrets).use { server ->
            val client = NativeHostClient(endpoint, allowlist) { secrets.get(NativeBridgeServer.SESSION_SECRET_ID) }
            assertTrue(client.callerAllowed(listOf("chrome-extension://release-id")))
            Files.writeString(allowlist, "keyscan@keyscan.app\n")
            assertTrue(client.callerAllowed(listOf("C:\\KeyScan\\com.keyscan.desktop.firefox.json", "keyscan@keyscan.app")))
            assertTrue(!client.callerAllowed(listOf("chrome-extension://different-id")))

            val caller = "chrome-extension://release-id"
            val registration = NativeMessagingProtocol.attachNativeCaller("{\"id\":\"pair\",\"type\":\"register\",\"browser\":\"chrome\",\"extensionId\":\"release-id\",\"version\":\"1\"}".toByteArray(), caller)
            val pairing = CompletableFuture.supplyAsync { client.forward(registration).decodeToString() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (server.pairings.pending.value == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            val pending = checkNotNull(server.pairings.pending.value)
            server.pairings.resolve(pending.requestId, true)
            val pairingResponse = pairing.get(2, TimeUnit.SECONDS)
            val token = Regex("\\\"pairingToken\\\":\\\"([^\\\"]+)\\\"").find(pairingResponse)?.groupValues?.get(1) ?: error("Missing pairing token")
            val find = NativeMessagingProtocol.attachNativeCaller("{\"id\":\"1\",\"type\":\"findCredentials\",\"origin\":\"https://accounts.example.com\",\"token\":\"$token\"}".toByteArray(), caller)
            assertTrue("\"error\":\"LOCKED\"" in client.forward(find).decodeToString())

            val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() }).toCharArray()
            val vault = EncryptedVaultStore(root.resolve("vault.ksdb"), databaseKeyProvider = { block -> block(key.copyOf()) })
            vault.savePassword(PasswordEntry("mail", "Mail", "example.com", "alice", "never-over-ipc"))
            server.unlockedWith(vault)
            val response = client.forward(find).decodeToString()
            assertTrue("\"id\":\"mail\"" in response && "\"username\":\"alice\"" in response)
            assertTrue("never-over-ipc" !in response)
            key.fill('\u0000')
        }
        assertTrue(!Files.exists(endpoint))
        assertTrue(secrets.get(NativeBridgeServer.SESSION_SECRET_ID) == null)
    }

    @Test fun wrongIpcTokenCannotReadAResponse() {
        val root = Files.createTempDirectory("keyscan-native-host-token")
        val endpoint = root.resolve("endpoint.properties")
        val allowlist = root.resolve("allowlist.txt").also { Files.writeString(it, "chrome-extension://release-id\n") }
        val secrets = MemorySecrets()
        NativeBridgeServer.start(endpoint, secrets).use {
            val badClient = NativeHostClient(endpoint, allowlist) { "incorrect-token".toCharArray() }
            val request = "{\"id\":\"1\",\"type\":\"status\"}".toByteArray()
            assertTrue(runCatching { badClient.forward(request) }.isFailure)
        }
    }

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        override fun put(id: String, secret: CharArray) { values.remove(id)?.fill('\u0000'); values[id] = secret.copyOf() }
        override fun get(id: String): CharArray? = values[id]?.copyOf()
        override fun remove(id: String) { values.remove(id)?.fill('\u0000') }
    }
}
