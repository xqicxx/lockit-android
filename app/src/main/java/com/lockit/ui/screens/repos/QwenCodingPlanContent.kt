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
