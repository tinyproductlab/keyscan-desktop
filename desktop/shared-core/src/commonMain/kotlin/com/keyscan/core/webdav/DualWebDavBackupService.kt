package com.keyscan.core.webdav

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class WebDavTarget(val id: String, val client: WebDavOperations)
data class WebDavTargetResult(val targetId: String, val latestUploaded: Boolean, val historyUploaded: Boolean, val error: String? = null)
data class DualWebDavResult(val targets: List<WebDavTargetResult>) {
    val successCount: Int get() = targets.count { it.latestUploaded && it.historyUploaded }
}

class DualWebDavBackupService(private val historyLimit: Int = 10) {
    init { require(historyLimit > 0) }

    /** Each configured target is isolated: failure on one never prevents upload to the other. */
    fun upload(backup: Path, targets: List<WebDavTarget>, prefix: String = "filebackup", now: Instant = Instant.now()): DualWebDavResult {
        require(Files.isRegularFile(backup)) { "Backup source is not a file" }
        val safePrefix = sanitizePrefix(prefix)
        val latest = "/${safePrefix}_latest.dat"
        val history = "/${safePrefix}_${REMOTE_TIME.format(now)}.dat"
        return DualWebDavResult(targets.distinctBy { it.id }.map { target ->
            try {
                val latestOk = target.client.upload(latest, backup)
                val historyOk = latestOk && target.client.upload(history, backup)
                if (latestOk && historyOk) prune(target.client)
                WebDavTargetResult(target.id, latestOk, historyOk, if (latestOk && historyOk) null else "WebDAV backup upload failed")
            } catch (error: Exception) {
                WebDavTargetResult(target.id, false, false, error.message?.take(160) ?: "WebDAV backup upload failed")
            }
        })
    }

    private fun prune(client: WebDavOperations) {
        client.listBackupFiles().filter { HISTORY.matches(it.name) }.sortedByDescending { it.name }
            .drop(historyLimit).forEach { runCatching { client.delete(it.path) } }
    }

    companion object {
        private val REMOTE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
        private val HISTORY = Regex("[A-Za-z0-9_-]{1,32}_[0-9]{8}_[0-9]{6}\\.dat")
        fun sanitizePrefix(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9_-]"), "").ifBlank { "filebackup" }.take(32)
    }
}
