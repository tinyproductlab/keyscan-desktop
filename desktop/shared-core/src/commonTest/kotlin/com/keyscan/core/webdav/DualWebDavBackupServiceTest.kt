package com.keyscan.core.webdav

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DualWebDavBackupServiceTest {
    @Test fun `uploads Android-compatible latest and history to both targets`() {
        val file = Files.createTempFile("backup", ".dat").also { Files.writeString(it, "encrypted") }
        val first = FakeClient(); val second = FakeClient()
        val result = DualWebDavBackupService().upload(file, listOf(WebDavTarget("main", first), WebDavTarget("backup", second)), "filebackup", Instant.parse("2026-07-30T02:03:04Z"))
        assertEquals(2, result.successCount)
        assertEquals(listOf("/filebackup_latest.dat", "/filebackup_20260730_020304.dat"), first.uploads)
        assertEquals(first.uploads, second.uploads)
    }

    @Test fun `one target failure does not block another`() {
        val file = Files.createTempFile("backup", ".dat")
        val failed = FakeClient(failUpload = true); val healthy = FakeClient()
        val result = DualWebDavBackupService().upload(file, listOf(WebDavTarget("main", failed), WebDavTarget("backup", healthy)))
        assertEquals(1, result.successCount); assertFalse(result.targets.first().latestUploaded); assertTrue(result.targets.last().latestUploaded)
    }

    @Test fun `path allowlist rejects traversal and arbitrary files`() {
        assertEquals("/filebackup_latest.dat", HttpWebDavClient.requireAllowedPath("filebackup_latest.dat"))
        listOf("../secret", "/x.txt", "/folder/filebackup_latest.dat").forEach { value ->
            assertTrue(runCatching { HttpWebDavClient.requireAllowedPath(value) }.isFailure)
        }
    }

    private class FakeClient(private val failUpload: Boolean = false) : WebDavOperations {
        val uploads = mutableListOf<String>()
        override fun testConnection() = WebDavConnectionResult(true, 1, 207)
        override fun upload(remotePath: String, source: Path): Boolean { if (failUpload) error("offline"); uploads += remotePath; return true }
        override fun download(remotePath: String, destination: Path) = false
        override fun delete(remotePath: String) = true
        override fun listBackupFiles() = emptyList<WebDavRemoteFile>()
    }
}
