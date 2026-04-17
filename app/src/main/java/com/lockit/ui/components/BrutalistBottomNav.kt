package com.lockit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.R
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(White)
            .border(1.dp, Color.Black),
    ) {
        BottomNavItem.entries.forEachIndexed { index, item ->
            val isSelected = item == selected
            val label = stringResource(item.labelRes)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) Primary else Color.Transparent)
                    .clickable { onItemSelected(item) },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = label,
                        tint = if (isSelected) White else Primary,
                        modifier = Modifier.height(20.dp),
                    )
                    Text(
                        text = label,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = if (isSelected) White else Primary,
                    )
                }
            }

            // Vertical separator between items
            if (index < BottomNavItem.entries.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.Black),
                )
            }
        }
    }
}
