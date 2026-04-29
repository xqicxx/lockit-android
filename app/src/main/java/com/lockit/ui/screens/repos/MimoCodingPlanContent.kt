package com.lockit.ui.screens.repos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.domain.CodingPlanQuota
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.TacticalRed

private fun formatTokens(n: Int): String = when {
    n >= 1_000_000_000 -> "${n / 1_000_000_000}B"
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1_000 -> "${n / 1_000}K"
    else -> n.toString()
}

@Composable
internal fun MimoCompactRow(
    label: String,
    state: ProviderQuotaState,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val quota = state.quota

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        // ── Main row: label | total credits | API badge | arrow ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isExpanded) IndustrialOrange else colorScheme.onSurface,
                modifier = Modifier.width(64.dp),
            )

            if (state.isLoading) {
                Text("...", fontFamily = JetBrainsMonoFamily, fontSize = 9.sp,
                    color = colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            } else if (quota != null) {
                if (quota.monthTotal > 0) {
                    Text("${quota.monthTotal / 1_000_000}M total",
                        fontFamily = JetBrainsMonoFamily, fontSize = 9.sp,
                        color = IndustrialOrange, modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                StatusChip(text = "API", color = IndustrialOrange)
            } else {
                Text("—", fontFamily = JetBrainsMonoFamily, fontSize = 9.sp,
                    color = colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }

            Text(
                text = if (isExpanded) "▼" else "▶",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // ── Sub-row: values only, max 4 ──
        if (quota != null && !state.isLoading) {
            val parts = mimoCompactMetaParts(quota, state.cacheAgeMinutes)
            if (parts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    parts.forEach { part ->
                        Text(
                            text = part,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 7.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private fun mimoCompactMetaParts(quota: CodingPlanQuota, cacheAgeMinutes: Int): List<String> {
    val items = linkedMapOf<String, String>()
    val seenValues = mutableSetOf<String>()

    fun add(key: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return
        val valueKey = clean.lowercase()
        if (items.containsKey(key) || !seenValues.add(valueKey)) return
        items[key] = clean
    }

    // Mimo-specific: instanceType → planName → loginMethod
    val planVal = quota.instanceType.takeIf { it.isNotBlank() }
        ?: quota.planName.takeIf { it.isNotBlank() }
        ?: quota.loginMethod.takeIf { it.isNotBlank() }
    add("plan", planVal)
    // No instance name for Mimo

    if (quota.monthTotal > 0) {
        val mpct = (quota.monthUsed * 100L / quota.monthTotal).toInt()
        add("month_pct", "$mpct%")
        add("month_used", "${quota.monthUsed}/${quota.monthTotal}")
    }
    if (quota.sessionTotal > 0) add("session_used", "${quota.sessionUsed}/${quota.sessionTotal}")
    if (quota.remainingDays > 0) add("remaining", "${quota.remainingDays} days")
    add("charge", quota.chargeType.uppercase())
    add("login", quota.loginMethod.uppercase())
    add("account", quota.accountEmail)
    if (cacheAgeMinutes > 0) add("cache", "${cacheAgeMinutes}m ago")
    quota.extraDetails.forEach { (key, value) -> add("extra:${key.lowercase()}", value) }

    return items.values.take(4)
}

// ── Expanded Detail (unchanged) ──

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

    BoardDivider()
    Row(modifier = Modifier.fillMaxWidth()) {
        val active = quota.status.equals("ACTIVE", true) || quota.status.equals("VALID", true)
        StatusChip(
            text = if (active) "ACTIVE" else quota.status.uppercase().take(8),
            color = if (active) IndustrialOrange else TacticalRed,
            filled = active,
        )
    }
}
