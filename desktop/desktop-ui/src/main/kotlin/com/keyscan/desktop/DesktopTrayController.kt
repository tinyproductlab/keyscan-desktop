package com.keyscan.desktop

import java.awt.*
import java.awt.image.BufferedImage

class DesktopTrayController : AutoCloseable {
    private var icon: TrayIcon? = null

    fun install(onShow: () -> Unit, onLock: () -> Unit, onExit: () -> Unit): Boolean {
        if (!SystemTray.isSupported()) return false
        return runCatching {
            val popup = PopupMenu().apply {
                add(MenuItem("Show KeyScan").apply { addActionListener { onShow() } })
                add(MenuItem("Lock now").apply { addActionListener { onLock() } })
                addSeparator(); add(MenuItem("Exit").apply { addActionListener { onExit() } })
            }
            val trayIcon = TrayIcon(DesktopAppIcon.image ?: createFallbackIcon(), "KeyScan", popup).apply { isImageAutoSize = true; addActionListener { onShow() } }
            SystemTray.getSystemTray().add(trayIcon); icon = trayIcon; true
        }.getOrDefault(false)
    }

    override fun close() { icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }; icon = null }

    private fun createFallbackIcon(): Image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).also { image ->
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color(0x39, 0x79, 0xED); graphics.fillRoundRect(1, 1, 30, 30, 10, 10)
            graphics.color = Color.WHITE; graphics.fillOval(8, 7, 16, 16); graphics.fillRoundRect(14, 18, 4, 9, 3, 3)
        } finally { graphics.dispose() }
    }
}
