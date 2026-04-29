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

@Composable
internal fun DeepSeekCompactRow(
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
        // ── Main row: label | ¥ balance | currency chip | arrow ──
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
                Text("¥${"%.2f".format(quota.creditsRemaining)}",
                    fontFamily = JetBrainsMonoFamily, fontSize = 9.sp,
                    color = IndustrialOrange, modifier = Modifier.weight(1f))
                StatusChip(text = quota.creditsCurrency, color = IndustrialOrange)
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
            val parts = deepseekCompactMetaParts(quota, state.cacheAgeMinutes)
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

private fun deepseekCompactMetaParts(quota: CodingPlanQuota, cacheAgeMinutes: Int): List<String> {
    val items = linkedMapOf<String, String>()
    val seenValues = mutableSetOf<String>()

    fun add(key: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return
        val valueKey = clean.lowercase()
        if (items.containsKey(key) || !seenValues.add(valueKey)) return
        items[key] = clean
    }

    add("plan", quota.planName.ifBlank { quota.tier.ifBlank { quota.instanceType } })
    add("instance", quota.instanceName)
    if (quota.sessionTotal > 0) add("session_used", "${quota.sessionUsed}/${quota.sessionTotal}")
    if (quota.remainingDays > 0) add("remaining", "${quota.remainingDays} days")
    add("charge", quota.chargeType.uppercase())
    if (quota.creditsRemaining > 0.0) add("credits", "${quota.creditsRemaining} ${quota.creditsCurrency}")
    add("login", quota.loginMethod.uppercase())
    add("account", quota.accountEmail)
    if (cacheAgeMinutes > 0) add("cache", "${cacheAgeMinutes}m ago")
    quota.extraDetails.forEach { (key, value) -> add("extra:${key.lowercase()}", value) }

    return items.values.take(4)
}

// ── Expanded Detail (unchanged) ──

@Composable
internal fun DeepSeekCodingPlanContent(quota: CodingPlanQuota) {
    val infoItems = buildList {
        if (quota.creditsRemaining > 0.0) add("BALANCE" to "¥${"%.2f".format(quota.creditsRemaining)}")
        if (quota.loginMethod.isNotBlank()) add("AUTH" to quota.loginMethod.uppercase())
        if (quota.instanceType.isNotBlank()) add("TYPE" to quota.instanceType.uppercase())
        if (quota.status.isNotBlank()) add("STATUS" to quota.status.uppercase())
        add("RATE" to "Unlimited")
    }

    InfoGrid(items = infoItems)

    BoardDivider()
    Row(modifier = Modifier.fillMaxWidth()) {
        StatusChip(
            text = "ACTIVE",
            color = IndustrialOrange,
            filled = true,
        )
    }
}
