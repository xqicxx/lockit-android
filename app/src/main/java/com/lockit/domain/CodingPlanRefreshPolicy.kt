package com.lockit.domain

object CodingPlanRefreshPolicy {
    const val AUTO_REFRESH_INTERVAL_MS: Long = 30 * 60 * 1000L

    fun shouldFetch(
        lastFetchAt: Long,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): Boolean {
        if (force) return true
        if (lastFetchAt <= 0L) return true
        return now - lastFetchAt >= AUTO_REFRESH_INTERVAL_MS
    }

    fun cacheAgeMinutes(
        timestamp: Long,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (timestamp <= 0L || now <= timestamp) return 0
        return ((now - timestamp) / 60_000L).toInt()
    }
}
