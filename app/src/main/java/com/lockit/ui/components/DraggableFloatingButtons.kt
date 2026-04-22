package com.lockit.ui.components

import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lockit.R
import kotlin.math.roundToInt

/**
 * Draggable floating button container with glassmorphism effect.
 * Used in WebViewAuthActivity for overlay controls.
 */
class DraggableFloatingButtons(
    private val onBack: () -> Unit,
    private val onReset: () -> Unit,
    private val onClose: () -> Unit,
) {

    fun createView(context: android.content.Context): View {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FloatingButtonsContent(onBack, onReset, onClose)
            }
        }
    }
}

@Composable
private fun FloatingButtonsContent(
    onBack: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val offsetX = remember { mutableStateOf(0f) }
    val offsetY = remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX.value += dragAmount.x
                    offsetY.value += dragAmount.y
                }
            }
            .glassmorphismBackground()
            .padding(8.dp)
    ) {
        Row {
            // Back button
            GlassButton(
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = "返回",
                onClick = onBack
            )

            // Reset button
            GlassButton(
                iconRes = R.drawable.ic_refresh,
                contentDescription = "重新登录",
                onClick = onReset
            )

            // Close button
            GlassButton(
                iconRes = R.drawable.ic_close,
                contentDescription = "关闭",
                onClick = onClose,
                isDestructive = true
            )
        }
    }
}

@Composable
private fun GlassButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
) {
    val iconColor = if (isDestructive) Color(0xFFA30000) else Color.White

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .background(
                color = if (isDestructive) Color(0x40A30000) else Color(0x30FFFFFF),
                shape = CircleShape
            )
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun Modifier.glassmorphismBackground(): Modifier {
    // Semi-transparent glass effect with shadow
    // API 31+ would add RenderEffect blur, but for compatibility we use simple transparency
    return this
        .shadow(8.dp, RoundedCornerShape(24.dp))
        .background(
            color = Color(0x60FFFFFF), // Semi-transparent white
            shape = RoundedCornerShape(24.dp)
        )
        .border(
            width = 1.dp,
            color = Color(0x40FFFFFF), // Border glow
            shape = RoundedCornerShape(24.dp)
        )
}

// Removed custom offset extension - using standard Modifier.offset(IntOffset)