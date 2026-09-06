package com.keyscan.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsHelloClientTest {
    @Test fun parsesOnlyExactHelperRecords() {
        assertEquals(WindowsHelloAvailability.AVAILABLE, WindowsHelloClient.parseAvailability("AVAILABILITY:Available"))
        assertEquals(WindowsHelloAvailability.NOT_CONFIGURED, WindowsHelloClient.parseAvailability("AVAILABILITY:NotConfiguredForUser"))
        assertEquals(WindowsHelloAvailability.ERROR, WindowsHelloClient.parseAvailability("Available"))
        assertEquals(WindowsHelloVerification.VERIFIED, WindowsHelloClient.parseVerification("VERIFICATION:Verified"))
        assertEquals(WindowsHelloVerification.CANCELED, WindowsHelloClient.parseVerification("VERIFICATION:Canceled"))
        assertEquals(WindowsHelloVerification.ERROR, WindowsHelloClient.parseVerification("VERIFIED"))
    }
}
