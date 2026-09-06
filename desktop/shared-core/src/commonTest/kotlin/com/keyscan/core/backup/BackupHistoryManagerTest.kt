package com.keyscan.core.backup

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.security.KeyDerivation
import com.keyscan.core.security.VaultAuthenticationException
import com.keyscan.core.vault.EncryptedAttachmentStore
import com.keyscan.core.vault.EncryptedVaultStore
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.test.*

class BackupHistoryManagerTest {
    @Test fun createsListsAndDetectsTamperingBeforeRestore() {
        val root = Files.createTempDirectory("keyscan-history-test")
        val chars = Base64.getEncoder().encodeToString(ByteArray(32) { 5 }).toCharArray()
        val provider: ((CharArray) -> Unit) -> Unit = { block -> val copy = chars.copyOf(); try { block(copy) } finally { copy.fill('\u0000') } }
        val vault = EncryptedVaultStore(root.resolve("vault.ksdb"), provider); vault.savePassword(PasswordEntry("p", "Mail", "example.com", "u", "secret"))
        val service = LocalBackupService(vault, EncryptedAttachmentStore(root.resolve("attachments"), provider), provider)
        val manager = BackupHistoryManager(service, root.resolve("history")); val rootKey = KeyDerivation.deriveRootKey("123456", "Data Key")
        val created = manager.create(rootKey, Instant.parse("2026-07-30T12:34:56Z"))
        assertEquals(BackupIntegrity.VERIFIED, created.integrity); assertEquals(created.path, manager.list().single().path)
        val bytes = Files.readAllBytes(created.path); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); Files.write(created.path, bytes)
        assertEquals(BackupIntegrity.CORRUPTED, manager.verify(created.path).integrity)
        assertFailsWith<VaultAuthenticationException> { manager.restoreReplace(created.path, rootKey) }
        assertEquals("p", vault.listPasswords().single().id)
    }

    @Test fun backupHistoryDoesNotFollowFileSymlinksOutsideManagedDirectory() {
        val root = Files.createTempDirectory("keyscan-history-link-test")
        val history = Files.createDirectories(root.resolve("history"))
        val outside = root.resolve("outside.ksb").also { Files.writeString(it, "outside") }
        val link = history.resolve("KeyScan-linked.ksb")
        if (runCatching { Files.createSymbolicLink(link, outside) }.isFailure) return
        val chars = Base64.getEncoder().encodeToString(ByteArray(32) { 5 }).toCharArray()
        val provider: ((CharArray) -> Unit) -> Unit = { block -> val copy = chars.copyOf(); try { block(copy) } finally { copy.fill('\u0000') } }
        val vault = EncryptedVaultStore(root.resolve("vault.ksdb"), provider)
        val manager = BackupHistoryManager(LocalBackupService(vault, EncryptedAttachmentStore(root.resolve("attachments"), provider), provider), history)
        assertTrue(manager.list().isEmpty())
        assertFailsWith<IllegalArgumentException> { manager.verify(link) }
        assertFailsWith<IllegalArgumentException> { manager.restoreReplace(link, "root-key") }
    }
}
