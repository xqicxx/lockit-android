package com.lockit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.R
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.White

enum class BottomNavItem(val labelRes: Int, val icon: ImageVector) {
    Repos(R.string.nav_repos, Icons.Default.Storage),
    Keys(R.string.nav_keys, Icons.Default.Key),
    Logs(R.string.nav_logs, Icons.Default.Terminal),
    Config(R.string.nav_config, Icons.Default.Settings),
}

@Composable
fun BrutalistBottomNav(
    selected: BottomNavItem,
    onItemSelected: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isLight = colorScheme.background.red > 0.5f

    val backgroundColor = if (isLight) White else colorScheme.surfaceContainerLowest
    val topBorderColor = if (isLight) Color(0xFFE0E0E0) else colorScheme.outlineVariant
    val inactiveColor = if (isLight) Color(0xFF757575) else Color(0xFF9E9E9E)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(backgroundColor)
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawLine(
                    color = topBorderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = strokeWidth,
                )
            },
    ) {
        BottomNavItem.entries.forEach { item ->
            val isSelected = item == selected
            val label = stringResource(item.labelRes)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) IndustrialOrange else Color.Transparent)
                    .clickable { onItemSelected(item) },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = label,
                        tint = if (isSelected) White else inactiveColor,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = label,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 10.sp,
                        letterSpacing = 0.3.sp,
                        color = if (isSelected) White else inactiveColor,
                    )
                }
            }
        }
    }
}
