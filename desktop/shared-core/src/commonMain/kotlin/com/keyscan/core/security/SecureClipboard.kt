package com.keyscan.core.security

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface ClipboardAccess { fun readText(): String?; fun writeText(value: String) }

class SystemClipboardAccess : ClipboardAccess {
    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard
    override fun readText(): String? = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
    override fun writeText(value: String) { clipboard.setContents(StringSelection(value), null) }
}

class SecureClipboard(private val access: ClipboardAccess = SystemClipboardAccess()) : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "KeyScan-ClipboardClear").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    private var ownedDigest: ByteArray? = null

    @Synchronized fun copy(secret: String, clearAfterSeconds: Int) {
        require(clearAfterSeconds in 5..300)
        access.writeText(secret); ownedDigest?.fill(0); ownedDigest = digest(secret)
        pending?.cancel(false); pending = scheduler.schedule({ clearIfOwned() }, clearAfterSeconds.toLong(), TimeUnit.SECONDS)
    }

    @Synchronized fun clearIfOwned() {
        val expected = ownedDigest ?: return
        val current = access.readText() ?: return forget()
        val actual = digest(current)
        try { if (MessageDigest.isEqual(expected, actual)) access.writeText("") }
        finally { actual.fill(0); forget() }
    }

    @Synchronized private fun forget() { ownedDigest?.fill(0); ownedDigest = null; pending = null }
    override fun close() { synchronized(this) { pending?.cancel(false); clearIfOwned(); scheduler.shutdownNow() } }
    private fun digest(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("SHA-256").digest(bytes) }
        finally { bytes.fill(0) }
    }
}
