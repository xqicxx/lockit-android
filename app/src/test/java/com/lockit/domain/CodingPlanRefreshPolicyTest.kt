package com.lockit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingPlanRefreshPolicyTest {

    @Test
    fun `manual refresh is always allowed`() {
        val now = 60 * 60 * 1000L
        val lastFetchAt = now - 1_000L

        assertTrue(CodingPlanRefreshPolicy.shouldFetch(lastFetchAt, now, force = true))
    }

    @Test
    fun `auto refresh is blocked before thirty minutes`() {
        val now = 60 * 60 * 1000L
        val lastFetchAt = now - CodingPlanRefreshPolicy.AUTO_REFRESH_INTERVAL_MS + 1L

        assertFalse(CodingPlanRefreshPolicy.shouldFetch(lastFetchAt, now, force = false))
    }

    @Test
    fun `auto refresh is allowed at thirty minutes`() {
        val now = 60 * 60 * 1000L
        val lastFetchAt = now - CodingPlanRefreshPolicy.AUTO_REFRESH_INTERVAL_MS

        assertTrue(CodingPlanRefreshPolicy.shouldFetch(lastFetchAt, now, force = false))
    }

    @Test
    fun `missing timestamp is treated as stale`() {
        assertTrue(CodingPlanRefreshPolicy.shouldFetch(lastFetchAt = 0L, now = 1_000L, force = false))
    }

    @Test
    fun `cache age minutes is reusable`() {
        val now = 60 * 60 * 1000L
        val timestamp = now - 7 * 60 * 1000L

        assertEquals(7, CodingPlanRefreshPolicy.cacheAgeMinutes(timestamp, now))
    }
}
