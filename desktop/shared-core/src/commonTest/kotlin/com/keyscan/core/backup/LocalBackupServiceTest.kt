package com.keyscan.core.backup

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.model.SecureItem
import com.keyscan.core.security.KeyDerivation
import com.keyscan.core.vault.EncryptedAttachmentStore
import com.keyscan.core.vault.EncryptedVaultStore
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class LocalBackupServiceTest {
    @Test fun restoresAndroidProductionV6VectorIncludingAuthenticatedAttachment() {
        val root = Files.createTempDirectory("keyscan-android-v6-restore-test")
        val keyChars = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 9).toByte() }).toCharArray()
        val provider: ((CharArray) -> Unit) -> Unit = { block ->
            val copy = keyChars.copyOf(); try { block(copy) } finally { copy.fill('\u0000') }
        }
        val vault = EncryptedVaultStore(root.resolve("vault.ksdb"), provider)
        val attachmentStore = EncryptedAttachmentStore(root.resolve("attachments"), provider)
        val backup = root.resolve("android-v6-backup.ksb")
        checkNotNull(javaClass.classLoader.getResourceAsStream("backup/android-v6-backup.ksb")) { "Missing Android V6 golden backup" }.use {
            Files.write(backup, it.readBytes())
        }

        val result = LocalBackupService(vault, attachmentStore, provider).restoreReplaceV6(
            backup, KeyDerivation.deriveRootKey("001234", "Android Vector Data Key 2026!")
        )
        assertEquals(1, result.passwordCount); assertEquals(1, result.secureItemCount); assertEquals(1, result.attachmentCount)
        assertEquals("Android compatibility", vault.listPasswords().single().title)
        val restoredAttachment = vault.listAttachments("vault-android").single()
        assertEquals("android-note.txt", restoredAttachment.filename)
        val exported = root.resolve("android-note-exported.txt")
        attachmentStore.exportFile(restoredAttachment, exported)
        assertEquals("Android attachment bytes: 中文 · 한국어 · 日本語", Files.readString(exported))
    }

    @Test fun createsAuthenticatedAndroidLayoutBackupWithoutPlaintextStaging() {
        val root = Files.createTempDirectory("keyscan-local-backup-test")
        val keyChars = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }).toCharArray()
        val provider: ((CharArray) -> Unit) -> Unit = { block -> val copy = keyChars.copyOf(); try { block(copy) } finally { copy.fill('\u0000') } }
        val vault = EncryptedVaultStore(root.resolve("vault.ksdb"), provider)
        val attachmentStore = EncryptedAttachmentStore(root.resolve("attachments"), provider)
        val item = SecureItem("v1", title = "Passport", fieldsJson = "{\"number\":\"P123456\"}")
        val source = root.resolve("source.txt"); Files.writeString(source, "attachment secret")
        val attachment = attachmentStore.importFile(item.id, source, "text/plain")
        vault.replaceAll(EncryptedVaultStore.Snapshot(listOf(PasswordEntry("p1", "Mail", "example.com", "alice", "password secret")), emptyList(), listOf(item), listOf(attachment)))
        val service = LocalBackupService(vault, attachmentStore, provider); val backup = root.resolve("backup.ksb")
        val info = service.createV6(backup, KeyDerivation.deriveRootKey("001234", "Data Key"))
        assertTrue(info.size > 0); assertEquals(1, info.attachmentCount); assertEquals(64, info.sha256.length)
        val disk = Files.readAllBytes(backup).toString(Charsets.ISO_8859_1); assertFalse(disk.contains("password secret")); assertFalse(disk.contains("attachment secret"))
        val inspected = service.inspectV6(backup, KeyDerivation.deriveRootKey("001234", "Data Key"))
        assertEquals("p1", inspected.passwords.single().itemId); assertEquals("attachments/${attachment.id}.bin", inspected.vaultAttachments.single().contentReference)
    }

    @Test fun replaceRestoreReencryptsAttachmentsAndReplacesVault() {
        val root = Files.createTempDirectory("keyscan-restore-test"); val rootKey = KeyDerivation.deriveRootKey("123456", "Shared Data Key")
        fun provider(seed: Int): ((CharArray) -> Unit) -> Unit {
            val chars = Base64.getEncoder().encodeToString(ByteArray(32) { (it + seed).toByte() }).toCharArray()
            return { block -> val copy = chars.copyOf(); try { block(copy) } finally { copy.fill('\u0000') } }
        }
        val sourceProvider = provider(1); val sourceVault = EncryptedVaultStore(root.resolve("source/vault.ksdb"), sourceProvider); val sourceAttachments = EncryptedAttachmentStore(root.resolve("source/attachments"), sourceProvider)
        val item = SecureItem("v1", title = "Identity"); val plain = root.resolve("plain.txt"); Files.writeString(plain, "restored attachment")
        val sourceAttachment = sourceAttachments.importFile(item.id, plain, "text/plain")
        sourceVault.replaceAll(EncryptedVaultStore.Snapshot(listOf(PasswordEntry("new", "New", "new.test", "new-user", "new-secret")), emptyList(), listOf(item), listOf(sourceAttachment)))
        val backup = root.resolve("restore.ksb"); LocalBackupService(sourceVault, sourceAttachments, sourceProvider).createV6(backup, rootKey)

        val targetProvider = provider(9); val targetVault = EncryptedVaultStore(root.resolve("target/vault.ksdb"), targetProvider); val targetAttachments = EncryptedAttachmentStore(root.resolve("target/attachments"), targetProvider)
        targetVault.savePassword(PasswordEntry("old", "Old", "old.test", "old-user", "old-secret"))
        val result = LocalBackupService(targetVault, targetAttachments, targetProvider).restoreReplaceV6(backup, rootKey)
        assertEquals(1, result.passwordCount); assertEquals("new", targetVault.listPasswords().single().id); assertEquals(1, result.attachmentCount)
        val restoredMetadata = targetVault.listAttachments(item.id).single(); val exported = root.resolve("exported.txt"); targetAttachments.exportFile(restoredMetadata, exported)
        assertEquals("restored attachment", Files.readString(exported))
    }

    @Test fun failedRestoreLeavesExistingVaultUntouched() {
        val root = Files.createTempDirectory("keyscan-restore-rollback-test"); val provider: ((CharArray) -> Unit) -> Unit = run {
            val chars = Base64.getEncoder().encodeToString(ByteArray(32) { 4 }).toCharArray()
            val callback: ((CharArray) -> Unit) -> Unit = { block -> val copy = chars.copyOf(); try { block(copy) } finally { copy.fill('\u0000') } }
            callback
        }
        val vault = EncryptedVaultStore(root.resolve("vault.ksdb"), provider); val attachmentStore = EncryptedAttachmentStore(root.resolve("attachments"), provider)
        vault.savePassword(PasswordEntry("old", "Old", "old.test", "u", "p"))
        val corrupt = root.resolve("corrupt.ksb"); Files.writeString(corrupt, "not a backup")
        assertFails { LocalBackupService(vault, attachmentStore, provider).restoreReplaceV6(corrupt, "wrong") }
        assertEquals("old", vault.listPasswords().single().id)
    }

    @Test fun restoreRollsBackOldVaultAndAttachmentsWhenCommitValidationFails() {
        val root = Files.createTempDirectory("keyscan-restore-late-rollback-test")
        val keyChars = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }).toCharArray()
        val provider: ((CharArray) -> Unit) -> Unit = { block -> val copy = keyChars.copyOf(); try { block(copy) } finally { copy.fill('\u0000') } }
        val vault = EncryptedVaultStore(root.resolve("vault.ksdb"), provider)
        val attachmentStore = EncryptedAttachmentStore(root.resolve("attachments"), provider)
        val oldItem = SecureItem("old-item", title = "Old")
        val oldPlain = root.resolve("old.txt").also { Files.writeString(it, "old attachment") }
        val oldAttachment = attachmentStore.importFile(oldItem.id, oldPlain, "text/plain")
        val oldPassword = PasswordEntry("old-password", "Old", "old.test", "alice", "old-secret")
        vault.replaceAll(EncryptedVaultStore.Snapshot(listOf(oldPassword), emptyList(), listOf(oldItem), listOf(oldAttachment)))

        val newBytes = "new attachment".toByteArray()
        val newHash = MessageDigest.getInstance("SHA-256").digest(newBytes).joinToString("") { "%02x".format(it) }
        val invalidPayload = BackupPayloadV5(
            passwords = listOf(BackupPasswordEntry(itemId = "duplicate"), BackupPasswordEntry(itemId = "duplicate")),
            vaultItems = listOf(BackupVaultItem(id = "new-item", title = "New")),
            vaultAttachments = listOf(BackupVaultAttachment("new-attachment", "new-item", "new.txt", "text/plain", newBytes.size.toLong(), newHash)),
        )
        val rootKey = KeyDerivation.deriveRootKey("123456", "Shared Data Key")
        val backup = root.resolve("late-failure.ksb")
        Files.newOutputStream(backup).use { output ->
            SecureBackupPackage.writeV6(output, invalidPayload, rootKey, String(keyChars)) { _, attachmentOutput -> attachmentOutput.write(newBytes) }
        }

        assertFailsWith<IllegalArgumentException> { LocalBackupService(vault, attachmentStore, provider).restoreReplaceV6(backup, rootKey) }
        assertEquals(oldPassword, vault.listPasswords().single())
        assertEquals(oldItem, vault.listSecureItems().single())
        assertEquals(oldAttachment, vault.listAttachments(oldItem.id).single())
        val restoredOld = root.resolve("restored-old.txt"); attachmentStore.exportFile(oldAttachment, restoredOld)
        assertEquals("old attachment", Files.readString(restoredOld))
        assertFalse(Files.exists(root.resolve("attachments/new-attachment.ksatt")))
        assertTrue(Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().startsWith("keyscan-restore-") } })
        newBytes.fill(0)
    }
}
