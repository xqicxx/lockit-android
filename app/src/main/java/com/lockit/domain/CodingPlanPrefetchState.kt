package com.lockit.domain

/**
 * Global singleton to store prefetched coding plan quota.
 * This allows MainActivity to prefetch immediately on app startup,
 * and ReposScreen can access the result without refetching.
 */
object CodingPlanPrefetchState {
    var quota: CodingPlanQuota? = null
    var error: String? = null
    var isLoading: Boolean = false
    var hasPrefetched: Boolean = false
}