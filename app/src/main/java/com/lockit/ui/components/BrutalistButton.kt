package com.lockit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.InterFontFamily
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White

enum class ButtonVariant {
    Primary,       // Black fill, white text
    Secondary,     // White fill, black border
    Danger,        // Red fill, white text
    Warning,       // Orange fill, white text
    Revoke,        // White fill, red border, red text
}

@Composable
fun BrutalistButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Secondary,
    enabled: Boolean = true,
    useMonoFont: Boolean = false,
    icon: ImageVector? = null,
    iconPosition: IconPosition = IconPosition.Start,
    customBorderColor: Color? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val colors = when (variant) {
        ButtonVariant.Primary -> Triple(colorScheme.onSurface, colorScheme.surface, colorScheme.onSurface.copy(alpha = 0.4f))
        ButtonVariant.Secondary -> Triple(colorScheme.surface, colorScheme.onSurface, colorScheme.outlineVariant.copy(alpha = 0.2f))
        ButtonVariant.Danger -> Triple(TacticalRed, White, TacticalRed)
        ButtonVariant.Warning -> Triple(IndustrialOrange, White, IndustrialOrange)
        ButtonVariant.Revoke -> Triple(colorScheme.surface, TacticalRed, TacticalRed)
    }
    val (bgColor, textColor, defaultBorderColor) = colors
    val borderColor = customBorderColor ?: defaultBorderColor

    // Disabled state: use distinct colors for background vs text
    val disabledBgColor = colorScheme.surfaceContainerHighest
    val disabledContentColor = colorScheme.onSurfaceVariant

    val font = if (useMonoFont) JetBrainsMonoFamily else InterFontFamily
    val weight = if (useMonoFont) FontWeight.Medium else FontWeight.Bold

    Box(
        modifier = modifier
            .border(1.dp, borderColor)
            .background(if (enabled) bgColor else disabledBgColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (iconPosition == IconPosition.Start) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) textColor else disabledContentColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = text.uppercase(),
                    fontFamily = font,
                    fontWeight = weight,
                    color = if (enabled) textColor else disabledContentColor,
                    fontSize = if (useMonoFont) 10.sp else 12.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                )
                if (iconPosition == IconPosition.End) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) textColor else disabledContentColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            Text(
                text = text.uppercase(),
                fontFamily = font,
                fontWeight = weight,
                color = if (enabled) textColor else disabledContentColor,
                fontSize = if (useMonoFont) 10.sp else 12.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

enum class IconPosition { Start, End }
