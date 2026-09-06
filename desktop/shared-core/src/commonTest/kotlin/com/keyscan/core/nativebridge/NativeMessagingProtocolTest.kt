package com.keyscan.core.nativebridge

import com.keyscan.core.model.PasswordEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeMessagingProtocolTest {
    private val token = "test-pairing-token-0123456789"
    @Test fun framingUsesUnsignedLittleEndianLengthAndRoundTrips() {
        val payload = "{\"id\":\"one\",\"type\":\"status\"}".toByteArray()
        val output = ByteArrayOutputStream()
        NativeMessagingProtocol.writeFrame(output, payload)
        val framed = output.toByteArray()
        assertEquals(payload.size, framed[0].toInt() and 0xff)
        assertEquals(payload.toList(), NativeMessagingProtocol.readFrame(ByteArrayInputStream(framed))!!.toList())
    }

    @Test fun parsesOnlyStrictSupportedSchemas() {
        assertIs<NativeMessagingProtocol.Request.Status>(parse("{\"id\":\"1\",\"type\":\"status\"}"))
        assertIs<NativeMessagingProtocol.Request.Unlock>(parse("{\"id\":\"unlock\",\"type\":\"unlock\",\"token\":\"$token\"}"))
        assertIs<NativeMessagingProtocol.Request.Register>(parse("{\"id\":\"safari\",\"type\":\"register\",\"browser\":\"safari\",\"extensionId\":\"com.keyscan.safari.extension\",\"version\":\"1\"}"))
        val request = assertIs<NativeMessagingProtocol.Request.FindCredentials>(parse("{\"id\":\"2\",\"type\":\"findCredentials\",\"origin\":\"https://EXAMPLE.com:443\",\"token\":\"$token\"}"))
        assertEquals("https://example.com", request.origin.value)
        assertFailsWith<IllegalArgumentException> { parse("{\"id\":\"unlock\",\"type\":\"unlock\",\"pin\":\"1234\"}") }
        assertFailsWith<IllegalArgumentException> { parse("{\"id\":\"1\",\"type\":\"status\",\"extra\":true}") }
        assertFailsWith<IllegalArgumentException> { parse("{\"id\":\"1\",\"type\":\"unknown\"}") }
    }

    @Test fun nativeCallerCanOnlyBeAttachedByTheNativeHost() {
        val payload = "{\"id\":\"register\",\"type\":\"register\",\"browser\":\"chrome\",\"extensionId\":\"abc\",\"version\":\"1\"}".toByteArray()
        val attached = NativeMessagingProtocol.attachNativeCaller(payload, "chrome-extension://abc")
        val request = assertIs<NativeMessagingProtocol.Request.Register>(NativeMessagingProtocol.parseRequest(attached))
        assertEquals("chrome-extension://abc", request.nativeCaller)
        assertFailsWith<IllegalArgumentException> { NativeMessagingProtocol.attachNativeCaller(attached, "chrome-extension://other") }
    }

    @Test fun rejectsNonOriginsAndNonHttpsInput() {
        listOf(
            "http://example.com",
            "https://user@example.com",
            "https://example.com/path",
            "https://example.com?query=1",
            "javascript:alert(1)",
        ).forEach { origin ->
            assertFailsWith<IllegalArgumentException> {
                NativeMessagingProtocol.parseHttpsOrigin(origin)
            }
        }
    }

    @Test fun credentialLookupReturnsMetadataOnlyAndMatchesSubdomainsSafely() {
        val entries = listOf(
            PasswordEntry("1", "Primary", "https://example.com/login", "alice", "must-not-leak"),
            PasswordEntry("2", "Wrong suffix", "notexample.com", "mallory", "secret"),
            PasswordEntry("3", "Subdomain", "accounts.example.com", "bob", "secret"),
        )
        val rootMatches = NativeMessagingProtocol.metadataFor(NativeMessagingProtocol.parseHttpsOrigin("https://example.com"), entries)
        assertEquals(listOf("1"), rootMatches.map { it.id })
        val subdomainMatches = NativeMessagingProtocol.metadataFor(NativeMessagingProtocol.parseHttpsOrigin("https://accounts.example.com"), entries)
        assertEquals(setOf("1", "3"), subdomainMatches.map { it.id }.toSet())
        val encoded = NativeMessagingProtocol.encodeResponse(NativeMessagingProtocol.Response("x", true, credentials = subdomainMatches)).decodeToString()
        check("must-not-leak" !in encoded && "secret" !in encoded)
    }

    @Test fun publicSuffixesAndLookalikeDomainsNeverAuthorizeSubdomainCredentials() {
        val entries = listOf(
            PasswordEntry("tld", "TLD", "com", "a", "secret"),
            PasswordEntry("suffix", "Suffix", "co.uk", "b", "secret"),
            PasswordEntry("private-suffix", "Private suffix", "blogspot.com", "c", "secret"),
            PasswordEntry("valid", "Valid", "bücher.example", "d", "secret"),
            PasswordEntry("lookalike", "Lookalike", "example.com", "e", "secret"),
        )
        assertTrue(NativeMessagingProtocol.metadataFor(NativeMessagingProtocol.parseHttpsOrigin("https://attacker.com"), entries).isEmpty())
        assertTrue(NativeMessagingProtocol.metadataFor(NativeMessagingProtocol.parseHttpsOrigin("https://bank.co.uk"), entries).isEmpty())
        assertTrue(NativeMessagingProtocol.metadataFor(NativeMessagingProtocol.parseHttpsOrigin("https://victim.blogspot.com"), entries).isEmpty())
        assertEquals(listOf("valid"), NativeMessagingProtocol.metadataFor(NativeMessagingProtocol.parseHttpsOrigin("https://xn--bcher-kva.example"), entries).map { it.id })
        assertTrue(NativeMessagingProtocol.metadataFor(NativeMessagingProtocol.parseHttpsOrigin("https://example.com.attacker.test"), entries).isEmpty())
    }

    @Test fun oversizedAndTruncatedFramesAreRejected() {
        val oversizedHeader = byteArrayOf(1, 0, 1, 0)
        assertFailsWith<IllegalArgumentException> { NativeMessagingProtocol.readFrame(ByteArrayInputStream(oversizedHeader)) }
        assertFailsWith<java.io.EOFException> { NativeMessagingProtocol.readFrame(ByteArrayInputStream(byteArrayOf(5, 0, 0, 0, 1, 2))) }
    }

    @Test fun serviceNeverReadsVaultWhileLockedAndReturnsStableErrors() {
        var vaultRead = false
        val locked = NativeMessagingService(isUnlocked = { false }, passwords = { vaultRead = true; error("must not run") })
        val lockedResult = locked.handle("{\"id\":\"find-1\",\"type\":\"findCredentials\",\"origin\":\"https://example.com\",\"token\":\"$token\"}".toByteArray()).decodeToString()
        assertTrue("\"error\":\"LOCKED\"" in lockedResult)
        assertTrue(!vaultRead)

        val invalidResult = locked.handle("{\"id\":\"bad-1\",\"type\":\"status\",\"extra\":1}".toByteArray()).decodeToString()
        assertTrue("\"id\":\"bad-1\"" in invalidResult)
        assertTrue("\"error\":\"INVALID_REQUEST\"" in invalidResult)
    }

    @Test fun lockedBrowserUnlockRequestsDesktopInteractionWithoutReceivingAnySecret() {
        var requested = false
        val service = NativeMessagingService(
            isUnlocked = { false },
            passwords = { emptyList() },
            requestDesktopUnlock = { requested = true; true },
        )
        val response = service.handle("{\"id\":\"unlock\",\"type\":\"unlock\",\"token\":\"$token\"}".toByteArray()).decodeToString()
        assertTrue(requested)
        assertTrue("\"state\":\"unlocked\"" in response)
        assertTrue("pin" !in response.lowercase())
    }

    @Test fun credentialReleaseRequiresExactOriginEntryAndExplicitAuthorization() {
        val entry = PasswordEntry("mail", "Mail", "example.com", "alice", "one-time-secret")
        val request = "{\"id\":\"fill\",\"type\":\"requestCredential\",\"origin\":\"https://login.example.com\",\"credentialId\":\"mail\",\"token\":\"$token\"}".toByteArray()
        val denied = NativeMessagingService({ true }, { listOf(entry) })
        assertTrue("\"error\":\"DENIED\"" in denied.handle(request).decodeToString())
        val approved = NativeMessagingService({ true }, { listOf(entry) }) { origin, selected ->
            origin.asciiHost == "login.example.com" && selected.id == "mail"
        }
        val response = approved.handle(request).decodeToString()
        assertTrue("\"username\":\"alice\"" in response && "\"password\":\"one-time-secret\"" in response)
        val wrongSite = request.decodeToString().replace("login.example.com", "attacker.test").toByteArray()
        assertTrue("\"error\":\"DENIED\"" in approved.handle(wrongSite).decodeToString())

        var current = listOf(entry)
        val deletedDuringApproval = NativeMessagingService({ true }, { current }) { _, _ -> current = emptyList(); true }
        assertTrue("\"error\":\"DENIED\"" in deletedDuringApproval.handle(request).decodeToString())
    }

    @Test fun metadataAndCredentialResponsesAlwaysStayWithinFrameLimit() {
        val many = (1..200).map { index -> PasswordEntry(index.toString(), "L".repeat(500), "example.com", "U".repeat(1000), "p") }
        val origin = NativeMessagingProtocol.parseHttpsOrigin("https://example.com")
        val metadata = NativeMessagingProtocol.metadataFor(origin, many)
        assertEquals(50, metadata.size)
        assertTrue(metadata.all { it.label.length <= 128 && it.username.length <= 256 })
        val findService = NativeMessagingService({ true }, { many })
        val findResponse = findService.handle("{\"id\":\"many\",\"type\":\"findCredentials\",\"origin\":\"https://example.com\",\"token\":\"$token\"}".toByteArray())
        assertTrue(findResponse.size <= NativeMessagingProtocol.MAX_MESSAGE_BYTES)

        val huge = PasswordEntry("huge", "Huge", "example.com", "alice", "x".repeat(NativeMessagingProtocol.MAX_MESSAGE_BYTES))
        val fillService = NativeMessagingService({ true }, { listOf(huge) }) { _, _ -> true }
        val fillResponse = fillService.handle("{\"id\":\"fill\",\"type\":\"requestCredential\",\"origin\":\"https://example.com\",\"credentialId\":\"huge\",\"token\":\"$token\"}".toByteArray()).decodeToString()
        assertTrue("\"error\":\"RESPONSE_TOO_LARGE\"" in fillResponse)
    }

    private fun parse(value: String) = NativeMessagingProtocol.parseRequest(value.toByteArray())
}
