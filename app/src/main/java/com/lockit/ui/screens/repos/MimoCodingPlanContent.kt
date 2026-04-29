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

@Composable
internal fun MimoCodingPlanContent(quota: CodingPlanQuota) {
    val infoItems = buildList {
        if (quota.planName.isNotBlank()) add("PLAN" to quota.planName)
        if (quota.instanceType.isNotBlank()) add("TYPE" to quota.instanceType.uppercase())
        if (quota.loginMethod.isNotBlank()) add("AUTH" to quota.loginMethod.uppercase())
        if (quota.status.isNotBlank()) add("STATUS" to quota.status.uppercase())
        add("RPM" to "100")
        add("TPM" to "10M")
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
