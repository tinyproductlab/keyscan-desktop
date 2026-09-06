package com.keyscan.core.backup

import com.keyscan.core.security.VaultAuthenticationException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class BackupIntegrity { VERIFIED, CORRUPTED, UNCHECKED }
data class BackupHistoryEntry(val path: Path, val createdAt: Instant, val size: Long, val sha256: String?, val integrity: BackupIntegrity)

class BackupHistoryManager(private val service: LocalBackupService, private val directory: Path) {
    fun create(rootKey: String, now: Instant = Instant.now()): BackupHistoryEntry {
        Files.createDirectories(directory)
        val timestamp = FILE_TIME.format(now); var target = directory.resolve("KeyScan-$timestamp.ksb")
        if (Files.exists(target)) target = directory.resolve("KeyScan-$timestamp-${UUID.randomUUID().toString().take(8)}.ksb")
        val info = service.createV6(target, rootKey); writeChecksum(target, info.sha256)
        return BackupHistoryEntry(target, now, info.size, info.sha256, BackupIntegrity.VERIFIED)
    }

    fun list(): List<BackupHistoryEntry> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { files -> files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) && it.fileName.toString().startsWith("KeyScan-") && it.fileName.toString().endsWith(".ksb") }
            .map { path -> inspect(path) }.toList() }.sortedByDescending { it.createdAt }
    }

    fun verify(path: Path): BackupHistoryEntry = inspect(requireManagedBackup(path))

    fun restoreReplace(path: Path, rootKey: String): RestoreResult {
        val entry = inspect(requireManagedBackup(path))
        if (entry.integrity == BackupIntegrity.CORRUPTED) throw VaultAuthenticationException("Backup checksum does not match")
        return service.restoreReplaceV6(entry.path, rootKey)
    }

    /** Copies a downloaded cloud backup into managed history before it can be restored. */
    fun importDownloaded(source: Path, now: Instant = Instant.now()): BackupHistoryEntry {
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) { "Downloaded backup is not a regular file" }
        Files.createDirectories(directory)
        val target = directory.resolve("KeyScan-WebDAV-${FILE_TIME.format(now)}-${UUID.randomUUID().toString().take(8)}.ksb")
        val temporary = Files.createTempFile(directory, "keyscan-cloud-import-", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            moveAtomically(temporary, target)
            writeChecksum(target, sha256(target))
            return inspect(target)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun inspect(path: Path): BackupHistoryEntry {
        val expected = readChecksum(path); val actual = if (expected != null) sha256(path) else null
        val integrity = when { expected == null -> BackupIntegrity.UNCHECKED; expected.equals(actual, true) -> BackupIntegrity.VERIFIED; else -> BackupIntegrity.CORRUPTED }
        return BackupHistoryEntry(path, Files.getLastModifiedTime(path).toInstant(), Files.size(path), expected, integrity)
    }

    private fun requireManagedBackup(path: Path): Path {
        val value = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(value) && value.fileName.toString().endsWith(".ksb")) { "Backup is outside managed history" }
        val root = directory.toRealPath(); val realValue = value.toRealPath()
        require(realValue.parent == root) { "Backup is outside managed history" }
        return value
    }

    private fun checksumPath(backup: Path): Path = backup.resolveSibling(backup.fileName.toString() + ".sha256")
    private fun readChecksum(backup: Path): String? {
        val sidecar = checksumPath(backup); if (!Files.isRegularFile(sidecar)) return null
        val value = Files.readString(sidecar, StandardCharsets.US_ASCII).trim().lowercase()
        return value.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }
    private fun writeChecksum(backup: Path, hash: String) {
        val sidecar = checksumPath(backup); val temporary = Files.createTempFile(directory, "checksum-", ".tmp")
        try { Files.writeString(temporary, "$hash\n", StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING); moveAtomically(temporary, sidecar) }
        finally { Files.deleteIfExists(temporary) }
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

    companion object {
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        fun defaultDirectory(): Path = com.keyscan.core.vault.VaultBootstrapPath.baseDirectory().resolve("backups")
    }
}
