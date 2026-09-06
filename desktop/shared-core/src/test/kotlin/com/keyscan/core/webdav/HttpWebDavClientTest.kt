package com.keyscan.core.webdav

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpWebDavClientTest {
    private var server: HttpServer? = null

    @AfterTest fun stop() { server?.stop(0) }

    @Test fun `performs authenticated PROPFIND PUT GET and DELETE without redirects`() {
        val stored = mutableMapOf<String, ByteArray>(); val methods = mutableListOf<String>()
        val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("alice:pässword".toByteArray(StandardCharsets.ISO_8859_1))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/dav") { exchange ->
                methods += exchange.requestMethod
                if (exchange.requestHeaders.getFirst("Authorization") != expectedAuth) { exchange.sendResponseHeaders(401, -1); exchange.close(); return@createContext }
                val relative = exchange.requestURI.path.removePrefix("/dav")
                when (exchange.requestMethod) {
                    "PROPFIND" -> {
                        val body = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/filebackup_latest.dat</d:href><d:propstat><d:prop><d:getcontentlength>9</d:getcontentlength><d:getlastmodified>Thu, 30 Jul 2026 02:03:04 GMT</d:getlastmodified></d:prop></d:propstat></d:response></d:multistatus>""".toByteArray()
                        exchange.sendResponseHeaders(207, body.size.toLong()); exchange.responseBody.use { it.write(body) }
                    }
                    "PUT" -> { stored[relative] = exchange.requestBody.use { it.readBytes() }; exchange.sendResponseHeaders(201, -1) }
                    "GET" -> stored[relative]?.let { exchange.sendResponseHeaders(200, it.size.toLong()); exchange.responseBody.use { output -> output.write(it) } } ?: exchange.sendResponseHeaders(404, -1)
                    "DELETE" -> { stored.remove(relative); exchange.sendResponseHeaders(204, -1) }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
                exchange.close()
            }
            start()
        }
        val client = HttpWebDavClient("http://127.0.0.1:${server!!.address.port}/dav", WebDavCredentials("alice", "pässword".toCharArray()), allowInsecureLoopbackForTests = true)
        assertTrue(client.testConnection().success)
        assertEquals("filebackup_latest.dat", client.listBackupFiles().single().name)
        val source = Files.createTempFile("webdav-upload", ".dat").also { Files.writeString(it, "encrypted") }
        val target = Files.createTempFile("webdav-download", ".dat")
        assertTrue(client.upload("/filebackup_latest.dat", source)); assertTrue(client.download("/filebackup_latest.dat", target))
        assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(target)); assertTrue(client.delete("/filebackup_latest.dat"))
        assertEquals(listOf("PROPFIND", "PROPFIND", "PUT", "GET", "DELETE"), methods)
    }

    @Test fun `rejects insecure WebDAV URLs outside explicit loopback tests`() {
        listOf("http://webdav.example.com/dav", "http://127.0.0.1:8080/dav", "ftp://webdav.example.com/dav").forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) {
                HttpWebDavClient(url, WebDavCredentials("alice", "secret".toCharArray()))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            HttpWebDavClient("http://webdav.example.com/dav", WebDavCredentials("alice", "secret".toCharArray()), allowInsecureLoopbackForTests = true)
        }
    }

    @Test fun `rejects oversized WebDAV directory responses`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/dav") { exchange ->
                val body = ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() }
                exchange.sendResponseHeaders(207, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val client = HttpWebDavClient("http://127.0.0.1:${server!!.address.port}/dav", WebDavCredentials("alice", "secret".toCharArray()), allowInsecureLoopbackForTests = true)
        assertFailsWith<IllegalArgumentException> { client.listBackupFiles() }
    }
}
