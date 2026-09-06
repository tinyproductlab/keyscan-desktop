package com.keyscan.desktop

import java.util.Locale

enum class DesktopPlatform {
    WINDOWS,
    MACOS,
    OTHER;

    val supportsWindowsHello: Boolean get() = this == WINDOWS
    val supportsWindowsNativeMessaging: Boolean get() = this == WINDOWS

    companion object {
        fun current(): DesktopPlatform = detect(System.getProperty("os.name", ""))

        internal fun detect(osName: String): DesktopPlatform = when {
            osName.lowercase(Locale.ROOT).startsWith("windows") -> WINDOWS
            osName.lowercase(Locale.ROOT).startsWith("mac") -> MACOS
            else -> OTHER
        }
    }
}
