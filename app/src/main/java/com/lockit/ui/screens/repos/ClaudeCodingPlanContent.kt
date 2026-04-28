package com.lockit.ui.screens.repos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lockit.R
import com.lockit.domain.CodingPlanQuota

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
