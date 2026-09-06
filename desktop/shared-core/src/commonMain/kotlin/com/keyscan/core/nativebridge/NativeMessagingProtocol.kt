package com.keyscan.core.nativebridge

import com.google.common.net.InternetDomainName
import com.keyscan.core.model.PasswordEntry
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.URI
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object NativeMessagingProtocol {
    const val MAX_MESSAGE_BYTES = 64 * 1024
    private const val MAX_REQUEST_ID_CHARS = 128
    private const val MAX_CREDENTIAL_RESULTS = 50
    private const val MAX_METADATA_LABEL_CHARS = 128
    private const val MAX_METADATA_USERNAME_CHARS = 256
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false }

    sealed interface Request { val id: String; val token: String?; val nativeCaller: String?
        data class Register(override val id: String, val browser: String, val extensionId: String, val version: String, override val token: String?, override val nativeCaller: String?) : Request
        data class Heartbeat(override val id: String, override val token: String?, override val nativeCaller: String?) : Request
        data class Status(override val id: String, override val token: String?, override val nativeCaller: String?) : Request
        /** Requests a trusted desktop-side unlock interaction. No credential is sent by the browser. */
        data class Unlock(override val id: String, override val token: String?, override val nativeCaller: String?) : Request
        data class FindCredentials(override val id: String, val origin: HttpsOrigin, override val token: String?, override val nativeCaller: String?) : Request
        data class RequestCredential(override val id: String, val origin: HttpsOrigin, val credentialId: String, override val token: String?, override val nativeCaller: String?) : Request
        data class SaveCredential(override val id: String, val origin: HttpsOrigin, val title: String, val username: String, val password: String, val mode: String, val credentialId: String?, override val token: String?, override val nativeCaller: String?) : Request
        data class GeneratePassword(override val id: String, val origin: HttpsOrigin, val length: Int, val mode: String, override val token: String?, override val nativeCaller: String?) : Request
    }

    @Serializable
    data class CredentialMetadata(val id: String, val label: String, val username: String)

    @Serializable
    data class CredentialFill(val username: String, val password: String)

    @Serializable
    data class Response(
        val id: String,
        val ok: Boolean,
        val state: String? = null,
        val error: String? = null,
        val credentials: List<CredentialMetadata>? = null,
        val credential: CredentialFill? = null,
        val generatedPassword: String? = null,
        val pairingToken: String? = null,
    )

    data class HttpsOrigin(val value: String, val asciiHost: String, val port: Int?)

    fun parseRequest(payload: ByteArray): Request {
        require(payload.size <= MAX_MESSAGE_BYTES) { "MESSAGE_TOO_LARGE" }
        val objectValue = json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        val id = objectValue.requiredString("id").also {
            require(it.isNotBlank() && it.length <= MAX_REQUEST_ID_CHARS) { "INVALID_ID" }
        }
        val nativeCaller = objectValue.optionalNativeCaller()
        return when (objectValue.requiredString("type")) {
            "register" -> {
                objectValue.requireOnly("id", "type", "browser", "extensionId", "version", "token", "nativeCaller")
                val browser = objectValue.requiredString("browser").lowercase()
                require(browser in setOf("chrome", "edge", "brave", "firefox", "safari")) { "INVALID_BROWSER" }
                val extensionId = objectValue.requiredString("extensionId")
                require(extensionId.length in 1..256) { "INVALID_EXTENSION" }
                val version = objectValue.requiredString("version")
                require(version.length <= 64) { "INVALID_VERSION" }
                Request.Register(id, browser, extensionId, version, objectValue.optionalToken(), nativeCaller)
            }
            "heartbeat" -> {
                objectValue.requireOnly("id", "type", "token", "nativeCaller")
                Request.Heartbeat(id, objectValue.requiredToken(), nativeCaller)
            }
            "status" -> {
                objectValue.requireOnly("id", "type", "token", "nativeCaller")
                Request.Status(id, objectValue.optionalToken(), nativeCaller)
            }
            "unlock" -> {
                objectValue.requireOnly("id", "type", "token", "nativeCaller")
                Request.Unlock(id, objectValue.requiredToken(), nativeCaller)
            }
            "findCredentials" -> {
                objectValue.requireOnly("id", "type", "origin", "token", "nativeCaller")
                Request.FindCredentials(id, parseHttpsOrigin(objectValue.requiredString("origin")), objectValue.requiredToken(), nativeCaller)
            }
            "requestCredential" -> {
                objectValue.requireOnly("id", "type", "origin", "credentialId", "token", "nativeCaller")
                val credentialId = objectValue.requiredString("credentialId")
                require(credentialId.isNotBlank() && credentialId.length <= 256) { "INVALID_CREDENTIAL_ID" }
                Request.RequestCredential(id, parseHttpsOrigin(objectValue.requiredString("origin")), credentialId, objectValue.requiredToken(), nativeCaller)
            }
            "saveCredential" -> {
                objectValue.requireOnly("id", "type", "origin", "title", "username", "password", "mode", "credentialId", "token", "nativeCaller")
                val title = objectValue.requiredString("title").trim().take(128)
                val username = objectValue.requiredString("username").trim().take(256)
                val password = objectValue.requiredString("password")
                val mode = objectValue.optionalString("mode")?.lowercase()?.takeIf { it in setOf("save", "update") } ?: "save"
                val credentialId = objectValue.optionalString("credentialId")?.trim()?.takeIf { it.isNotBlank() }
                require(credentialId == null || credentialId.length <= 256) { "INVALID_CREDENTIAL_ID" }
                require(title.isNotBlank() && (username.isNotBlank() || (mode == "update" && credentialId != null)) && password.length in 4..4096) { "INVALID_CREDENTIAL" }
                Request.SaveCredential(id, parseHttpsOrigin(objectValue.requiredString("origin")), title, username, password, mode, credentialId, objectValue.requiredToken(), nativeCaller)
            }
            "generatePassword" -> {
                objectValue.requireOnly("id", "type", "origin", "length", "mode", "token", "nativeCaller")
                val length = objectValue.optionalInt("length")?.coerceIn(4, 32) ?: 16
                val mode = objectValue.optionalString("mode")?.lowercase()?.takeIf { it in setOf("recommended", "compatible", "pin") } ?: "recommended"
                Request.GeneratePassword(id, parseHttpsOrigin(objectValue.requiredString("origin")), length, mode, objectValue.requiredToken(), nativeCaller)
            }
            else -> throw IllegalArgumentException("UNSUPPORTED_REQUEST")
        }
    }

    fun encodeResponse(response: Response): ByteArray = json.encodeToString(Response.serializer(), response).toByteArray(Charsets.UTF_8)

    /** Adds the caller identity supplied by the browser's native-messaging runtime. */
    fun attachNativeCaller(payload: ByteArray, caller: String): ByteArray {
        require(caller.length in 1..256) { "INVALID_CALLER" }
        val value = json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        require("nativeCaller" !in value) { "UNTRUSTED_CALLER_FIELD" }
        return json.encodeToString(JsonObject(value + ("nativeCaller" to JsonPrimitive(caller)))).toByteArray(Charsets.UTF_8)
    }

    fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readFully(input, header, 1, 3)
        val size = (header[0].toInt() and 0xff) or
            ((header[1].toInt() and 0xff) shl 8) or
            ((header[2].toInt() and 0xff) shl 16) or
            ((header[3].toInt() and 0xff) shl 24)
        require(size in 1..MAX_MESSAGE_BYTES) { "INVALID_MESSAGE_SIZE" }
        return ByteArray(size).also { readFully(input, it, 0, size) }
    }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size in 1..MAX_MESSAGE_BYTES) { "INVALID_MESSAGE_SIZE" }
        val size = payload.size
        output.write(byteArrayOf(size.toByte(), (size ushr 8).toByte(), (size ushr 16).toByte(), (size ushr 24).toByte()))
        output.write(payload)
        output.flush()
    }

    fun metadataFor(origin: HttpsOrigin, passwords: List<PasswordEntry>): List<CredentialMetadata> =
        passwords.asSequence()
            .filter { it.id.length in 1..256 && websiteMatches(origin.asciiHost, it.websiteDomain) }
            .map { CredentialMetadata(it.id, it.title.ifBlank { origin.asciiHost }.take(MAX_METADATA_LABEL_CHARS), it.username.take(MAX_METADATA_USERNAME_CHARS)) }
            .distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .take(MAX_CREDENTIAL_RESULTS)
            .toList()

    fun parseHttpsOrigin(raw: String): HttpsOrigin {
        require(raw.length <= 2048) { "INVALID_ORIGIN" }
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("INVALID_ORIGIN") }
        require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) { "ORIGIN_REQUIRED" }
        val host = uri.host?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("INVALID_ORIGIN")
        val asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("INVALID_ORIGIN")
        val localHttp = scheme == "http" && (asciiHost == "127.0.0.1" || asciiHost == "localhost")
        require((scheme == "https" || localHttp) && uri.rawUserInfo == null) { "HTTPS_REQUIRED" }
        val port = uri.port.takeIf { it >= 0 }
        require(port == null || port in 1..65535) { "INVALID_ORIGIN" }
        val defaultPort = if (scheme == "https") 443 else 80
        val normalized = "$scheme://$asciiHost" + if (port != null && port != defaultPort) ":$port" else ""
        return HttpsOrigin(normalized, asciiHost, port)
    }

    private fun websiteMatches(originHost: String, website: String): Boolean {
        val candidate = website.trim()
        if (candidate.isEmpty()) return false
        val host = runCatching {
            val uri = URI(if (candidate.contains("://")) candidate else "https://$candidate")
            val rawHost = uri.host ?: uri.rawAuthority
                ?.takeIf { '@' !in it && '[' !in it && it.count { char -> char == ':' } <= 1 }
                ?.substringBefore(':')
            rawHost?.trimEnd('.')?.let { IDN.toASCII(it, IDN.USE_STD3_ASCII_RULES).lowercase() }
        }.getOrNull() ?: return false
        if (host == originHost) return true
        if (!originHost.endsWith(".$host")) return false
        return runCatching {
            val domain = InternetDomainName.from(host)
            domain.hasPublicSuffix() && !domain.isPublicSuffix
        }.getOrDefault(false)
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("INVALID_REQUEST")

    private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.takeIf { it.isString }?.content
    private fun JsonObject.optionalInt(name: String): Int? = this[name]?.jsonPrimitive?.content?.toIntOrNull()
    private fun JsonObject.optionalToken(): String? = this["token"]?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.length in 16..256 }
    private fun JsonObject.requiredToken(): String = optionalToken() ?: throw IllegalArgumentException("INVALID_TOKEN")
    private fun JsonObject.optionalNativeCaller(): String? = this["nativeCaller"]?.jsonPrimitive?.takeIf { it.isString }?.content
        ?.takeIf { it.length in 1..256 }

    private fun JsonObject.requireOnly(vararg names: String) {
        require(keys.all(names.toSet()::contains)) { "UNKNOWN_FIELD" }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var position = offset
        while (position < offset + length) {
            val read = input.read(buffer, position, offset + length - position)
            if (read < 0) throw EOFException("Truncated native-messaging frame")
            position += read
        }
    }
}

/**
 * Deliberately exposes metadata only. Password release is a separate future flow that must require
 * an explicit desktop-side selection/authorization and must never be added to findCredentials.
 */
class NativeMessagingService(
    private val isUnlocked: () -> Boolean,
    private val passwords: () -> List<PasswordEntry>,
    private val requestDesktopUnlock: () -> Boolean = { false },
    private val saveCredential: (NativeMessagingProtocol.Request.SaveCredential) -> Boolean = { false },
    // Keep authorization last: Kotlin callers use a trailing lambda for it.
    private val authorize: (NativeMessagingProtocol.HttpsOrigin, PasswordEntry) -> Boolean = { _, _ -> false },
) {
    private val passwordRandom = SecureRandom()

    fun handle(payload: ByteArray): ByteArray {
        val response = try {
            when (val request = NativeMessagingProtocol.parseRequest(payload)) {
                is NativeMessagingProtocol.Request.Register,
                is NativeMessagingProtocol.Request.Heartbeat -> NativeMessagingProtocol.Response(request.id, ok = false, error = "DENIED")
                is NativeMessagingProtocol.Request.Status -> NativeMessagingProtocol.Response(
                    id = request.id,
                    ok = true,
                    state = if (isUnlocked()) "unlocked" else "locked",
                )
                is NativeMessagingProtocol.Request.Unlock -> {
                    if (requestDesktopUnlock()) NativeMessagingProtocol.Response(request.id, ok = true, state = "unlocked")
                    else NativeMessagingProtocol.Response(request.id, ok = false, state = "locked", error = "DENIED")
                }
                is NativeMessagingProtocol.Request.FindCredentials ->
                    if (!isUnlocked()) NativeMessagingProtocol.Response(request.id, ok = false, state = "locked", error = "LOCKED")
                    else NativeMessagingProtocol.Response(
                        request.id,
                        ok = true,
                        state = "unlocked",
                        credentials = NativeMessagingProtocol.metadataFor(request.origin, passwords()),
                    )
                is NativeMessagingProtocol.Request.RequestCredential -> {
                    if (!isUnlocked()) {
                        NativeMessagingProtocol.Response(request.id, ok = false, state = "locked", error = "LOCKED")
                    } else {
                        val entry = passwords().firstOrNull { it.id == request.credentialId &&
                            NativeMessagingProtocol.metadataFor(request.origin, listOf(it)).isNotEmpty() }
                        if (entry == null || !authorize(request.origin, entry) || !isUnlocked()) {
                            NativeMessagingProtocol.Response(request.id, ok = false, error = "DENIED")
                        } else {
                            // Re-read after the user approves: an entry may have been edited, deleted, or
                            // moved to another site while the desktop confirmation was visible.
                            val current = passwords().firstOrNull { it.id == request.credentialId &&
                                NativeMessagingProtocol.metadataFor(request.origin, listOf(it)).isNotEmpty() }
                            if (current == null) NativeMessagingProtocol.Response(request.id, ok = false, error = "DENIED")
                            else NativeMessagingProtocol.Response(
                                request.id, ok = true, state = "unlocked",
                                credential = NativeMessagingProtocol.CredentialFill(current.username, current.password),
                            )
                        }
                    }
                }
                is NativeMessagingProtocol.Request.SaveCredential -> {
                    if (!isUnlocked()) NativeMessagingProtocol.Response(request.id, ok = false, state = "locked", error = "LOCKED")
                    else if (saveCredential(request)) NativeMessagingProtocol.Response(request.id, ok = true, state = "unlocked")
                    else NativeMessagingProtocol.Response(request.id, ok = false, error = "DENIED")
                }
                is NativeMessagingProtocol.Request.GeneratePassword -> NativeMessagingProtocol.Response(
                    request.id,
                    ok = true,
                    state = if (isUnlocked()) "unlocked" else "locked",
                    generatedPassword = generatePassword(request.length, request.mode),
                )
            }
        } catch (_: Exception) {
            NativeMessagingProtocol.Response(bestEffortId(payload), ok = false, error = "INVALID_REQUEST")
        }
        val encoded = NativeMessagingProtocol.encodeResponse(response)
        if (encoded.size <= NativeMessagingProtocol.MAX_MESSAGE_BYTES) return encoded
        encoded.fill(0)
        return NativeMessagingProtocol.encodeResponse(
            NativeMessagingProtocol.Response(response.id.take(128), ok = false, error = "RESPONSE_TOO_LARGE")
        )
    }

    private fun bestEffortId(payload: ByteArray): String = runCatching {
        Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject["id"]?.jsonPrimitive?.content
            ?.take(128).orEmpty()
    }.getOrDefault("")

    private fun generatePassword(length: Int, mode: String): String {
        val lower = if (mode == "pin") "" else "abcdefghijkmnpqrstuvwxyz"
        val upper = if (mode == "pin") "" else "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val digits = "23456789"
        val symbols = if (mode == "recommended") "!@#$%^&*" else ""
        val groups = listOf(lower, upper, digits, symbols).filter { it.isNotEmpty() }
        val pool = groups.joinToString("")
        if (pool.isEmpty()) return ""
        val size = if (mode == "pin") length.coerceIn(4, 8) else length.coerceIn(4, 32)
        val chars = mutableListOf<Char>()
        groups.forEach { if (chars.size < size) chars += it[passwordRandom.nextInt(it.length)] }
        while (chars.size < size) chars += pool[passwordRandom.nextInt(pool.length)]
        for (i in chars.lastIndex downTo 1) {
            val j = passwordRandom.nextInt(i + 1)
            val temp = chars[i]
            chars[i] = chars[j]
            chars[j] = temp
        }
        return chars.joinToString("")
    }
}
