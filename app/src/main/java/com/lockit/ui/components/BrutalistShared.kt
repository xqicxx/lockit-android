package com.lockit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.SurfaceHighest
import com.lockit.ui.theme.SurfaceLow
import com.lockit.ui.theme.White

@Composable
fun SeparatorLine(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colorScheme.outlineVariant.copy(alpha = 0.2f)),
    )
}

@Composable
fun ScreenHero(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)  // Ensure consistent minimum height
    ) {
        Text(
            text = title,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,  // Consistent size for all pages
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = (-0.5).sp,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun InfoTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .background(colorScheme.surfaceContainerHighest)
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TerminalFooter(
    lines: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.surfaceContainerHighest)
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
            .padding(12.dp),
    ) {
        Column {
            lines.forEach { (text, color) ->
                Text(
                    text = text,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    color = color,
                    fontWeight = if (color == IndustrialOrange) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun BrutalistConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    confirmVariant: ButtonVariant = ButtonVariant.Danger,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        },
        text = {
            Text(
                text = message,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
            )
        },
        confirmButton = {
            BrutalistButton(
                text = confirmText,
                onClick = onConfirm,
                variant = confirmVariant,
                modifier = Modifier.height(36.dp),
                useMonoFont = true,
            )
        },
        dismissButton = {
            BrutalistButton(
                text = "CANCEL",
                onClick = onDismiss,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.height(36.dp),
                useMonoFont = true,
            )
        },
        containerColor = colorScheme.surfaceContainerHigh,
    )
}

@Composable
fun BrutalistToast(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500)
        onDismiss()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(
                if (onAction != null) Modifier.clickable { onAction() } else Modifier
            ),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "> $message",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            color = IndustrialOrange,
            fontWeight = FontWeight.Bold,
        )
        if (onAction != null) {
            Text(
                text = "TAP",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun BrutalistEmptyState(
    message: String,
    modifier: Modifier = Modifier,
) {
    BrutalistCard(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
