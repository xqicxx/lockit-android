package com.lockit.ui.screens.repos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockit.R
import com.lockit.domain.CodingPlanQuota
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.TacticalRed

@Composable
internal fun ChatGptCodingPlanContent(quota: CodingPlanQuota) {
    val tier = quota.tier.ifBlank { quota.instanceType }.ifBlank { null }
    val remainingDays = quota.remainingDays.takeIf { it > 0 }
    val renewText = when {
        quota.autoRenewFlag -> stringResource(R.string.repos_quota_auto_renew)
        quota.chargeType.equals("subscription", ignoreCase = true) -> stringResource(R.string.repos_quota_manual_renew)
        quota.chargeType.isNotBlank() -> quota.chargeType.uppercase()
        else -> null
    }

    // Only add fields that have actual data (no "--" placeholders)
    val infoItems = buildList {
        tier?.let { add(stringResource(R.string.repos_quota_plan) to it) }
        if (quota.accountEmail.isNotBlank())
            add(stringResource(R.string.repos_quota_account) to quota.accountEmail)
        if (quota.loginMethod.isNotBlank())
            add(stringResource(R.string.repos_quota_login_method) to quota.loginMethod)
        remainingDays?.let { add(stringResource(R.string.repos_quota_remaining_label) to stringResource(R.string.repos_quota_days_value, it)) }
        renewText?.let { add(stringResource(R.string.repos_quota_renew_label) to it) }
        if (quota.chargeAmount > 0.0)
            add(stringResource(R.string.repos_quota_cost_label) to "$${quota.chargeAmount}")
        if (quota.chargeType.isNotBlank())
            add("CHARGE" to quota.chargeType.uppercase())
        if (quota.creditsRemaining > 0.0)
            add(stringResource(R.string.repos_quota_credits) to "${quota.creditsRemaining} ${quota.creditsCurrency}")
        if (quota.extraUsageSpent > 0.0 || quota.extraUsageLimit > 0.0)
            add(stringResource(R.string.repos_quota_extra_usage) to "${quota.extraUsageSpent}/${quota.extraUsageLimit}")
    }

    InfoGrid(items = infoItems)

    // Status chip — color driven by actual API status value, not hardcoded mapping
    if (quota.status.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val s = quota.status.uppercase()
            val isGood = s == "ACTIVE" || s == "VALID"
            StatusChip(text = s, color = if (isGood) IndustrialOrange else TacticalRed, filled = isGood)
        }
    }

    // ChatGPT only has 5h + weekly windows (no monthly quota)
    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, quota.sessionResetsAt, false, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, quota.weekResetsAt, false, true, Modifier.weight(1f))
    }
}
