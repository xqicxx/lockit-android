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

                when (provider) {
                    CodingPlanProviders.CHATGPT -> ChatGPTCompactRow(
                        label = label, state = state, isExpanded = isExpanded,
                        onClick = { onToggleExpand(provider) },
                    )
                    CodingPlanProviders.QWEN_BAILIAN -> QwenCompactRow(
                        label = label, state = state, isExpanded = isExpanded,
                        onClick = { onToggleExpand(provider) },
                    )
                    CodingPlanProviders.MIMO -> MimoCompactRow(
                        label = label, state = state, isExpanded = isExpanded,
                        onClick = { onToggleExpand(provider) },
                    )
                    CodingPlanProviders.DEEPSEEK -> DeepSeekCompactRow(
                        label = label, state = state, isExpanded = isExpanded,
                        onClick = { onToggleExpand(provider) },
                    )
                    else -> ClaudeCompactRow(
                        label = label, state = state, isExpanded = isExpanded,
                        onClick = { onToggleExpand(provider) },
                    )
                }

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
        if (total > 0) {
            Text(
                text = "$used / $total",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun InfoGrid(items: List<Pair<String, String>>) {
    val rows = dedupeInfoItems(items).chunked(2)
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

internal fun dedupeInfoItems(
    items: List<Pair<String, String>>,
    existing: List<Pair<String, String>> = emptyList(),
): List<Pair<String, String>> {
    val seenLabels = existing.mapTo(mutableSetOf()) { it.first.trim().lowercase() }
    val seenValues = existing.mapTo(mutableSetOf()) { it.second.trim().lowercase() }
    return items.mapNotNull { (label, value) ->
        val cleanLabel = label.trim()
        val cleanValue = value.trim()
        if (cleanLabel.isBlank() || cleanValue.isBlank() || cleanValue == "null") {
            null
        } else {
            val labelKey = cleanLabel.lowercase()
            val valueKey = cleanValue.lowercase()
            if (!seenLabels.add(labelKey) || !seenValues.add(valueKey)) {
                null
            } else {
                cleanLabel to cleanValue
            }
        }
    }
}

@Composable
internal fun QuotaInfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
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
