package com.lockit.widget

import android.content.Context
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
import com.lockit.data.vault.CodingPlanPrefs
import com.lockit.domain.CodingPlanQuota

// Suppress lint: Glance ColorProvider accepts Int colors
@Suppress("ResourceType")
private val ColorDarkBg = ColorProvider(android.graphics.Color.parseColor("#1A1A1A"))
@Suppress("ResourceType")
private val ColorWhite = ColorProvider(android.graphics.Color.WHITE)
@Suppress("ResourceType")
private val ColorGray = ColorProvider(android.graphics.Color.GRAY)
@Suppress("ResourceType")
private val ColorLightGray = ColorProvider(android.graphics.Color.parseColor("#AAAAAA"))
@Suppress("ResourceType")
private val ColorOrange = ColorProvider(android.graphics.Color.parseColor("#B34700"))
@Suppress("ResourceType")
private val ColorRed = ColorProvider(android.graphics.Color.parseColor("#A30000"))

/** Status codes for widget data resolution. */
private object WidgetStatus {
    const val LOCKED = "LOCKED"
    const val NO_CREDENTIALS = "NO_CREDENTIALS"
    const val NO_CACHE = "NO_CACHE"
    const val FETCH_FAILED = "FETCH_FAILED"
}

/**
 * Coding Plan Widget using Glance.
 * Reads cached quota from SharedPreferences (widget runs in separate process,
 * cannot access Room database directly).
 */
class CodingPlanWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (status, quota) = loadWidgetData(context)

        provideContent {
            when (status) {
                WidgetStatus.LOCKED -> LockedWidgetContent()
                else -> QuotaWidgetContent(quota, status)
            }
        }
    }
}

/**
 * Load widget data from SharedPreferences cache.
 * Widget runs in a separate process and cannot access Room database or LockitApp DI.
 */
private fun loadWidgetData(context: Context): Pair<String, CodingPlanQuota?> {
    if (!CodingPlanPrefs.isVaultUnlocked(context)) {
        return WidgetStatus.LOCKED to null
    }

    val cacheTimestamp = CodingPlanPrefs.getCacheTimestamp(context)
    if (cacheTimestamp == 0L) {
        return WidgetStatus.NO_CACHE to null
    }

    val quota = try {
        CodingPlanPrefs.loadQuotaCache(context)
    } catch (e: Exception) {
        android.util.Log.e("CodingPlanWidget", "Failed to load quota cache", e)
        null
    }

    return when {
        quota != null -> "OK" to quota
        else -> WidgetStatus.FETCH_FAILED to null
    }
}

@Composable
private fun LockedWidgetContent() {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorDarkBg)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Locked",
            style = TextStyle(
                color = ColorOrange,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun QuotaWidgetContent(quota: CodingPlanQuota?, status: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorDarkBg)
            .padding(12.dp)
    ) {
        // Header row: title + refresh button
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                text = quota?.instanceName?.uppercase() ?: "CODING PLAN",
                style = TextStyle(
                    color = ColorWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Box(
                modifier = GlanceModifier
                    .background(ColorOrange)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .clickable(onRefreshAction)
            ) {
                Text(
                    text = "REFRESH",
                    style = TextStyle(
                        color = ColorWhite,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

        if (quota != null) {
            // Status info
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = "${quota.remainingDays}d left",
                    style = TextStyle(color = ColorLightGray, fontSize = 10.sp)
                )
                if (quota.autoRenewFlag) {
                    Text(
                        text = "  AUTO",
                        style = TextStyle(color = ColorOrange, fontSize = 9.sp)
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            // Quota gauges row
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                QuotaGaugeWidget("5h", quota.sessionUsed, quota.sessionTotal, GlanceModifier.defaultWeight())
                QuotaGaugeWidget("Wk", quota.weekUsed, quota.weekTotal, GlanceModifier.defaultWeight())
                QuotaGaugeWidget("Mo", quota.monthUsed, quota.monthTotal, GlanceModifier.defaultWeight())
            }
        } else {
            // Differentiated failure message
            val (message, color) = when (status) {
                WidgetStatus.NO_CREDENTIALS -> "No credentials" to ColorOrange
                WidgetStatus.NO_CACHE -> "No cached data" to ColorGray
                WidgetStatus.FETCH_FAILED -> "Fetch failed" to ColorRed
                else -> "No data" to ColorGray
            }
            Text(
                text = message,
                style = TextStyle(
                    color = color,
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
        pct >= 90 -> ColorRed
        pct >= 70 -> ColorOrange
        else -> ColorLightGray
    }

    Column(modifier = modifier) {
        Text(
            text = "$label $pct%",
            style = TextStyle(color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        )
        val barFill = (pct / 10)
        Text(
            text = "█".repeat(barFill) + "░".repeat(10 - barFill),
            style = TextStyle(color = color, fontSize = 8.sp)
        )
    }
}

private val onRefreshAction = actionRunCallback<RefreshWidgetCallback>()

class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        CodingPlanWidget().update(context, glanceId)
    }
}