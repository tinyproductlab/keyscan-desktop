package com.keyscan.core.backup

import com.keyscan.core.security.VaultAuthenticationException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object SecureBackupPackage {
    fun writeV6(
        destination: OutputStream,
        payload: BackupPayloadV5,
        rootKey: String,
        databaseKey: String,
        attachmentWriter: (BackupVaultAttachment, OutputStream) -> Unit = { _, _ -> }
    ) {
        val portableAttachments = payload.vaultAttachments.map { it.copy(contentReference = attachmentEntryName(it.id)) }
        val portablePayload = payload.copy(version = 5, vaultAttachments = portableAttachments)
        ZipOutputStream(SecureBackupCipher.encryptingV6(destination, rootKey, databaseKey)).use { zip ->
            zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY)); zip.write(BackupPayloadV5Codec.encode(portablePayload)); zip.closeEntry()
            portableAttachments.forEach { attachment ->
                zip.putNextEntry(ZipEntry(checkNotNull(attachment.contentReference))); attachmentWriter(attachment, zip); zip.closeEntry()
            }
        }
    }

    fun readV6(
        source: InputStream,
        rootKey: String,
        attachmentReader: (BackupVaultAttachment, InputStream) -> Unit = { _, input -> input.copyTo(OutputStream.nullOutputStream()) }
    ): BackupPayloadV5 {
        val decrypted = SecureBackupCipher.decryptingV6(source, rootKey)
        ZipInputStream(decrypted.stream).use { zip ->
            var payload: BackupPayloadV5? = null; var entries = 0; val seenAttachments = hashSetOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++; if (entries > MAX_ZIP_ENTRIES) throw VaultAuthenticationException("Backup has too many ZIP entries")
                val name = entry.name
                if (entry.isDirectory || name.contains('\\') || name.startsWith('/') || name.split('/').any { it == ".." }) throw VaultAuthenticationException("Unsafe backup ZIP entry")
                if (name == PAYLOAD_ENTRY) {
                    if (payload != null) throw VaultAuthenticationException("Duplicate backup payload")
                    payload = BackupPayloadV5Codec.decode(zip.readLimited(MAX_PAYLOAD_BYTES))
                } else {
                    val currentPayload = payload ?: throw VaultAuthenticationException("Backup payload must be the first ZIP entry")
                    val attachment = currentPayload.vaultAttachments.singleOrNull { it.contentReference == name }
                        ?: throw VaultAuthenticationException("Unexpected backup attachment entry")
                    if (!seenAttachments.add(name)) throw VaultAuthenticationException("Duplicate backup attachment entry")
                    val limited = LimitedInputStream(zip, MAX_ATTACHMENT_BYTES)
                    attachmentReader(attachment, limited)
                    val drain = ByteArray(8192)
                    try { while (limited.read(drain) != -1) { } } finally { drain.fill(0) }
                }
                zip.closeEntry()
            }
            val result = payload ?: throw VaultAuthenticationException("Backup payload is missing")
            val expected = result.vaultAttachments.mapNotNull { it.contentReference }.toSet()
            if (seenAttachments != expected) throw VaultAuthenticationException("Backup attachment content is missing")
            return result
        }
    }

    private fun attachmentEntryName(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Unsafe attachment id" }
        return "attachments/$id.bin"
    }

    private fun InputStream.readLimited(limit: Long): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0L
        while (true) { val read = read(buffer); if (read < 0) break; total += read; if (total > limit) throw VaultAuthenticationException("Backup payload is too large"); output.write(buffer, 0, read) }
        buffer.fill(0); return output.toByteArray()
    }

    private class LimitedInputStream(private val delegate: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L
        override fun read(): Int { val value = delegate.read(); if (value >= 0 && ++count > limit) throw VaultAuthenticationException("Attachment is too large"); return value }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length); if (read > 0 && (count + read).also { count = it } > limit) throw VaultAuthenticationException("Attachment is too large"); return read
        }
    }

    private const val PAYLOAD_ENTRY = "payload.json"
    private const val MAX_ZIP_ENTRIES = 100_002
    private const val MAX_PAYLOAD_BYTES = 256L * 1024 * 1024
    private const val MAX_ATTACHMENT_BYTES = 2L * 1024 * 1024 * 1024
}
