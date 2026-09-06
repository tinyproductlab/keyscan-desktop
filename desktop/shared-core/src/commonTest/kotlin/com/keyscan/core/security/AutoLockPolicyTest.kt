package com.keyscan.core.security

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoLockPolicyTest {
    @Test fun `locks only after inactivity threshold and activity resets timer`() {
        val policy = AutoLockPolicy(Duration.ofMinutes(5)); policy.start(1)
        assertFalse(policy.shouldLock(Duration.ofMinutes(4).toNanos()))
        policy.recordActivity(Duration.ofMinutes(4).toNanos())
        assertFalse(policy.shouldLock(Duration.ofMinutes(8).toNanos()))
        assertTrue(policy.shouldLock(Duration.ofMinutes(9).toNanos()))
    }
}
