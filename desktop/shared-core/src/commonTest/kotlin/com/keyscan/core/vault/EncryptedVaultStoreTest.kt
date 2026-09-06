package com.keyscan.core.vault

import com.keyscan.core.model.PasswordEntry
import com.keyscan.core.model.TotpEntry
import com.keyscan.core.model.SecureItem
import com.keyscan.core.model.VaultAttachment
import com.keyscan.core.security.VaultAuthenticationException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.*

class EncryptedVaultStoreTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }).toCharArray()
    private fun provider(value: CharArray = key): ((CharArray) -> Unit) -> Unit = { block -> block(value.copyOf()) }

    @Test fun passwordCrudSurvivesReopenWithoutPlaintextOnDisk() {
        val file = Files.createTempDirectory("keyscan-vault-test").resolve("vault.ksdb")
        val store = EncryptedVaultStore(file, provider(), SecureRandom())
        val entry = PasswordEntry("id-1", "Example account", "example.com", "alice@example.com", "NeverStorePlaintext!", "private note")
        store.savePassword(entry)
        assertEquals(entry, store.findPassword("id-1"))
        val disk = Files.readAllBytes(file)
        val diskText = disk.toString(Charsets.ISO_8859_1)
        assertFalse(diskText.contains(entry.username)); assertFalse(diskText.contains(entry.password)); assertFalse(diskText.contains(entry.notes))
        val reopened = EncryptedVaultStore(file, provider())
        assertEquals(listOf(entry), reopened.listPasswords())
        val updated = entry.copy(title = "Updated")
        reopened.savePassword(updated); assertEquals(updated, reopened.findPassword(entry.id))
        assertTrue(reopened.deletePassword(entry.id)); assertTrue(reopened.listPasswords().isEmpty())
    }

    @Test fun wrongKeyAndTamperingAreRejected() {
        val file = Files.createTempDirectory("keyscan-vault-auth-test").resolve("vault.ksdb")
        EncryptedVaultStore(file, provider()).savePassword(PasswordEntry("1", "A", "a.test", "u", "p"))
        val wrong = Base64.getEncoder().encodeToString(ByteArray(32) { 9 }).toCharArray()
        assertFailsWith<VaultAuthenticationException> { EncryptedVaultStore(file, provider(wrong)).listPasswords() }
        val bytes = Files.readAllBytes(file); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); Files.write(file, bytes)
        assertFailsWith<VaultAuthenticationException> { EncryptedVaultStore(file, provider()).listPasswords() }
    }

    @Test fun totpIsEncryptedAndPersists() {
        val file = Files.createTempDirectory("keyscan-totp-test").resolve("vault.ksdb")
        val store = EncryptedVaultStore(file, provider())
        val token = TotpEntry("otp-1", "alice@example.com", "Example", "JBSWY3DPEHPK3PXP", pinned = true)
        store.saveTotp(token)
        assertEquals(listOf(token), EncryptedVaultStore(file, provider()).listTotp())
        assertFalse(Files.readAllBytes(file).toString(Charsets.ISO_8859_1).contains(token.secret))
        assertTrue(store.deleteTotp(token.itemId)); assertTrue(store.listTotp().isEmpty())
    }

    @Test fun secureItemsAndAttachmentMetadataAreEncryptedAndCascadeDeleted() {
        val file = Files.createTempDirectory("keyscan-secure-item-test").resolve("vault.ksdb")
        val store = EncryptedVaultStore(file, provider())
        val item = SecureItem("item-1", "IDENTITY", "PERSONAL", "Passport", "{\"number\":\"P123456\"}", "private")
        val attachment = VaultAttachment("attachment-1", item.id, "passport.pdf", "application/pdf", "attachments/attachment-1.ksatt", "abc123", 42)
        store.saveSecureItem(item); store.saveAttachment(attachment)
        val reopened = EncryptedVaultStore(file, provider())
        assertEquals(listOf(item), reopened.listSecureItems()); assertEquals(listOf(attachment), reopened.listAttachments(item.id))
        val diskText = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
        assertFalse(diskText.contains("P123456")); assertFalse(diskText.contains("passport.pdf"))
        assertTrue(reopened.deleteSecureItem(item.id)); assertTrue(reopened.listAttachments(item.id).isEmpty())
    }

    @Test fun passwordChangesCreateEncryptedHistoryThatCanBeRestored() {
        val file = Files.createTempDirectory("keyscan-history-test").resolve("vault.ksdb"); val store = EncryptedVaultStore(file, provider())
        val original = PasswordEntry("p1", "Mail", "example.com", "alice", "old-secret")
        store.savePassword(original); store.savePassword(original.copy(password = "new-secret", updatedAt = original.updatedAt + 1))
        val history = store.listPasswordHistory(original.id).single(); assertEquals("old-secret", history.oldPassword); assertEquals("manual_edit", history.source)
        assertFalse(Files.readAllBytes(file).toString(Charsets.ISO_8859_1).contains("old-secret"))
        assertTrue(store.restorePasswordHistory(history.historyId)); assertEquals("old-secret", store.findPassword(original.id)?.password)
        assertEquals("new-secret", store.listPasswordHistory(original.id).first().oldPassword); assertEquals("restore", store.listPasswordHistory(original.id).first().source)
        assertTrue(store.deletePasswordHistory(history.historyId))
    }

    @Test fun deletedPasswordTotpAndVaultItemMoveToEncryptedTrashAndRestore() {
        val file = Files.createTempDirectory("keyscan-trash-test").resolve("vault.ksdb"); val store = EncryptedVaultStore(file, provider())
        val password = PasswordEntry("p", "Mail", "example.test", "alice", "secret")
        val token = TotpEntry("o", "alice", "Example", "JBSWY3DPEHPK3PXP")
        val item = SecureItem("v", title = "Passport"); val attachment = VaultAttachment("a", item.id, "passport.pdf", encryptedPath = "a.ksatt", hash = "hash", size = 1)
        store.replaceAll(EncryptedVaultStore.Snapshot(listOf(password), listOf(token), listOf(item), listOf(attachment)))
        assertTrue(store.deletePassword(password.id)); assertTrue(store.deleteTotp(token.id)); assertTrue(store.deleteSecureItem(item.id))
        val trash = store.listTrash(); assertEquals(3, trash.size)
        val disk = Files.readAllBytes(file).toString(Charsets.ISO_8859_1); assertFalse(disk.contains("secret")); assertFalse(disk.contains("passport.pdf"))
        trash.forEach { assertTrue(store.restoreTrash(it.id)) }
        assertEquals(password, store.findPassword(password.id)); assertEquals(token, store.listTotp().single()); assertEquals(item, store.listSecureItems().single()); assertEquals(attachment, store.listAttachments(item.id).single())
    }

    @Test fun trashRestoreRejectsConflictingLiveIdAndPermanentDeleteReturnsAttachmentMetadata() {
        val store = EncryptedVaultStore(Files.createTempDirectory("keyscan-trash-conflict").resolve("vault.ksdb"), provider())
        val original = PasswordEntry("p", "One", "one.test", "u", "a"); store.savePassword(original); store.deletePassword(original.id)
        store.savePassword(original.copy(title = "Replacement", password = "b")); val trashed = store.listTrash().single()
        assertFalse(store.restoreTrash(trashed.id)); assertEquals(trashed, store.permanentlyDeleteTrash(trashed.id)); assertTrue(store.listTrash().isEmpty())
    }

    @Test fun oversizedReplacementIsRejectedBeforeExistingVaultChanges() {
        val file = Files.createTempDirectory("keyscan-vault-limit").resolve("vault.ksdb")
        val store = EncryptedVaultStore(file, provider())
        val original = PasswordEntry("original", "Original", "example.com", "alice", "secret")
        store.savePassword(original)
        val oversized = List(100_001) { index -> PasswordEntry("id-$index", "", "", "", "") }
        assertFailsWith<IllegalArgumentException> {
            store.replaceAll(EncryptedVaultStore.Snapshot(oversized, emptyList(), emptyList(), emptyList()))
        }
        assertEquals(original, EncryptedVaultStore(file, provider()).listPasswords().single())
    }
}
