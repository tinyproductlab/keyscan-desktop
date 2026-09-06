package com.keyscan.desktop

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.security.SecretStore
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCredentialApprovalBrokerTest {
    @Test fun explicitApprovalCompletesOnlyMatchingPendingRequest() {
        val broker = NativeCredentialApprovalBroker()
        val result = CompletableFuture.supplyAsync { broker.request("https://example.com", entry()) }
        val pending = awaitPending(broker)
        broker.resolve(pending.requestId + 1, true)
        assertFalse(result.isDone)
        broker.resolve(pending.requestId, true)
        assertTrue(result.get(2, TimeUnit.SECONDS))
        assertTrue(broker.pending.value == null)
    }

    @Test fun lockCancellationDeniesAndConcurrentRequestIsDenied() {
        val broker = NativeCredentialApprovalBroker()
        val first = CompletableFuture.supplyAsync { broker.request("https://example.com", entry()) }
        awaitPending(broker)
        assertFalse(broker.request("https://example.com", entry().copy(id = "second")))
        broker.cancel()
        assertFalse(first.get(2, TimeUnit.SECONDS))
        assertTrue(broker.pending.value == null)
    }

    @Test fun bridgeStartupFailureRemovesSessionSecretAndEndpoint() {
        val root = Files.createTempDirectory("keyscan-native-start-failure")
        val invalidParent = root.resolve("not-a-directory").also { Files.writeString(it, "block") }
        val endpoint = invalidParent.resolve("endpoint.properties")
        val secrets = MemorySecrets()
        assertTrue(runCatching { NativeBridgeServer.start(endpoint, secrets) }.isFailure)
        assertTrue(secrets.get(NativeBridgeServer.SESSION_SECRET_ID) == null)
        assertTrue(!Files.exists(endpoint))
    }

    @Test fun completedLockIsABarrierForLaterNativeResponses() {
        val gate = NativeBridgeAccessGate()
        gate.unlock { }
        val transmissionEntered = CountDownLatch(1)
        val releaseTransmission = CountDownLatch(1)
        val transmission = CompletableFuture.runAsync {
            gate.transmit { unlocked ->
                assertTrue(unlocked)
                transmissionEntered.countDown()
                assertTrue(releaseTransmission.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(transmissionEntered.await(2, TimeUnit.SECONDS))
        val lockStarted = CountDownLatch(1)
        val locking = CompletableFuture.runAsync { lockStarted.countDown(); gate.lock { } }
        assertTrue(lockStarted.await(2, TimeUnit.SECONDS))
        Thread.sleep(30) // The lock call has started but must remain behind the active transmission.
        assertFalse(locking.isDone)
        releaseTransmission.countDown()
        transmission.get(2, TimeUnit.SECONDS)
        locking.get(2, TimeUnit.SECONDS)
        gate.transmit { unlocked -> assertFalse(unlocked) }
    }

    private fun awaitPending(broker: NativeCredentialApprovalBroker): NativeCredentialApproval {
        repeat(200) { broker.pending.value?.let { return it }; Thread.sleep(5) }
        error("Approval did not become pending")
    }

    private fun entry() = PasswordEntry("one", "Mail", "example.com", "alice", "secret")

    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, CharArray>()
        override fun put(id: String, secret: CharArray) { values.remove(id)?.fill('\u0000'); values[id] = secret.copyOf() }
        override fun get(id: String): CharArray? = values[id]?.copyOf()
        override fun remove(id: String) { values.remove(id)?.fill('\u0000') }
    }
}
