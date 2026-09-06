package com.keyscan.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlatformTest {
    @Test fun detectsSupportedDesktopFamiliesWithoutGuessingUnknownSystems() {
        assertEquals(DesktopPlatform.WINDOWS, DesktopPlatform.detect("Windows 11"))
        assertEquals(DesktopPlatform.MACOS, DesktopPlatform.detect("Mac OS X"))
        assertEquals(DesktopPlatform.OTHER, DesktopPlatform.detect("Linux"))
    }

    @Test fun windowsOnlyCapabilitiesStayDisabledOnMacOs() {
        assertTrue(DesktopPlatform.WINDOWS.supportsWindowsHello)
        assertTrue(DesktopPlatform.WINDOWS.supportsWindowsNativeMessaging)
        assertFalse(DesktopPlatform.MACOS.supportsWindowsHello)
        assertFalse(DesktopPlatform.MACOS.supportsWindowsNativeMessaging)
    }
}
