package com.lockit.domain.model

import com.lockit.domain.CodingPlanProviders

/**
 * Which fields each coding plan provider needs.
 * Extensible: add a new entry for each new provider, no form code changes needed.
 */
object CodingPlanProviderFields {

    /** Fields visible per provider. Indexes match [CodingPlanFields]. */
    private val providerFieldMap: Map<String, Set<Int>> = mapOf(
        CodingPlanProviders.QWEN_BAILIAN to setOf(
            CodingPlanFields.PROVIDER,
            CodingPlanFields.RAW_CURL,
            CodingPlanFields.COOKIE,
            CodingPlanFields.BASE_URL,
        ),
        CodingPlanProviders.CHATGPT to setOf(
            CodingPlanFields.PROVIDER,
            CodingPlanFields.RAW_CURL,
            CodingPlanFields.API_KEY,
            CodingPlanFields.BASE_URL,
        ),
        CodingPlanProviders.CLAUDE to setOf(
            CodingPlanFields.PROVIDER,
            CodingPlanFields.RAW_CURL,
            CodingPlanFields.API_KEY,
            CodingPlanFields.BASE_URL,
        ),
        CodingPlanProviders.MIMO to setOf(
            CodingPlanFields.PROVIDER,
            CodingPlanFields.API_KEY,
            CodingPlanFields.BASE_URL,
        ),
        CodingPlanProviders.DEEPSEEK to setOf(
            CodingPlanFields.PROVIDER,
            CodingPlanFields.API_KEY,
            CodingPlanFields.BASE_URL,
        ),
    )

    fun visibleFieldIndices(provider: String): Set<Int> =
        providerFieldMap[CodingPlanProviders.normalize(provider)]
            ?: providerFieldMap.values.first() // fallback

    fun isFieldVisible(provider: String, fieldIndex: Int): Boolean =
        fieldIndex in visibleFieldIndices(provider)
}
