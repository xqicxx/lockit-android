package com.lockit.ui.screens.repos

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lockit.domain.CodingPlanQuota
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.TacticalRed

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
