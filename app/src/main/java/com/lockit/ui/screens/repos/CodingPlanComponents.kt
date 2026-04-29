package com.lockit.ui.screens.repos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.R
import com.lockit.domain.CodingPlanProviders
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Multi-provider compact dashboard — all providers visible at once, no switching needed.
 * Each provider shows key metrics in a compact row. Click to expand full detail inline.
 */
@Composable
internal fun MultiProviderBoard(
    providerQuotas: Map<String, ProviderQuotaState>,
    expandedProvider: String?,
    onToggleExpand: (String) -> Unit,
    onRefreshAll: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val providerLabels = mapOf(
        "qwen_bailian" to stringResource(R.string.provider_qwen),
        "chatgpt" to stringResource(R.string.provider_chatgpt),
        "claude" to stringResource(R.string.provider_claude),
        "mimo" to "XIAOMI",
        "deepseek" to "DeepSeek",
    )
    val anyLoading = providerQuotas.values.any { it.isLoading }
    val sortedProviders = providerQuotas.keys.sortedBy { it }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outline)
            .padding(12.dp),
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.repos_coding_plan),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = colorScheme.onSurface,
            )
            if (anyLoading) {
                Text(
                    text = stringResource(R.string.repos_fetching),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    modifier = Modifier
                        .background(TacticalRed)
                        .clickable(onClick = onRefreshAll)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.repos_refresh),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = White,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (sortedProviders.isEmpty()) {
            // No cached data yet — show placeholder
            Text(
                text = stringResource(R.string.repos_quota_no_data),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            sortedProviders.forEachIndexed { index, provider ->
                if (index > 0) BoardDivider()
                val state = providerQuotas[provider] ?: return@forEachIndexed
                val label = providerLabels[provider] ?: provider
                val isExpanded = expandedProvider == provider

                CompactProviderRow(
                    provider = provider,
                    label = label,
                    state = state,
                    isExpanded = isExpanded,
                    onClick = { onToggleExpand(provider) },
                )

                // Expanded detail
                if (isExpanded && state.quota != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    when (provider) {
                        CodingPlanProviders.CHATGPT -> ChatGptCodingPlanContent(state.quota)
                        CodingPlanProviders.QWEN_BAILIAN -> QwenCodingPlanContent(state.quota)
                        CodingPlanProviders.MIMO -> MimoCodingPlanContent(state.quota)
                        CodingPlanProviders.DEEPSEEK -> DeepSeekCodingPlanContent(state.quota)
                        else -> ClaudeCodingPlanContent(state.quota)
                    }
                }

                // Error state (shown in-row, below compact)
                if (state.quota == null && !state.isLoading && state.error != null) {
                    Text(
                        text = when (state.error) {
                            "COOKIE_EXPIRED" -> stringResource(R.string.repos_quota_cookie_expired)
                            else -> stringResource(R.string.repos_quota_fetch_failed)
                        },
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 8.sp,
                        color = TacticalRed,
                        modifier = Modifier.padding(start = 64.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

private val TOKEN_PLAN_PROVIDERS = setOf(CodingPlanProviders.MIMO, CodingPlanProviders.CHATGPT, CodingPlanProviders.CLAUDE, CodingPlanProviders.DEEPSEEK)

@Composable
internal fun CompactProviderRow(
    provider: String,
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
        // Main row: label | gauges | badge | arrow
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Provider name label
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
                if (provider in TOKEN_PLAN_PROVIDERS) {
                    // Token plan: show credits used/total + plan name
                    if (quota.monthTotal > 0) {
                        Text("${quota.monthTotal / 1_000_000}M total",
                            fontFamily = JetBrainsMonoFamily, fontSize = 9.sp,
                            color = IndustrialOrange, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    val tokenBadge = quota.planName.takeIf { it.isNotBlank() }
                        ?: quota.loginMethod.takeIf { it.isNotBlank() }
                        ?: quota.instanceType.takeIf { it.isNotBlank() }
                        ?: "ACTIVE"
                    StatusChip(
                        text = tokenBadge.take(8).uppercase(),
                        color = IndustrialOrange,
                    )
                } else {
                    // Time-window plan: show 5h/Wk/Mo gauges
                    CompactGauge("5h", quota.sessionUsed, quota.sessionTotal, Modifier.weight(1f))
                    CompactGauge("Wk", quota.weekUsed, quota.weekTotal, Modifier.weight(1f))
                    if (quota.monthTotal > 0) {
                        CompactGauge("Mo", quota.monthUsed, quota.monthTotal, Modifier.weight(1f))
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
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
                }
            } else {
                Text("—", fontFamily = JetBrainsMonoFamily, fontSize = 9.sp,
                    color = colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }

            // Expand/collapse arrow
            Text(
                text = if (isExpanded) "▼" else "▶",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // Sub row: plan + status + timing + identity (below the gauges)
        if (quota != null && !state.isLoading) {
            val metaParts = mutableListOf<String>()
            // Plan info first — highest priority
            if (quota.planName.isNotBlank()) metaParts.add(quota.planName.take(16))
            if (quota.instanceType.isNotBlank()) metaParts.add(quota.instanceType.uppercase().take(10))
            if (quota.chargeType.isNotBlank()) metaParts.add(quota.chargeType.uppercase().take(10))
            if (quota.chargeAmount > 0.0) metaParts.add("¥${quota.chargeAmount}")
            if (quota.creditsRemaining > 0.0) metaParts.add("${quota.creditsRemaining} CR")
            if (state.cacheAgeMinutes > 0) metaParts.add("${state.cacheAgeMinutes}m ago")
            if (quota.remainingDays > 0) {
                metaParts.add(stringResource(R.string.repos_quota_remaining, quota.remainingDays))
            }
            if (provider !in TOKEN_PLAN_PROVIDERS) {
                quota.sessionResetsAt?.let { metaParts.add("5h ${formatResetTime(it)}") }
                quota.weekResetsAt?.let { metaParts.add("Wk ${formatResetTime(it)}") }
            }
            if (quota.loginMethod.isNotBlank()) metaParts.add(quota.loginMethod.take(12))
            val compactEmailName = quota.accountEmail.substringBefore("@").take(20)
            if (compactEmailName.isNotBlank()) metaParts.add(compactEmailName)
            if (metaParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    metaParts.forEach { part ->
                        Text(
                            text = part,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 7.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactGauge(label: String, used: Int, total: Int, modifier: Modifier) {
    val pct = if (total > 0) (used.toLong() * 100 / total).coerceIn(0, 100).toInt() else 0
    val barColor = quotaProgressColor(pct)

    Column(modifier = modifier.padding(horizontal = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$pct%",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = barColor,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (total > 0) pct / 100f else 0f)
                    .height(3.dp)
                    .background(barColor),
            )
        }
    }
}

@Composable
internal fun InfoGrid(items: List<Pair<String, String>>) {
    val rows = items.chunked(2)
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowItems.forEach { (label, value) ->
                    QuotaInfoCell(label = label, value = value, modifier = Modifier.weight(1f))
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (index != rows.lastIndex) {
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
internal fun QuotaInfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun BoardDivider() {
    Spacer(modifier = Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
internal fun StatusChip(text: String, color: Color, filled: Boolean = false) {
    Box(
        modifier = Modifier
            .background(if (filled) color.copy(alpha = 0.15f) else Color.Transparent)
            .border(1.dp, color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

internal val quotaResetFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

internal fun formatResetTime(resetAt: Instant?): String {
    return resetAt
        ?.atZone(ZoneId.systemDefault())
        ?.format(quotaResetFormatter)
        ?: "--"
}

@Composable
internal fun QuotaGauge(
    label: String,
    used: Int,
    total: Int,
    resetAt: Instant?,
    showUsageNumbers: Boolean,
    showReset: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val pct = if (total > 0) (used.toLong() * 100 / total).coerceIn(0, 100).toInt() else 0
        val barColor = quotaProgressColor(pct)

        // Label row with percentage on the right (above progress bar)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$pct%",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                color = barColor,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        // Progress bar background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            // Progress bar fill
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (total > 0) pct / 100f else 0f)
                    .height(8.dp)
                    .background(color = barColor),
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (showUsageNumbers && total > 0) "$used / $total" else stringResource(R.string.repos_quota_usage_hidden),
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showReset) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.repos_quota_reset_at, formatResetTime(resetAt)),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun quotaProgressColor(pct: Int): Color {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when {
        pct >= 90 -> TacticalRed
        pct >= 70 -> IndustrialOrange
        isDarkTheme -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
