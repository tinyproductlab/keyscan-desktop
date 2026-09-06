package com.keyscan.desktop

import com.keyscan.core.security.VaultAuthenticationException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsHelloQuickUnlockStoreTest {
    @Test fun signatureDerivedEnvelopeUnlocksAndWrongSignatureFails() {
        val file = Files.createTempDirectory("keyscan-hello-store").resolve("hello.properties")
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(byteArrayOf(1, 2, 3, 4)) }
        val store = WindowsHelloQuickUnlockStore(file, random)
        val enrollment = store.newEnrollment(); val databaseKey = "database-key-material".toCharArray()
        val signature = ByteArray(256) { (it * 7).toByte() }
        store.enable(enrollment, databaseKey, signature)
        assertTrue(store.isEnabled()); assertTrue(store.enrollment().challenge.contentEquals(enrollment.challenge))
        store.unlock(signature).use { session -> session.useDatabaseKey { assertTrue(it.contentEquals(databaseKey)) } }
        assertFails { store.unlock(signature.copyOf().also { it[0]++ }) }
        assertFalse(Files.readString(file).contains("database-key-material"))
        store.disable(); assertFalse(store.isEnabled())
        databaseKey.fill('\u0000'); signature.fill(0); enrollment.challenge.fill(0)
    }

    @Test fun enrollmentRejectsUnsupportedFormatVersion() {
        val file = newConfigurationFile(
            "formatVersion" to "2",
            "keyName" to WindowsHelloQuickUnlockStore.KEY_NAME,
        )

        val error = assertFailsWith<VaultAuthenticationException> {
            WindowsHelloQuickUnlockStore(file).enrollment()
        }

        assertEquals("Unsupported Windows Hello quick-unlock configuration", error.message)
    }

    @Test fun enrollmentRejectsUnexpectedKeyName() {
        val file = newConfigurationFile(
            "formatVersion" to "1",
            "keyName" to "KeyScan.Desktop.UntrustedKey",
        )

        val error = assertFailsWith<VaultAuthenticationException> {
            WindowsHelloQuickUnlockStore(file).enrollment()
        }

        assertEquals("Unsupported Windows Hello quick-unlock configuration", error.message)
    }

    @Test fun enrollmentWrapsDamagedPropertiesAsAuthenticationFailure() {
        val file = Files.createTempDirectory("keyscan-hello-corrupt").resolve("hello.properties")
        Files.writeString(file, "formatVersion=1\nkeyName=KeyScan.Desktop.QuickUnlock.v1\nchallenge=\\u12G4\n")

        val error = assertFailsWith<VaultAuthenticationException> {
            WindowsHelloQuickUnlockStore(file).enrollment()
        }

        assertEquals("Windows Hello quick-unlock configuration could not be read", error.message)
        assertTrue(error.cause is IllegalArgumentException)
    }

    private fun newConfigurationFile(vararg values: Pair<String, String>) =
        Files.createTempDirectory("keyscan-hello-invalid").resolve("hello.properties").also { file ->
            val properties = Properties().apply { values.forEach { (name, value) -> setProperty(name, value) } }
            Files.newOutputStream(file).use { properties.store(it, "test") }
        }
}
