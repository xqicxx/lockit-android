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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.R
import com.lockit.domain.CodingPlanQuota
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.TacticalRed

@Composable
internal fun QwenCompactRow(
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
        // ── Main row: label | 5h gauge | Wk gauge | (Mo gauge) | badge | arrow ──
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
                CompactGauge("5h", quota.sessionUsed, quota.sessionTotal, Modifier.weight(1f))
                CompactGauge("Wk", quota.weekUsed, quota.weekTotal, Modifier.weight(1f))
                if (quota.monthTotal > 0) {
                    CompactGauge("Mo", quota.monthUsed, quota.monthTotal, Modifier.weight(1f))
                }
                val badge = quota.tier.takeIf { it.isNotBlank() }
                    ?: quota.status.takeIf { it.isNotBlank() }
                    ?: quota.planName.takeIf { it.isNotBlank() }
                    ?: "—"
                StatusChip(
                    text = badge.uppercase().take(8),
                    color = when {
                        quota.status.equals("VALID", true) || quota.status.equals("ACTIVE", true) -> IndustrialOrange
                        quota.status.equals("EXPIRED", true) || quota.status.equals("EXHAUSTED", true) -> TacticalRed
                        else -> colorScheme.onSurfaceVariant
                    },
                )
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
            val parts = qwenCompactMetaParts(quota, state.cacheAgeMinutes)
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

@Composable
private fun qwenCompactMetaParts(quota: CodingPlanQuota, cacheAgeMinutes: Int): List<String> {
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
    if (quota.remainingDays > 0) add("remaining", stringResource(R.string.repos_quota_remaining, quota.remainingDays))
    quota.subscriptionExpiresAt?.let { add("expires", formatResetTime(it)) }
    add("charge", quota.chargeType.uppercase())
    if (quota.chargeAmount > 0.0) add("cost", "¥${quota.chargeAmount}")
    if (quota.creditsRemaining > 0.0) add("credits", "${quota.creditsRemaining} ${quota.creditsCurrency}")
    if (quota.extraUsageSpent > 0.0 || quota.extraUsageLimit > 0.0)
        add("extra_usage", "${quota.extraUsageSpent}/${quota.extraUsageLimit}")
    if (quota.autoRenewFlag) add("renew", stringResource(R.string.repos_quota_auto_renew))
    quota.sessionResetsAt?.let { add("session_reset", formatResetTime(it)) }
    quota.weekResetsAt?.let { add("week_reset", formatResetTime(it)) }
    quota.monthResetsAt?.let { add("month_reset", formatResetTime(it)) }
    add("login", quota.loginMethod.uppercase())
    add("account", quota.accountEmail)
    if (cacheAgeMinutes > 0) add("cache", "${cacheAgeMinutes}m ago")
    quota.extraDetails.forEach { (key, value) -> add("extra:${key.lowercase()}", value) }

    return items.values.take(4)
}

// ── Expanded Detail (unchanged) ──

@Composable
internal fun QwenCodingPlanContent(quota: CodingPlanQuota) {
    val remainingDays = quota.remainingDays.takeIf { it > 0 }

    val infoItems = buildList {
        if (quota.planName.isNotBlank()) add(stringResource(R.string.repos_quota_plan) to quota.planName)
        if (quota.instanceName.isNotBlank()) add("INSTANCE" to quota.instanceName)
        if (quota.instanceType.isNotBlank()) add("TYPE" to quota.instanceType)
        remainingDays?.let { add(stringResource(R.string.repos_quota_remaining_label) to stringResource(R.string.repos_quota_days_value, it)) }
        if (quota.chargeAmount > 0.0) add(stringResource(R.string.repos_quota_cost_label) to "¥${quota.chargeAmount}")
        if (quota.chargeType.isNotBlank()) add("CHARGE" to quota.chargeType.uppercase())
        if (quota.accountEmail.isNotBlank()) add(stringResource(R.string.repos_quota_account) to quota.accountEmail)
        if (quota.loginMethod.isNotBlank()) add(stringResource(R.string.repos_quota_login_method) to quota.loginMethod)
        if (quota.creditsRemaining > 0.0)
            add(stringResource(R.string.repos_quota_credits) to "${quota.creditsRemaining} ${quota.creditsCurrency}")
    }

    InfoGrid(items = infoItems)

    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (quota.autoRenewFlag) StatusChip(text = stringResource(R.string.repos_quota_auto_renew), color = IndustrialOrange)
        if (quota.chargeType.isNotBlank()) StatusChip(text = quota.chargeType.uppercase(), color = IndustrialOrange)
        if (quota.status.isNotBlank()) {
            val active = quota.status.equals("VALID", true) || quota.status.equals("ACTIVE", true)
            StatusChip(text = quota.status.uppercase(), color = if (active) IndustrialOrange else TacticalRed, filled = active)
        }
    }

    // Bailian has 5h + week + month windows, show usage counts
    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, null, true, false, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, null, true, false, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, null, true, false, Modifier.weight(1f))
    }
}
