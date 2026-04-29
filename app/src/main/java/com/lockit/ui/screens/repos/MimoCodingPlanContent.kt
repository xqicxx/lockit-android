package com.lockit.ui.screens.repos

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lockit.domain.CodingPlanQuota
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.TacticalRed

private fun formatTokens(n: Int): String = when {
    n >= 1_000_000_000 -> "${n / 1_000_000_000}B"
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1_000 -> "${n / 1_000}K"
    else -> n.toString()
}

@Composable
internal fun MimoCodingPlanContent(quota: CodingPlanQuota) {
    val infoItems = buildList {
        if (quota.sessionTotal > 0) {
            add("USED" to formatTokens(quota.sessionUsed))
            add("LIMIT" to formatTokens(quota.sessionTotal))
            val pct = if (quota.sessionTotal > 0) (quota.sessionUsed * 100L / quota.sessionTotal).toInt() else 0
            add("USAGE" to "$pct%")
        }
        if (quota.planName.isNotBlank()) add("PLAN" to quota.planName)
        if (quota.instanceType.isNotBlank()) add("TYPE" to quota.instanceType.uppercase())
        if (quota.loginMethod.isNotBlank()) add("AUTH" to quota.loginMethod.uppercase())
        add("RATE" to "100 rpm / 10M tpm")
    }

    InfoGrid(items = infoItems)

    Spacer(modifier = Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        val active = quota.status.equals("ACTIVE", true) || quota.status.equals("VALID", true)
        StatusChip(
            text = if (active) "ACTIVE" else quota.status.uppercase().take(8),
            color = if (active) IndustrialOrange else TacticalRed,
            filled = active,
        )
    }
}
