package com.keyscan.core.vault

import com.keyscan.core.security.VaultAuthenticationException
import com.keyscan.core.model.VaultAttachment
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class EncryptedAttachmentStoreTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() }).toCharArray()
    private val provider: ((CharArray) -> Unit) -> Unit = { block -> val copy = key.copyOf(); try { block(copy) } finally { copy.fill('\u0000') } }

    @Test fun fileRoundTripHasNoPlaintextAndRejectsTampering() {
        val root = Files.createTempDirectory("keyscan-attachment-test"); val source = root.resolve("source.txt")
        Files.writeString(source, "highly sensitive attachment content")
        val store = EncryptedAttachmentStore(root.resolve("encrypted"), provider)
        val metadata = store.importFile("item-1", source, "text/plain")
        val encrypted = root.resolve("encrypted").resolve(metadata.encryptedPath)
        assertFalse(Files.readAllBytes(encrypted).toString(Charsets.ISO_8859_1).contains("sensitive attachment"))
        val output = root.resolve("restored.txt"); store.exportFile(metadata, output); assertEquals(Files.readString(source), Files.readString(output))
        val bytes = Files.readAllBytes(encrypted); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); Files.write(encrypted, bytes)
        assertFailsWith<VaultAuthenticationException> { store.exportFile(metadata, root.resolve("tampered.txt")) }
    }

    @Test fun pathTraversalIsRejected() {
        val root = Files.createTempDirectory("keyscan-attachment-path-test"); val store = EncryptedAttachmentStore(root, provider)
        val bad = com.keyscan.core.model.VaultAttachment("1", "i", "x", encryptedPath = "../outside", hash = "", size = 0)
        assertFailsWith<IllegalArgumentException> { store.deleteEncryptedFile(bad) }
    }

    @Test fun backupImportStopsBeforeWritingBeyondAuthenticatedSize() {
        val root = Files.createTempDirectory("keyscan-attachment-size-test")
        val store = EncryptedAttachmentStore(root, provider)
        val metadata = VaultAttachment("safe-id", "item", "x.txt", encryptedPath = "", hash = "00", size = 2)
        assertFailsWith<VaultAuthenticationException> {
            store.importFromBackup(metadata, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        assertFalse(Files.exists(root.resolve("safe-id.ksatt")))
        assertTrue(Files.list(root).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }
}
