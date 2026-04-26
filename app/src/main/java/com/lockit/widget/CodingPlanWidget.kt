package com.lockit.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.lockit.data.vault.CodingPlanPrefs
import com.lockit.domain.CodingPlanFetchers
import com.lockit.domain.CodingPlanQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Suppress lint: Glance ColorProvider accepts Int colors, not R.color references
@Suppress("ResourceType")
private val ColorDarkBg = ColorProvider(android.graphics.Color.parseColor("#1A1A1A"))
@Suppress("ResourceType")
private val ColorPanelBg = ColorProvider(android.graphics.Color.parseColor("#242424"))
@Suppress("ResourceType")
private val ColorWhite = ColorProvider(android.graphics.Color.WHITE)
@Suppress("ResourceType")
private val ColorGray = ColorProvider(android.graphics.Color.GRAY)
@Suppress("ResourceType")
private val ColorOrange = ColorProvider(android.graphics.Color.parseColor("#B34700"))
@Suppress("ResourceType")
private val ColorRed = ColorProvider(android.graphics.Color.parseColor("#A30000"))

private const val TAG = "CodingPlanWidget"

private sealed interface WidgetQuotaState {
    data class Ready(val quota: CodingPlanQuota, val cacheAgeMinutes: Long) : WidgetQuotaState
    data class Empty(val message: String) : WidgetQuotaState
    data class Error(val message: String) : WidgetQuotaState
}

/**
 * Coding Plan Widget using Glance.
 * Displays quota gauges (5h/week/month) with refresh button.
 */
class CodingPlanWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadWidgetState(context)

        provideContent {
            CodingPlanWidgetContent(state)
        }
    }
}

@Composable
private fun CodingPlanWidgetContent(state: WidgetQuotaState) {
    when (state) {
        is WidgetQuotaState.Ready -> QuotaWidgetContent(state.quota, state.cacheAgeMinutes)
        is WidgetQuotaState.Empty -> MessageWidgetContent(state.message, ColorOrange)
        is WidgetQuotaState.Error -> MessageWidgetContent(state.message, ColorRed)
    }
}

@Composable
private fun MessageWidgetContent(message: String, color: ColorProvider) {
    WidgetSurface {
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = TextStyle(
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun QuotaWidgetContent(quota: CodingPlanQuota, cacheAgeMinutes: Long) {
    WidgetSurface {
        HeaderRow(quota)

        Text(
            text = "CACHE ${cacheAgeMinutes}M AGO",
            style = TextStyle(
                color = ColorGray,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        )

        Text(
            text = "剩余 ${quota.remainingDays} 天",
            style = TextStyle(
                color = ColorGray,
                fontSize = 10.sp
            )
        )
        if (quota.autoRenewFlag) {
            Text(
                text = "自动续费",
                style = TextStyle(
                    color = ColorOrange,
                    fontSize = 9.sp
                )
            )
        }

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            QuotaGaugeWidget("5h", quota.sessionUsed, quota.sessionTotal, GlanceModifier.defaultWeight())
            QuotaGaugeWidget("周", quota.weekUsed, quota.weekTotal, GlanceModifier.defaultWeight())
            QuotaGaugeWidget("月", quota.monthUsed, quota.monthTotal, GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun WidgetSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorDarkBg)
            .padding(12.dp),
        content = content
    )
}

@Composable
private fun HeaderRow(quota: CodingPlanQuota) {
    Row(
        modifier = GlanceModifier.fillMaxWidth()
    ) {
        Text(
            text = quota.instanceName.uppercase().ifBlank { "CODING PLAN" },
            style = TextStyle(
                color = ColorWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = GlanceModifier.defaultWeight()
        )
        Box(
            modifier = GlanceModifier
                .background(ColorPanelBg)
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .clickable(onRefreshAction)
        ) {
            Text(
                text = "REFRESH",
                style = TextStyle(
                    color = ColorOrange,
                    fontSize = 9.sp,
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
        pct >= 90 -> ColorRed
        pct >= 70 -> ColorOrange
        else -> ColorGray
    }

    Column(modifier = modifier) {
        Text(
            text = "$label $pct%",
            style = TextStyle(
                color = color,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        )
        // Simple text bar representation
        val barFill = (pct / 10)
        Text(
            text = "█".repeat(barFill) + "░".repeat(10 - barFill),
            style = TextStyle(
                color = color,
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
        refreshQuotaCache(context)
        CodingPlanWidget().update(context, glanceId)
    }
}

private fun loadWidgetState(context: Context): WidgetQuotaState {
    return try {
        val quota = CodingPlanPrefs.loadQuotaCache(context)
        if (quota != null) {
            val cacheAgeMinutes = ((System.currentTimeMillis() - CodingPlanPrefs.getCacheTimestamp(context)) / 60_000)
                .coerceAtLeast(0)
            return WidgetQuotaState.Ready(quota, cacheAgeMinutes)
        }

        if (CodingPlanPrefs.hasData(context)) {
            WidgetQuotaState.Empty("点击刷新获取额度")
        } else {
            WidgetQuotaState.Empty("无 CodingPlan 凭据")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load widget state", e)
        WidgetQuotaState.Error("读取缓存失败")
    }
}

private suspend fun refreshQuotaCache(context: Context) {
    withContext(Dispatchers.IO) {
        try {
            val metadata = CodingPlanPrefs.getMetadata(context)
            val provider = metadata["provider"]
            if (provider == null) {
                Log.e(TAG, "Cannot refresh widget: active provider missing")
                return@withContext
            }

            val fetcher = CodingPlanFetchers.forProvider(provider)
            if (fetcher == null) {
                Log.e(TAG, "Cannot refresh widget: unsupported provider=$provider")
                return@withContext
            }

            val quota = fetcher.fetchQuota(metadata)
            if (quota == null) {
                Log.e(TAG, "Cannot refresh widget: no quota returned for provider=$provider")
                return@withContext
            }

            CodingPlanPrefs.saveQuotaCache(context, quota, provider)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh quota cache", e)
        }
    }
}
