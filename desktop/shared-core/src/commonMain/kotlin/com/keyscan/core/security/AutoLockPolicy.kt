package com.keyscan.core.security

import java.time.Duration

class AutoLockPolicy(private val timeout: Duration) {
    init { require(!timeout.isNegative && !timeout.isZero) }
    private var lastActivityNanos = 0L

    @Synchronized fun start(nowNanos: Long) { lastActivityNanos = nowNanos }
    @Synchronized fun recordActivity(nowNanos: Long) { if (nowNanos >= lastActivityNanos) lastActivityNanos = nowNanos }
    @Synchronized fun shouldLock(nowNanos: Long): Boolean = lastActivityNanos > 0 && nowNanos - lastActivityNanos >= timeout.toNanos()
}
