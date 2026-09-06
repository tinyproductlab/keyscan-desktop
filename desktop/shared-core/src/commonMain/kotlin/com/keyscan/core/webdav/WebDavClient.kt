package com.keyscan.core.webdav

import org.w3c.dom.Element
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class WebDavCredentials(val username: String, val password: CharArray)
data class WebDavRemoteFile(val path: String, val name: String, val size: Long, val lastModified: String?)
data class WebDavConnectionResult(
    val success: Boolean,
    val latencyMillis: Long,
    val statusCode: Int? = null,
    val redirectLocation: String? = null,
)

interface WebDavOperations {
    fun testConnection(): WebDavConnectionResult
    fun upload(remotePath: String, source: Path): Boolean
    fun download(remotePath: String, destination: Path): Boolean
    fun delete(remotePath: String): Boolean
    fun listBackupFiles(): List<WebDavRemoteFile>
}

/** Java 17 WebDAV transport matching Android's root-level backup paths and no-redirect policy. */
class HttpWebDavClient(
    baseUrl: String,
    credentials: WebDavCredentials,
    private val timeout: Duration = Duration.ofSeconds(30),
    client: HttpClient? = null,
    allowInsecureLoopbackForTests: Boolean = false,
) : WebDavOperations {
    private val root = validateBaseUrl(baseUrl, allowInsecureLoopbackForTests)
    private val authorization = "Basic " + Base64.getEncoder().encodeToString(
        // OkHttp Credentials.basic(), used by Android, defaults to ISO-8859-1.
        // Use the same byte representation so desktop and Android authenticate identically.
        (credentials.username.trim() + ":" + String(credentials.password)).toByteArray(StandardCharsets.ISO_8859_1)
    )
    private val http = client ?: HttpClient.newBuilder()
        .connectTimeout(timeout)
        // Android uses OkHttp over HTTP/1.1 for WebDAV.  Some WebDAV gateways
        // accept browser traffic but reject the HTTP/2 upgrade path, so keep
        // the desktop transport on the same conservative protocol.
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER).build()

    override fun testConnection(): WebDavConnectionResult {
        val started = System.nanoTime()
        val response = http.send(request("/", "PROPFIND").header("Depth", "0")
            .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY)).build(), HttpResponse.BodyHandlers.discarding())
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return WebDavConnectionResult(
            response.statusCode() in 200..299,
            elapsed,
            response.statusCode(),
            response.headers().firstValue("Location").orElse(null),
        )
    }

    override fun upload(remotePath: String, source: Path): Boolean {
        require(Files.isRegularFile(source)) { "Backup source is not a file" }
        val response = http.send(request(remotePath, "PUT").PUT(HttpRequest.BodyPublishers.ofFile(source)).build(), HttpResponse.BodyHandlers.discarding())
        return response.statusCode() in 200..299
    }

    override fun download(remotePath: String, destination: Path): Boolean {
        val safePath = requireAllowedPath(remotePath)
        val parent = destination.toAbsolutePath().normalize().parent ?: error("Download destination requires a parent")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "keyscan-webdav-", ".tmp")
        try {
            val response = http.send(request(safePath, "GET").GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) { response.body().close(); return false }
            response.body().use { input -> Files.newOutputStream(temporary).use(input::copyTo) }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: Exception) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
            return true
        } finally { Files.deleteIfExists(temporary) }
    }

    override fun delete(remotePath: String): Boolean {
        val response = http.send(request(remotePath, "DELETE").DELETE().build(), HttpResponse.BodyHandlers.discarding())
        return response.statusCode() in 200..299 || response.statusCode() == 404
    }

    override fun listBackupFiles(): List<WebDavRemoteFile> {
        val response = http.send(request("/", "PROPFIND").header("Depth", "1")
            .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY)).build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299 && response.statusCode() != 207) { response.body().close(); return emptyList() }
        return response.body().use(::parseListing)
    }

    private fun request(path: String, method: String): HttpRequest.Builder = HttpRequest.newBuilder(resolve(path))
        .timeout(timeout).header("Authorization", authorization).header("User-Agent", "KeyScan-Desktop/1")
        .header("Accept", "application/xml, */*").also { if (method == "PROPFIND") it.header("Content-Type", "application/xml; charset=utf-8") }

    private fun resolve(path: String): URI {
        if (path == "/") return URI.create(root.toString() + "/")
        return URI.create(root.toString() + requireAllowedPath(path))
    }

    private fun parseListing(input: InputStream): List<WebDavRemoteFile> {
        val documentBytes = input.readNBytes(MAX_LISTING_BYTES + 1)
        require(documentBytes.size <= MAX_LISTING_BYTES) { "WebDAV directory response is too large" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        return try {
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(documentBytes))
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            (0 until responses.length).mapNotNull { index ->
                val element = responses.item(index) as? Element ?: return@mapNotNull null
                val href = text(element, "href") ?: return@mapNotNull null
                val decoded = URLDecoder.decode(URI.create(href).path.substringAfterLast('/'), StandardCharsets.UTF_8)
                if (!ALLOWED_FILE.matches(decoded)) return@mapNotNull null
                WebDavRemoteFile("/$decoded", decoded, text(element, "getcontentlength")?.toLongOrNull() ?: 0, text(element, "getlastmodified"))
            }.distinctBy { it.path }
        } finally { documentBytes.fill(0) }
    }

    private fun text(element: Element, localName: String): String? =
        element.getElementsByTagNameNS("DAV:", localName).item(0)?.textContent?.trim()?.takeIf(String::isNotEmpty)

    companion object {
        private val ALLOWED_FILE = Regex("[A-Za-z0-9_-]{1,32}_(?:latest|[0-9]{8}_[0-9]{6})\\.dat")
        private const val MAX_LISTING_BYTES = 4 * 1024 * 1024
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        fun requireAllowedPath(value: String): String {
            val path = if (value.startsWith('/')) value else "/$value"
            require(path == "/secure_backup.dat" || ALLOWED_FILE.matches(path.removePrefix("/"))) { "Unsupported WebDAV backup path" }
            return path
        }

        private fun validateBaseUrl(value: String, allowInsecureLoopbackForTests: Boolean): URI {
            val cleaned = value.trim().trimEnd('/')
            val uri = URI.create(cleaned)
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Invalid WebDAV base URL" }
            val secure = uri.scheme.equals("https", true)
            val testLoopback = allowInsecureLoopbackForTests && uri.scheme.equals("http", true) &&
                (uri.host == "127.0.0.1" || uri.host == "[::1]" || uri.host == "::1")
            require(secure || testLoopback) { "WebDAV URL must use HTTPS" }
            return uri
        }
    }
}
