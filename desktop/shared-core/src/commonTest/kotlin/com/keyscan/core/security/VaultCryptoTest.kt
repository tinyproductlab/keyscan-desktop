package com.keyscan.core.security

import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultCryptoTest {
    private fun deterministicRandom() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3, 4)) }

    @Test fun envelopeRoundTripAndLegacyRead() {
        val databaseKey = "database-key-value"
        val envelope = VaultCrypto.wrapDatabaseKey(databaseKey, "root-key", deterministicRandom())
        assertTrue(envelope.startsWith(VaultCrypto.ENVELOPE_PREFIX))
        assertEquals(databaseKey, VaultCrypto.unwrapDatabaseKey(envelope, "root-key"))
        assertEquals(databaseKey, VaultCrypto.unwrapDatabaseKey(envelope.removePrefix(VaultCrypto.ENVELOPE_PREFIX), "root-key"))
    }

    @Test fun wrongKeyAndTamperingFailAuthentication() {
        val envelope = VaultCrypto.wrapDatabaseKey("database-key-value", "root-key", deterministicRandom())
        assertFailsWith<VaultAuthenticationException> { VaultCrypto.unwrapDatabaseKey(envelope, "wrong-root") }
        val replacement = if (envelope.last() == 'A') 'B' else 'A'
        assertFailsWith<VaultAuthenticationException> { VaultCrypto.unwrapDatabaseKey(envelope.dropLast(1) + replacement, "root-key") }
    }

    @Test fun bootstrapNeverPersistsPinOrDataProtectionKey() {
        val dir = Files.createTempDirectory("keyscan-bootstrap-test")
        val file = dir.resolve("vault.properties")
        val store = VaultBootstrapStore(file)
        assertFalse(store.isConfigured())
        store.create("001234", "Secret Key With Spaces").close()
        assertTrue(store.isConfigured())
        val diskText = Files.readString(file)
        assertFalse(diskText.contains("001234"))
        assertFalse(diskText.contains("Secret Key With Spaces"))
        store.unlock("001234", "Secret Key With Spaces").close()
        assertFailsWith<VaultAuthenticationException> { store.unlock("001235", "Secret Key With Spaces") }
    }

    @Test fun unsupportedOrMissingBootstrapVersionFailsAuthentication() {
        val dir = Files.createTempDirectory("keyscan-bootstrap-version-test")
        val file = dir.resolve("vault.properties")
        val store = VaultBootstrapStore(file)

        Files.writeString(file, "formatVersion=2\ndatabaseKeyEnvelope=unused\n")
        assertFailsWith<VaultAuthenticationException> {
            store.unlock("001234", "Secret Key With Spaces")
        }

        Files.writeString(file, "databaseKeyEnvelope=unused\n")
        assertFailsWith<VaultAuthenticationException> {
            store.unlock("001234", "Secret Key With Spaces")
        }
    }

    @Test fun `changing data protection key preserves database key and rejects old key`() {
        val file = Files.createTempDirectory("keyscan-bootstrap-change-key-test").resolve("vault.properties")
        val store = VaultBootstrapStore(file)
        store.create("1234", "ABCD-EFGH-2345-6789").close()

        store.changeDataProtectionKey("1234", "ABCD-EFGH-2345-6789", "WXYZ-2345-6789-ABCD")

        assertFailsWith<VaultAuthenticationException> { store.unlock("1234", "ABCD-EFGH-2345-6789") }
        store.unlock("1234", "WXYZ-2345-6789-ABCD").close()
        val disk = Files.readString(file)
        assertFalse(disk.contains("ABCD-EFGH-2345-6789"))
        assertFalse(disk.contains("WXYZ-2345-6789-ABCD"))
    }

    @Test fun corruptedOrIncompleteBootstrapFailsAuthentication() {
        val dir = Files.createTempDirectory("keyscan-bootstrap-corrupt-test")
        val file = dir.resolve("vault.properties")
        val store = VaultBootstrapStore(file)

        Files.writeString(file, "formatVersion=1\ndatabaseKeyEnvelope=not-an-envelope\n")
        assertFailsWith<VaultAuthenticationException> {
            store.unlock("001234", "Secret Key With Spaces")
        }

        Files.writeString(file, "formatVersion=1\n")
        assertFailsWith<VaultAuthenticationException> {
            store.unlock("001234", "Secret Key With Spaces")
        }

        Files.writeString(file, "formatVersion=1\ndatabaseKeyEnvelope=\\u12G4\n")
        assertFailsWith<VaultAuthenticationException> {
            store.unlock("001234", "Secret Key With Spaces")
        }
    }

    @Test fun vaultSessionZerosBorrowedCopiesAndRejectsUseAfterClose() {
        val session = VaultSession("database-key-value")
        lateinit var borrowed: CharArray
        session.useDatabaseKey { borrowed = it }
        assertTrue(borrowed.all { it == '\u0000' })

        session.close()
        assertFailsWith<IllegalStateException> {
            session.useDatabaseKey { }
        }
        session.close() // Closing from both an explicit lock and disposal remains safe.
    }
}
