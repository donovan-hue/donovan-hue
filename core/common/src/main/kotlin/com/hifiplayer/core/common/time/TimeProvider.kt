package com.hifiplayer.core.common.time

/** Injectable clock: keeps scanners, caches and tests deterministic. */
interface TimeProvider {
    fun nowMs(): Long
    fun nowEpochSeconds(): Long = nowMs() / 1000L

    companion object {
        val System: TimeProvider = object : TimeProvider {
            override fun nowMs(): Long = java.lang.System.currentTimeMillis()
        }
    }
}
