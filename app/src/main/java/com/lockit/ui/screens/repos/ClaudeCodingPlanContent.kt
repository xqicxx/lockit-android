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

@Composable
internal fun ClaudeCompactRow(
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
        // ── Main row: label | total credits | badge | arrow ──
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
                val badge = quota.instanceType.takeIf { it.isNotBlank() }
                    ?: quota.planName.takeIf { it.isNotBlank() }
                    ?: quota.loginMethod.takeIf { it.isNotBlank() }
                    ?: "ACTIVE"
                StatusChip(text = badge.take(8).uppercase(), color = IndustrialOrange)
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

        // ── Sub-row: values only, max 4, no labels ──
        if (quota != null && !state.isLoading) {
            val parts = claudeCompactMetaParts(quota, state.cacheAgeMinutes)
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
private fun claudeCompactMetaParts(quota: CodingPlanQuota, cacheAgeMinutes: Int): List<String> {
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

    if (quota.monthTotal > 0) {
        val mpct = (quota.monthUsed * 100L / quota.monthTotal).toInt()
        add("month_pct", "$mpct%")
        add("month_used", "${quota.monthUsed}/${quota.monthTotal}")
    }
    if (quota.sessionTotal > 0) {
        add("session_used", "${quota.sessionUsed}/${quota.sessionTotal}")
    }
    if (quota.remainingDays > 0) {
        add("remaining", stringResource(R.string.repos_quota_remaining, quota.remainingDays))
    }
    quota.subscriptionExpiresAt?.let { add("expires", formatResetTime(it)) }
    add("charge", quota.chargeType.uppercase())
    if (quota.chargeAmount > 0.0) add("cost", "$${quota.chargeAmount}")
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
internal fun ClaudeCodingPlanContent(quota: CodingPlanQuota) {
    val planLabel = quota.tier.ifBlank { quota.planName.ifBlank { quota.instanceName } }.ifBlank { null }

    val infoItems = buildList {
        planLabel?.let { add(stringResource(R.string.repos_quota_plan) to it) }
        if (quota.instanceName.isNotBlank()) add("INSTANCE" to quota.instanceName)
        if (quota.instanceType.isNotBlank()) add("TYPE" to quota.instanceType)
        if (quota.accountEmail.isNotBlank()) add(stringResource(R.string.repos_quota_account) to quota.accountEmail)
        if (quota.loginMethod.isNotBlank()) add(stringResource(R.string.repos_quota_login_method) to quota.loginMethod)
        if (quota.remainingDays > 0) add(stringResource(R.string.repos_quota_remaining_label) to stringResource(R.string.repos_quota_days_value, quota.remainingDays))
        if (quota.status.isNotBlank()) add(stringResource(R.string.repos_quota_status_label) to quota.status.uppercase())
        if (quota.chargeAmount > 0.0) add(stringResource(R.string.repos_quota_cost_label) to "$${quota.chargeAmount}")
        if (quota.chargeType.isNotBlank()) add("CHARGE" to quota.chargeType.uppercase())
        if (quota.autoRenewFlag) add(stringResource(R.string.repos_quota_renew_label) to stringResource(R.string.repos_quota_auto_renew))
        if (quota.creditsRemaining > 0.0) add(stringResource(R.string.repos_quota_credits) to "${quota.creditsRemaining} ${quota.creditsCurrency}")
        if (quota.extraUsageSpent > 0.0 || quota.extraUsageLimit > 0.0)
            add(stringResource(R.string.repos_quota_extra_usage) to "${quota.extraUsageSpent}/${quota.extraUsageLimit}")
    }

    InfoGrid(items = infoItems)

    // Claude has 5h + week + month windows, show usage + reset times
    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, quota.sessionResetsAt, true, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, quota.weekResetsAt, true, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, quota.monthResetsAt, true, true, Modifier.weight(1f))
    }
}
