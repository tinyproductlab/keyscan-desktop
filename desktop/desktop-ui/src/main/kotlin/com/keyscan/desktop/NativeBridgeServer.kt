package com.keyscan.desktop

import com.keyscan.core.nativebridge.NativeMessagingProtocol
import com.keyscan.core.nativebridge.NativeMessagingService
import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.security.SecretStore
import com.keyscan.core.security.WindowsDpapiSecretStore
import com.keyscan.core.vault.EncryptedVaultStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NativeCredentialApproval(val requestId: Long, val origin: String, val label: String, val username: String)
data class NativeBrowserPairingRequest(val requestId: Long, val browser: String, val extensionId: String, val version: String)

internal class NativeBridgeAccessGate {
    private val monitor = Any()
    private var unlocked = false
    fun unlock(action: () -> Unit) = synchronized(monitor) { action(); unlocked = true }
    fun lock(action: () -> Unit) = synchronized(monitor) { unlocked = false; action() }
    fun <T> transmit(action: (Boolean) -> T): T = synchronized(monitor) { action(unlocked) }
}

class NativeCredentialApprovalBroker {
    private data class Waiting(val display: NativeCredentialApproval, val result: CompletableFuture<Boolean>)
    private val current = AtomicReference<Waiting?>(null)
    private val mutablePending = MutableStateFlow<NativeCredentialApproval?>(null)
    val pending: StateFlow<NativeCredentialApproval?> = mutablePending
    private val sequence = java.util.concurrent.atomic.AtomicLong()

    fun request(origin: String, entry: com.keyscan.core.model.PasswordEntry): Boolean {
        val waiting = Waiting(NativeCredentialApproval(sequence.incrementAndGet(), origin, entry.title, entry.username), CompletableFuture())
        if (!current.compareAndSet(null, waiting)) return false
        mutablePending.value = waiting.display
        return try { waiting.result.get(60, TimeUnit.SECONDS) }
        catch (_: Exception) { false }
        finally { current.compareAndSet(waiting, null); if (mutablePending.value?.requestId == waiting.display.requestId) mutablePending.value = null }
    }

    fun resolve(requestId: Long, approved: Boolean) {
        current.get()?.takeIf { it.display.requestId == requestId }?.result?.complete(approved)
    }
    fun cancel() { current.getAndSet(null)?.result?.complete(false); mutablePending.value = null }
}

class NativeBrowserPairingBroker {
    private data class Waiting(val display: NativeBrowserPairingRequest, val result: CompletableFuture<Boolean>)
    private val current = AtomicReference<Waiting?>(null)
    private val mutablePending = MutableStateFlow<NativeBrowserPairingRequest?>(null)
    val pending: StateFlow<NativeBrowserPairingRequest?> = mutablePending
    private val sequence = java.util.concurrent.atomic.AtomicLong()

    fun request(browser: String, extensionId: String, version: String): Boolean {
        val waiting = Waiting(NativeBrowserPairingRequest(sequence.incrementAndGet(), browser, extensionId, version), CompletableFuture())
        if (!current.compareAndSet(null, waiting)) return false
        mutablePending.value = waiting.display
        return try { waiting.result.get(60, TimeUnit.SECONDS) }
        catch (_: Exception) { false }
        finally { current.compareAndSet(waiting, null); if (mutablePending.value?.requestId == waiting.display.requestId) mutablePending.value = null }
    }

    fun resolve(requestId: Long, approved: Boolean) { current.get()?.takeIf { it.display.requestId == requestId }?.result?.complete(approved) }
    fun cancel() { current.getAndSet(null)?.result?.complete(false); mutablePending.value = null }
}

/**
 * Browser extensions can ask for an unlock, but the secret is always entered in
 * the desktop app.  Only one interactive request is allowed at a time.
 */
class NativeUnlockRequestBroker {
    private data class Waiting(val requestId: Long, val result: CompletableFuture<Boolean>)
    private val current = AtomicReference<Waiting?>(null)
    private val mutablePending = MutableStateFlow<Long?>(null)
    val pending: StateFlow<Long?> = mutablePending
    private val sequence = java.util.concurrent.atomic.AtomicLong()

    fun request(): Boolean {
        val waiting = Waiting(sequence.incrementAndGet(), CompletableFuture())
        if (!current.compareAndSet(null, waiting)) return false
        mutablePending.value = waiting.requestId
        return try { waiting.result.get(60, TimeUnit.SECONDS) }
        catch (_: Exception) { false }
        finally {
            current.compareAndSet(waiting, null)
            if (mutablePending.value == waiting.requestId) mutablePending.value = null
        }
    }

    fun unlocked() { current.get()?.result?.complete(true) }
    fun cancel() { current.getAndSet(null)?.result?.complete(false); mutablePending.value = null }
}

class NativeBridgeServer private constructor(
    private val endpointFile: Path,
    private val secretStore: SecretStore,
) : AutoCloseable {
    val approvals = NativeCredentialApprovalBroker()
    val pairings = NativeBrowserPairingBroker()
    val unlockRequests = NativeUnlockRequestBroker()
    private val uiVault = AtomicReference<EncryptedVaultStore?>(null)
    private val accessGate = NativeBridgeAccessGate()
    private val plugins = BrowserPluginRegistry(endpointFile.parent.resolve("browser-plugins.properties"), secretStore)
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)).toCharArray()
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "keyscan-native-ipc").apply { isDaemon = true } }
    @Volatile private var closed = false

    init {
        try {
            secretStore.put(SESSION_SECRET_ID, token)
            writeEndpoint()
            workers.execute {
                while (!closed) runCatching { server.accept() }.onSuccess { socket -> workers.execute { handle(socket) } }
            }
        } catch (error: Exception) {
            closed = true
            runCatching { server.close() }; workers.shutdownNow()
            runCatching { Files.deleteIfExists(endpointFile) }
            runCatching { secretStore.remove(SESSION_SECRET_ID) }
            token.fill('\u0000')
            throw error
        }
    }

    fun unlockedWith(value: EncryptedVaultStore) {
        accessGate.unlock { uiVault.set(value) }
        unlockRequests.unlocked()
    }
    fun browserPlugins(): List<BrowserPluginRecord> = plugins.records()
    fun browserPluginState(plugin: BrowserPluginRecord): BrowserPluginConnectionState = plugins.connectionState(plugin)
    fun revokeBrowserPlugin(caller: String) = plugins.revoke(caller)
    fun locked() {
        approvals.cancel()
        pairings.cancel()
        unlockRequests.cancel()
        accessGate.lock { uiVault.set(null) }
    }

    private fun currentVault(): EncryptedVaultStore? = uiVault.get()

    private fun requestDesktopUnlock(): Boolean =
        if (currentVault() != null) true else unlockRequests.request()

    private fun handle(socket: Socket) = socket.use {
        it.soTimeout = 70_000
        val input = DataInputStream(it.getInputStream())
        val suppliedSize = input.readUnsignedShort()
        require(suppliedSize in 1..256) { "Invalid IPC token" }
        val supplied = ByteArray(suppliedSize).also(input::readFully)
        val expected = String(token).toByteArray(StandardCharsets.US_ASCII)
        try {
            if (!MessageDigest.isEqual(expected, supplied)) return@use
            val request = NativeMessagingProtocol.readFrame(input) ?: return@use
            val parsed = NativeMessagingProtocol.parseRequest(request)
            val pairingResponse = when (parsed) {
                is NativeMessagingProtocol.Request.Register -> handleRegistration(parsed)
                is NativeMessagingProtocol.Request.Heartbeat -> handleHeartbeat(parsed)
                else -> null
            }
            if (pairingResponse != null) {
                NativeMessagingProtocol.writeFrame(DataOutputStream(it.getOutputStream()), pairingResponse)
                pairingResponse.fill(0)
                return@use
            }
            val caller = parsed.nativeCaller?.trimEnd('/')
            if (caller == null || !plugins.tokenMatches(caller, parsed.token)) {
                val denied = NativeMessagingProtocol.encodeResponse(NativeMessagingProtocol.Response(parsed.id, ok = false, error = "PAIRING_REQUIRED"))
                NativeMessagingProtocol.writeFrame(DataOutputStream(it.getOutputStream()), denied)
                denied.fill(0)
                return@use
            }
            plugins.markSeen(caller)
            val service = NativeMessagingService(
                isUnlocked = { currentVault() != null },
                passwords = { currentVault()?.listPasswords().orEmpty() },
                requestDesktopUnlock = ::requestDesktopUnlock,
                saveCredential = ::saveCredentialFromBrowser,
                authorize = { origin, entry -> approvals.request(origin.value, entry) },
            )
            var response = service.handle(request)
            accessGate.transmit { unlocked ->
                // The vault may have locked after authorization but before this socket write.
                // Re-evaluate the same request against locked state so no response prepared by
                // the old session can be transmitted after locked() has completed.
                if (!unlocked) {
                    response.fill(0)
                    response = service.handle(request)
                }
                try { NativeMessagingProtocol.writeFrame(DataOutputStream(it.getOutputStream()), response) }
                finally { response.fill(0) }
            }
        } finally {
            supplied.fill(0); expected.fill(0)
        }
    }

    private fun handleRegistration(request: NativeMessagingProtocol.Request.Register): ByteArray {
        val caller = request.nativeCaller?.trimEnd('/')
        log("register id=${request.id} browser=${request.browser} extension=${request.extensionId} caller=${caller ?: "<none>"} hasToken=${!request.token.isNullOrBlank()}")
        if (caller == null || !nativeCallerMatches(request, caller)) {
            log("register-denied id=${request.id} reason=caller_mismatch")
            return NativeMessagingProtocol.encodeResponse(NativeMessagingProtocol.Response(request.id, ok = false, error = "DENIED"))
        }
        if (plugins.tokenMatches(caller, request.token)) {
            plugins.markSeen(caller)
            log("register-ok id=${request.id} reason=existing_token")
            return NativeMessagingProtocol.encodeResponse(NativeMessagingProtocol.Response(request.id, ok = true, state = "paired"))
        }
        log("register-waiting-for-user id=${request.id}")
        val approved = pairings.request(request.browser, request.extensionId, request.version)
        log("register-user-result id=${request.id} approved=$approved")
        if (!approved) {
            return NativeMessagingProtocol.encodeResponse(NativeMessagingProtocol.Response(request.id, ok = false, error = "PAIRING_DENIED"))
        }
        val token = plugins.authorize(caller, request.browser, request.extensionId, request.version)
        log("register-authorized id=${request.id} records=${plugins.records().size}")
        return NativeMessagingProtocol.encodeResponse(NativeMessagingProtocol.Response(request.id, ok = true, state = "paired", pairingToken = token))
    }

    private fun handleHeartbeat(request: NativeMessagingProtocol.Request.Heartbeat): ByteArray {
        val caller = request.nativeCaller?.trimEnd('/')
        return if (caller == null || !plugins.tokenMatches(caller, request.token)) NativeMessagingProtocol.encodeResponse(NativeMessagingProtocol.Response(request.id, ok = false, error = "PAIRING_REQUIRED"))
        else {
            plugins.markSeen(caller)
            NativeMessagingProtocol.encodeResponse(NativeMessagingProtocol.Response(request.id, ok = true, state = "paired"))
        }
    }

    private fun saveCredentialFromBrowser(request: NativeMessagingProtocol.Request.SaveCredential): Boolean {
        val vault = currentVault() ?: return false
        val now = System.currentTimeMillis()
        val existingById = request.credentialId
            ?.let { id -> vault.findPassword(id) }
            ?.takeIf { entry -> NativeMessagingProtocol.metadataFor(request.origin, listOf(entry)).isNotEmpty() }
        val existingByUsername = vault.listPasswords().firstOrNull { entry ->
            entry.username.equals(request.username, ignoreCase = true) &&
                NativeMessagingProtocol.metadataFor(request.origin, listOf(entry)).isNotEmpty()
        }
        val existing = existingById ?: existingByUsername
        if (request.mode == "update" && existing == null) {
            log("save-credential origin=${request.origin.value} username=${request.username.take(80)} update-miss")
            return false
        }
        val entry = if (existing != null) {
            existing.copy(
                title = existing.title.ifBlank { request.title },
                username = request.username.ifBlank { existing.username },
                password = request.password,
                updatedAt = now,
            )
        } else {
            PasswordEntry(
                id = UUID.randomUUID().toString(),
                title = request.title.ifBlank { request.origin.asciiHost },
                websiteDomain = request.origin.asciiHost,
                username = request.username,
                password = request.password,
                createdAt = now,
                updatedAt = now,
            )
        }
        vault.savePassword(entry)
        log("save-credential origin=${request.origin.value} username=${request.username.take(80)} mode=${request.mode} updated=${existing != null}")
        return true
    }

    private fun nativeCallerMatches(request: NativeMessagingProtocol.Request.Register, caller: String): Boolean {
        val expected = if (request.browser == "firefox" || request.browser == "safari") request.extensionId else "chrome-extension://${request.extensionId}"
        return caller == expected.trimEnd('/')
    }

    private fun writeEndpoint() {
        Files.createDirectories(endpointFile.parent)
        val properties = Properties().apply {
            setProperty("formatVersion", "1")
            setProperty("address", server.inetAddress.hostAddress)
            setProperty("port", server.localPort.toString())
            setProperty("pid", ProcessHandle.current().pid().toString())
        }
        val temporary = Files.createTempFile(endpointFile.parent, "native-bridge-", ".tmp")
        try {
            Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING).use { properties.store(it, "KeyScan local IPC endpoint; authentication token is DPAPI protected") }
            try { Files.move(temporary, endpointFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: Exception) { Files.move(temporary, endpointFile, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun log(message: String) {
        runCatching {
            val file = endpointFile.parent.resolve("native-bridge.log")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                "${Instant.now()} $message${System.lineSeparator()}",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        approvals.cancel()
        pairings.cancel()
        unlockRequests.cancel()
        accessGate.lock { uiVault.set(null) }
        runCatching { server.close() }
        workers.shutdownNow()
        Files.deleteIfExists(endpointFile); secretStore.remove(SESSION_SECRET_ID); token.fill('\u0000')
    }

    companion object {
        const val SESSION_SECRET_ID = "native_bridge_session"
        fun defaultEndpointFile(): Path = WindowsDpapiSecretStore.defaultDirectory().parent.resolve("native-bridge.properties")
        fun startWindows(): NativeBridgeServer? = if (WindowsDpapiSecretStore.isWindows())
            NativeBridgeServer(defaultEndpointFile(), WindowsDpapiSecretStore(WindowsDpapiSecretStore.defaultDirectory())) else null

        fun start(endpointFile: Path, secretStore: SecretStore) = NativeBridgeServer(endpointFile, secretStore)
    }
}
