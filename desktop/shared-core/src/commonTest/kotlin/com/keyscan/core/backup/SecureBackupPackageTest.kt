package com.keyscan.core.backup

import com.keyscan.core.security.KeyDerivation
import com.keyscan.core.security.VaultAuthenticationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.*

class SecureBackupPackageTest {
    private val rootKey = KeyDerivation.deriveRootKey("001234", "Data Key")
    private val databaseKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test fun androidZipLayoutRoundTripsPayloadAndAttachments() {
        val attachment = BackupVaultAttachment("a-1", "v-1", "证件.txt", "text/plain", 6, "hash")
        val payload = BackupPayloadV5(vaultAttachments = listOf(attachment), vaultItems = listOf(BackupVaultItem("v-1", title = "证件")))
        val output = ByteArrayOutputStream()
        SecureBackupPackage.writeV6(output, payload, rootKey, databaseKey) { _, stream -> stream.write("秘密内容".toByteArray()) }
        var restoredContent = ""
        val restored = SecureBackupPackage.readV6(ByteArrayInputStream(output.toByteArray()), rootKey) { _, stream -> restoredContent = stream.readBytes().toString(Charsets.UTF_8) }
        assertEquals("秘密内容", restoredContent); assertEquals("attachments/a-1.bin", restored.vaultAttachments.single().contentReference); assertEquals(5, restored.version)
    }

    @Test fun wrongRootKeyAndTamperingFail() {
        val output = ByteArrayOutputStream(); SecureBackupPackage.writeV6(output, BackupPayloadV5(), rootKey, databaseKey)
        val bytes = output.toByteArray()
        assertFailsWith<Exception> { SecureBackupPackage.readV6(ByteArrayInputStream(bytes), "wrong") }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertFailsWith<Exception> { SecureBackupPackage.readV6(ByteArrayInputStream(bytes), rootKey) }
    }
}
