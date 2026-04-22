package com.lockit.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global singleton to store prefetched coding plan quota.
 * Uses StateFlow for proper Compose observation (no polling needed).
 */
object CodingPlanPrefetchState {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _quota = MutableStateFlow<CodingPlanQuota?>(null)
    val quota: StateFlow<CodingPlanQuota?> = _quota.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var hasPrefetched: Boolean = false

    // Setter functions for updating state
    fun setLoading(value: Boolean) {
        _isLoading.value = value
    }

    fun setQuota(value: CodingPlanQuota?) {
        _quota.value = value
    }

    fun setError(value: String?) {
        _error.value = value
    }

    // Convenience function to reset all state
    fun reset() {
        _isLoading.value = false
        _quota.value = null
        _error.value = null
        hasPrefetched = false
    }
}