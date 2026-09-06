package com.keyscan.nativehost

import com.keyscan.core.nativebridge.NativeMessagingProtocol
import com.keyscan.core.nativebridge.NativeMessagingProtocol.Response
import com.keyscan.core.security.WindowsDpapiSecretStore
import com.keyscan.core.vault.VaultBootstrapPath
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private const val SECRET_ID = "native_bridge_session"

fun main(args: Array<String>) {
    val host = NativeHostClient.defaultWindows()
    while (true) {
        val request = runCatching { NativeMessagingProtocol.readFrame(System.`in`) }.getOrElse { return }
            ?: return
        val caller = host.trustedCaller(args.asList())
        val response = if (caller == null) {
            failure(request, "DENIED")
        } else {
            val forwarded = runCatching { NativeMessagingProtocol.attachNativeCaller(request, caller) }.getOrNull()
            if (forwarded == null) failure(request, "DENIED")
            else runCatching { host.forward(forwarded) }.getOrElse { failure(request, "HOST_NOT_FOUND") }
        }
        try { NativeMessagingProtocol.writeFrame(System.out, response) } finally { response.fill(0) }
    }
}

internal class NativeHostClient(
    private val endpointFile: Path,
    private val allowlistFile: Path,
    private val tokenProvider: () -> CharArray?,
) {
    fun callerAllowed(callers: Iterable<String>): Boolean {
        return trustedCaller(callers) != null
    }

    fun trustedCaller(callers: Iterable<String>): String? {
        val normalized = callers.map { it.trim().trimEnd('/') }.filter(String::isNotEmpty).toSet()
        if (normalized.isEmpty()) return null
        if (!Files.isRegularFile(allowlistFile)) return null
        return Files.readAllLines(allowlistFile).asSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { it.trimEnd('/') }
            .firstOrNull(normalized::contains)
    }

    fun forward(request: ByteArray): ByteArray {
        val endpoint = Properties().also { Files.newInputStream(endpointFile).use(it::load) }
        require(endpoint.getProperty("formatVersion") == "1")
        val address = InetAddress.getByName(endpoint.getProperty("address"))
        require(address.isLoopbackAddress) { "Endpoint is not loopback" }
        val port = endpoint.getProperty("port").toInt().also { require(it in 1..65535) }
        val token = tokenProvider() ?: error("Desktop session is unavailable")
        try {
            val bytes = String(token).toByteArray(StandardCharsets.US_ASCII)
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), 2_000); socket.soTimeout = 65_000
                    val output = DataOutputStream(socket.getOutputStream())
                    output.writeShort(bytes.size); output.write(bytes)
                    NativeMessagingProtocol.writeFrame(output, request)
                    return NativeMessagingProtocol.readFrame(socket.getInputStream()) ?: error("Desktop closed IPC")
                }
            } finally { bytes.fill(0) }
        } finally { token.fill('\u0000') }
    }

    companion object {
        fun defaultWindows(): NativeHostClient {
            require(WindowsDpapiSecretStore.isWindows()) { "Windows native host requires DPAPI" }
            val base = VaultBootstrapPath.baseDirectory()
            val secrets = WindowsDpapiSecretStore(WindowsDpapiSecretStore.defaultDirectory())
            return NativeHostClient(base.resolve("native-bridge.properties"), base.resolve("native-host-allowlist.txt")) { secrets.get(SECRET_ID) }
        }
    }
}

private fun failure(request: ByteArray, error: String): ByteArray {
    val id = runCatching { NativeMessagingProtocol.parseRequest(request).id }.getOrDefault("")
    return NativeMessagingProtocol.encodeResponse(Response(id, ok = false, error = error))
}
