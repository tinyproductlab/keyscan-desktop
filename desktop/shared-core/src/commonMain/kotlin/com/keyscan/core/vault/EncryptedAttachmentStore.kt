package com.keyscan.core.vault

import com.keyscan.core.model.VaultAttachment
import com.keyscan.core.security.VaultAuthenticationException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.DigestOutputStream
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedAttachmentStore(
    private val directory: Path,
    private val databaseKeyProvider: ((CharArray) -> Unit) -> Unit,
    private val random: SecureRandom = SecureRandom()
) {
    internal fun managedDirectory(): Path = directory.toAbsolutePath().normalize()
    internal fun managedFile(attachment: VaultAttachment): Path = resolveManagedPath(attachment.encryptedPath)
    fun importFile(itemId: String, source: Path, mimeType: String = "application/octet-stream"): VaultAttachment {
        require(itemId.isNotBlank() && Files.isRegularFile(source)) { "Attachment source is invalid" }
        val id = UUID.randomUUID().toString(); val relative = "$id.ksatt"; Files.createDirectories(directory)
        val destination = resolveManagedPath(relative); val temporary = Files.createTempFile(directory, "attachment-", ".tmp")
        val iv = ByteArray(IV_BYTES).also(random::nextBytes); val key = databaseAesKey(); val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            BufferedOutputStream(Files.newOutputStream(temporary)).use { rawOutput ->
                rawOutput.write(MAGIC); rawOutput.write(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                CipherOutputStream(rawOutput, cipher).use { encryptedOutput ->
                    BufferedInputStream(Files.newInputStream(source)).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        try { while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read); encryptedOutput.write(buffer, 0, read); size += read } }
                        finally { buffer.fill(0) }
                    }
                }
            }
            moveAtomically(temporary, destination)
            return VaultAttachment(id, itemId, source.fileName.toString(), mimeType, relative, digest.digest().toHex(), size)
        } finally { key.encoded.fill(0); iv.fill(0); Files.deleteIfExists(temporary) }
    }

    fun exportFile(attachment: VaultAttachment, destination: Path) {
        PlaintextExportPolicy.requireOutsideManagedData(destination)
        val source = resolveManagedPath(attachment.encryptedPath)
        val temporaryParent = destination.toAbsolutePath().normalize().parent ?: throw IllegalArgumentException("Destination requires a parent")
        Files.createDirectories(temporaryParent); val temporary = Files.createTempFile(temporaryParent, "keyscan-export-", ".tmp")
        try {
            BufferedOutputStream(Files.newOutputStream(temporary)).use { exportTo(attachment, it) }
            moveAtomically(temporary, destination)
        } finally { Files.deleteIfExists(temporary) }
    }

    fun exportTo(attachment: VaultAttachment, output: OutputStream) {
        val source = resolveManagedPath(attachment.encryptedPath); val key = databaseAesKey()
        try {
            BufferedInputStream(Files.newInputStream(source)).use { rawInput ->
                val magic = rawInput.readNBytes(MAGIC.size); if (!magic.contentEquals(MAGIC)) throw VaultAuthenticationException("Invalid attachment container")
                val iv = rawInput.readNBytes(IV_BYTES); if (iv.size != IV_BYTES) throw VaultAuthenticationException("Truncated attachment container")
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                    val digest = MessageDigest.getInstance("SHA-256"); val digesting = DigestOutputStream(NonClosingOutputStream(output), digest)
                    CipherInputStream(rawInput, cipher).use { decrypted -> decrypted.copyTo(digesting) }; digesting.flush()
                    if (!digest.digest().toHex().equals(attachment.hash, ignoreCase = true)) throw VaultAuthenticationException("Attachment hash mismatch")
                } catch (error: Exception) {
                    if (error is VaultAuthenticationException) throw error
                    throw VaultAuthenticationException("Attachment authentication failed")
                } finally { iv.fill(0) }
            }
        } finally { key.encoded.fill(0) }
    }

    fun importFromBackup(metadata: VaultAttachment, input: InputStream): VaultAttachment {
        require(metadata.id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Unsafe attachment id" }
        require(metadata.size >= 0) { "Invalid attachment size" }
        val relative = "${metadata.id}.ksatt"; Files.createDirectories(directory); val destination = resolveManagedPath(relative)
        val temporary = Files.createTempFile(directory, "attachment-restore-", ".tmp"); val iv = ByteArray(IV_BYTES).also(random::nextBytes); val key = databaseAesKey(); val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            BufferedOutputStream(Files.newOutputStream(temporary)).use { rawOutput ->
                rawOutput.write(MAGIC); rawOutput.write(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                CipherOutputStream(rawOutput, cipher).use { encrypted ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    try { while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        if (size + read > metadata.size) throw VaultAuthenticationException("Restored attachment exceeds declared size")
                        digest.update(buffer, 0, read); encrypted.write(buffer, 0, read); size += read
                    } }
                    finally { buffer.fill(0) }
                }
            }
            val hash = digest.digest().toHex()
            if (size != metadata.size || !hash.equals(metadata.hash, true)) throw VaultAuthenticationException("Restored attachment integrity mismatch")
            moveAtomically(temporary, destination)
            return metadata.copy(encryptedPath = relative)
        } finally { key.encoded.fill(0); iv.fill(0); Files.deleteIfExists(temporary) }
    }

    fun deleteEncryptedFile(attachment: VaultAttachment): Boolean = Files.deleteIfExists(resolveManagedPath(attachment.encryptedPath))

    private fun resolveManagedPath(relative: String): Path {
        require(!Path.of(relative).isAbsolute) { "Absolute attachment paths are forbidden" }
        val root = directory.toAbsolutePath().normalize(); val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root) && resolved.parent == root) { "Attachment path escapes managed directory" }
        return resolved
    }

    private fun databaseAesKey(): SecretKeySpec {
        var result: ByteArray? = null
        databaseKeyProvider { chars ->
            val encoded = ByteArray(chars.size)
            try {
                chars.forEachIndexed { index, char -> require(char.code <= 0x7f); encoded[index] = char.code.toByte() }
                val decoded = Base64.getDecoder().decode(encoded); require(decoded.size == 32) { "Invalid database key" }; result = decoded
            } finally { encoded.fill(0) }
        }
        return SecretKeySpec(checkNotNull(result), "AES")
    }

    private fun moveAtomically(source: Path, destination: Path) {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = delegate.write(buffer, offset, length)
        override fun flush() = delegate.flush()
        override fun close() = flush()
    }

    companion object {
        private val MAGIC = byteArrayOf('K'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 0, 0)
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val BUFFER_SIZE = 64 * 1024
        fun defaultDirectory(): Path = VaultBootstrapPath.baseDirectory().resolve("attachments")
    }
}
