package com.keyscan.core.backup

import com.keyscan.core.vault.EncryptedAttachmentStore
import com.keyscan.core.vault.EncryptedVaultStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import com.keyscan.core.model.VaultAttachment
import java.util.Comparator

data class BackupFileInfo(val path: Path, val size: Long, val sha256: String, val itemCount: Int, val attachmentCount: Int)
data class RestoreResult(val passwordCount: Int, val totpCount: Int, val secureItemCount: Int, val attachmentCount: Int)

class LocalBackupService(
    private val vault: EncryptedVaultStore,
    private val attachments: EncryptedAttachmentStore,
    private val databaseKeyProvider: ((CharArray) -> Unit) -> Unit
) {
    fun createV6(destination: Path, rootKey: String): BackupFileInfo {
        val snapshot = vault.snapshot()
        val domain = BackupDomainSnapshot(snapshot.passwords, snapshot.totp, snapshot.secureItems, snapshot.attachments, snapshot.passwordGroups)
        val payload = BackupPayloadMapper.toPayload(domain)
        val parent = destination.toAbsolutePath().normalize().parent ?: throw IllegalArgumentException("Backup destination requires a parent")
        Files.createDirectories(parent); val temporary = Files.createTempFile(parent, "keyscan-backup-", ".tmp")
        val databaseKey = readDatabaseKey()
        try {
            BufferedOutputStream(Files.newOutputStream(temporary)).use { output ->
                SecureBackupPackage.writeV6(output, payload, rootKey, databaseKey) { portable, zip ->
                    val local = snapshot.attachments.singleOrNull { it.id == portable.id }
                        ?: throw IllegalStateException("Attachment metadata is inconsistent")
                    attachments.exportTo(local, zip)
                }
            }
            moveAtomically(temporary, destination)
            return BackupFileInfo(destination, Files.size(destination), sha256(destination), snapshot.passwords.size + snapshot.totp.size + snapshot.secureItems.size, snapshot.attachments.size)
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Fully decrypts and authenticates the package, but never modifies the current vault. */
    fun inspectV6(source: Path, rootKey: String): BackupPayloadV5 = BufferedInputStream(Files.newInputStream(source)).use { input ->
        SecureBackupPackage.readV6(input, rootKey)
    }

    /** Replaces the current vault only after the complete backup and every attachment pass authentication. */
    fun restoreReplaceV6(source: Path, rootKey: String): RestoreResult {
        val liveDirectory = attachments.managedDirectory(); Files.createDirectories(liveDirectory)
        val parent = liveDirectory.parent ?: throw IllegalStateException("Attachment directory requires a parent")
        val stagingDirectory = Files.createTempDirectory(parent, "keyscan-restore-stage-")
        val rollbackDirectory = Files.createTempDirectory(parent, "keyscan-restore-rollback-")
        val stagingStore = EncryptedAttachmentStore(stagingDirectory, databaseKeyProvider)
        val imported = mutableListOf<VaultAttachment>(); val installedFiles = mutableListOf<Path>(); val oldSnapshot = vault.snapshot(); var vaultCommitted = false
        try {
            val payload = BufferedInputStream(Files.newInputStream(source)).use { input ->
                SecureBackupPackage.readV6(input, rootKey) { metadata, content ->
                    val localMetadata = VaultAttachment(metadata.id, metadata.itemId, metadata.filename, metadata.mimeType, "", metadata.hash, metadata.size)
                    imported += stagingStore.importFromBackup(localMetadata, content)
                }
            }
            val domain = BackupPayloadMapper.toDomain(payload)
            val expectedImported = payload.vaultAttachments.count { !it.contentReference.isNullOrBlank() }
            require(imported.size == expectedImported) { "Not all backup attachments were restored" }
            val newSnapshot = EncryptedVaultStore.Snapshot(domain.passwords, domain.otpTokens, domain.vaultItems, imported, domain.passwordGroups)

            oldSnapshot.attachments.forEach { old ->
                val oldFile = attachments.managedFile(old)
                if (Files.exists(oldFile)) moveAtomically(oldFile, rollbackDirectory.resolve(oldFile.fileName))
            }
            imported.forEach { restored ->
                val stagedFile = stagingStore.managedFile(restored)
                val installed = liveDirectory.resolve(stagedFile.fileName)
                moveAtomically(stagedFile, installed); installedFiles.add(installed)
            }
            vault.replaceAll(newSnapshot); vaultCommitted = true
            runCatching { deleteTree(rollbackDirectory) }; runCatching { deleteTree(stagingDirectory) }
            return RestoreResult(newSnapshot.passwords.size, newSnapshot.totp.size, newSnapshot.secureItems.size, newSnapshot.attachments.size)
        } catch (error: Exception) {
            installedFiles.forEach(Files::deleteIfExists)
            if (Files.exists(rollbackDirectory)) Files.list(rollbackDirectory).use { files -> files.forEach { moveAtomically(it, liveDirectory.resolve(it.fileName)) } }
            if (vaultCommitted) runCatching { vault.replaceAll(oldSnapshot) }
            deleteTree(stagingDirectory); deleteTree(rollbackDirectory)
            throw error
        }
    }

    private fun readDatabaseKey(): String {
        var result: String? = null
        databaseKeyProvider { chars -> result = String(chars) }
        return checkNotNull(result)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        try { Files.newInputStream(path).use { input -> while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) } } }
        finally { buffer.fill(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveAtomically(source: Path, destination: Path) {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
