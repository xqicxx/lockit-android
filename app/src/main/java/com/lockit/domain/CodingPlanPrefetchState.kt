package com.lockit.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider singleton for cross-process coding plan quota state.
 *
 * Each provider (qwen_bailian, chatgpt, claude) has independent state slots,
 * eliminating cross-talk between providers during concurrent prefetch/fetch.
 *
 * Used by:
 * - MainActivity: writes prefetched data on app startup
 * - ReposViewModel: reads initial cache, writes fetch results
 * - CodingPlanWidget: reads cached quota (via CodingPlanPrefs, not directly)
 */
object CodingPlanPrefetchState {

    data class Snapshot(
        val isLoading: Boolean = false,
        val quota: CodingPlanQuota? = null,
        val error: String? = null,
        val cacheTimestamp: Long = 0L,
    )

    private class FlowSet {
        val isLoading = MutableStateFlow(false)
        val quota = MutableStateFlow<CodingPlanQuota?>(null)
        val error = MutableStateFlow<String?>(null)
        val cacheTimestamp = MutableStateFlow(0L)
    }

    private val flows = ConcurrentHashMap<String, FlowSet>()

    private fun forProvider(provider: String): FlowSet =
        flows.getOrPut(CodingPlanProviders.normalize(provider)) { FlowSet() }

    fun isLoading(provider: String): StateFlow<Boolean> =
        forProvider(provider).isLoading.asStateFlow()
    fun quota(provider: String): StateFlow<CodingPlanQuota?> =
        forProvider(provider).quota.asStateFlow()
    fun error(provider: String): StateFlow<String?> =
        forProvider(provider).error.asStateFlow()
    fun cacheTimestamp(provider: String): StateFlow<Long> =
        forProvider(provider).cacheTimestamp.asStateFlow()

    /** Synchronous snapshot — use for one-shot reads without collection overhead. */
    fun getSnapshot(provider: String): Snapshot {
        val f = flows[CodingPlanProviders.normalize(provider)] ?: return Snapshot()
        return Snapshot(
            isLoading = f.isLoading.value,
            quota = f.quota.value,
            error = f.error.value,
            cacheTimestamp = f.cacheTimestamp.value,
        )
    }

    // Global one-shot flag — prevents duplicate startup prefetch across process lifetime
    @Volatile
    var hasPrefetched: Boolean = false

    fun setLoading(provider: String, value: Boolean) {
        forProvider(provider).isLoading.value = value
    }

    fun setQuota(provider: String, value: CodingPlanQuota?) {
        forProvider(provider).quota.value = value
    }

    fun setError(provider: String, value: String?) {
        forProvider(provider).error.value = value
    }

    fun setCacheTimestamp(provider: String, value: Long) {
        forProvider(provider).cacheTimestamp.value = value
    }

    fun reset(provider: String) {
        val f = flows[CodingPlanProviders.normalize(provider)] ?: return
        f.isLoading.value = false
        f.quota.value = null
        f.error.value = null
        f.cacheTimestamp.value = 0L
    }

    fun resetAll() {
        flows.values.forEach { f ->
            f.isLoading.value = false
            f.quota.value = null
            f.error.value = null
            f.cacheTimestamp.value = 0L
        }
        hasPrefetched = false
    }
}
