package com.lockit.widget

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.lockit.LockitApp
import com.lockit.domain.CodingPlanQuota
import com.lockit.domain.CodingPlanFetchers
import com.lockit.domain.model.CredentialType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Coding Plan Widget using Glance.
 * Displays quota gauges (5h/week/month) with refresh button.
 */
class CodingPlanWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as LockitApp
        val vaultManager = app.vaultManager
        val isUnlocked = vaultManager.isUnlocked()

        val quota = if (isUnlocked) {
            fetchFirstCodingPlanQuota(context)
        } else {
            null
        }

        provideContent {
            if (isUnlocked) {
                QuotaWidgetContent(quota)
            } else {
                LockedWidgetContent()
            }
        }
    }
}

@Composable
private fun LockedWidgetContent() {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.parseColor("#1A1A1A"))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "🔒 解锁以查看",
            style = TextStyle(
                color = ColorProvider(Color.parseColor("#B34700")),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun QuotaWidgetContent(quota: CodingPlanQuota?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.WHITE)
            .padding(12.dp)
    ) {
        // Header row: title + refresh button
        Row(
            modifier = GlanceModifier.fillMaxWidth()
        ) {
            Text(
                text = quota?.instanceName?.uppercase() ?: "CODING PLAN",
                style = TextStyle(
                    color = ColorProvider(Color.BLACK),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Box(
                modifier = GlanceModifier
                    .background(Color.parseColor("#A30000"))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .clickable(onRefreshAction)
            ) {
                Text(
                    text = "REFRESH",
                    style = TextStyle(
                        color = ColorProvider(Color.WHITE),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        if (quota != null) {
            // Status info
            Text(
                text = "剩余 ${quota.remainingDays} 天",
                style = TextStyle(
                    color = ColorProvider(Color.GRAY),
                    fontSize = 10.sp
                )
            )
            if (quota.autoRenewFlag) {
                Text(
                    text = "自动续费",
                    style = TextStyle(
                        color = ColorProvider(Color.parseColor("#B34700")),
                        fontSize = 9.sp
                    )
                )
            }

            // Quota gauges row
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                QuotaGaugeWidget("5h", quota.sessionUsed, quota.sessionTotal, GlanceModifier.defaultWeight())
                QuotaGaugeWidget("周", quota.weekUsed, quota.weekTotal, GlanceModifier.defaultWeight())
                QuotaGaugeWidget("月", quota.monthUsed, quota.monthTotal, GlanceModifier.defaultWeight())
            }
        } else {
            Text(
                text = "无 CodingPlan 凭据",
                style = TextStyle(
                    color = ColorProvider(Color.parseColor("#A30000")),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun QuotaGaugeWidget(label: String, used: Int, total: Int, modifier: GlanceModifier) {
    val pct = if (total > 0) (used.toLong() * 100 / total).coerceIn(0, 100).toInt() else 0
    val color = when {
        pct >= 90 -> Color.parseColor("#A30000")
        pct >= 70 -> Color.parseColor("#B34700")
        else -> Color.GRAY
    }

    Column(modifier = modifier) {
        Text(
            text = "$label $pct%",
            style = TextStyle(
                color = ColorProvider(color),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        )
        // Simple text bar representation
        val barFill = (pct / 10)
        Text(
            text = "█".repeat(barFill) + "░".repeat(10 - barFill),
            style = TextStyle(
                color = ColorProvider(color),
                fontSize = 8.sp
            )
        )
    }
}

private val onRefreshAction = actionRunCallback<RefreshWidgetCallback>()

/**
 * Refresh action callback for widget.
 */
class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        CodingPlanWidget().update(context, glanceId)
    }
}

/**
 * Fetch quota from first available CodingPlan credential.
 */
private suspend fun fetchFirstCodingPlanQuota(context: Context): CodingPlanQuota? {
    return withContext(Dispatchers.IO) {
        val app = context.applicationContext as LockitApp
        val vaultManager = app.vaultManager

        if (!vaultManager.isUnlocked()) return@withContext null

        try {
            val credentials = vaultManager.getAllCredentials().first()
            val codingPlanCreds = credentials.filter { it.type == CredentialType.CodingPlan }

            if (codingPlanCreds.isEmpty()) return@withContext null

            val cred = codingPlanCreds.first()
            val provider = cred.metadata["provider"] ?: return@withContext null
            val fetcher = CodingPlanFetchers.forProvider(provider) ?: return@withContext null

            fetcher.fetchQuota(cred.metadata)
        } catch (e: Exception) {
            android.util.Log.e("CodingPlanWidget", "Failed to fetch quota: ${e.message}")
            null
        }
    }
}