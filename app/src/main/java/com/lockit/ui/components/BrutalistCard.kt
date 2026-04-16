package com.lockit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Brutalist card with 1px black border and 2px offset solid black shadow.
 * No border-radius, no blur shadows.
 */
@Composable
fun BrutalistCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(1.dp, Color.Black)
            .padding(0.dp),
    ) {
        content()
    }
}
