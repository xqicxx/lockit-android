package com.lockit.ui.components

import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lockit.R
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.TacticalRed
import kotlin.math.roundToInt

/**
 * Draggable floating button container with left/right snap toggle.
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
    // Get screen bounds for clamping
    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val screenHeightPx = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val buttonWidthPx = with(density) { 180.dp.toPx() } // Increased for 4 buttons
    val buttonHeightPx = with(density) { 56.dp.toPx() }

    // Left/right side toggle state
    val isOnRight = remember { mutableStateOf(true) }

    // Position: initially right-middle
    val offsetX = remember { mutableStateOf(screenWidthPx - buttonWidthPx - 16f) }
    val offsetY = remember { mutableStateOf(screenHeightPx / 2 - buttonHeightPx / 2) }

    // Snap to side function
    fun snapToSide(right: Boolean) {
        isOnRight.value = right
        offsetX.value = if (right) screenWidthPx - buttonWidthPx - 16f else 16f
    }

    // Full-screen transparent container for touch capture
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Positioned button container (draggable anywhere)
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newX = offsetX.value + dragAmount.x
                        val newY = offsetY.value + dragAmount.y
                        // Clamp to keep buttons visible
                        offsetX.value = newX.coerceIn(0f, screenWidthPx - buttonWidthPx)
                        offsetY.value = newY.coerceIn(0f, screenHeightPx - buttonHeightPx)
                    }
                }
                .visibleBackground()
                .padding(8.dp)
        ) {
            Row {
                // Toggle position button (left/right snap)
                VisibleButton(
                    iconRes = R.drawable.ic_swap_horiz,
                    contentDescription = "切换位置",
                    onClick = { snapToSide(!isOnRight.value) },
                    backgroundColor = Color(0x80B34700),
                    iconColor = Color.White
                )
                VisibleButton(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = "返回",
                    onClick = onBack,
                    backgroundColor = Color(0x80111111),
                    iconColor = Color.White
                )
                VisibleButton(
                    iconRes = R.drawable.ic_refresh,
                    contentDescription = "重新登录",
                    onClick = onReset,
                    backgroundColor = Color(0x80B34700),
                    iconColor = Color.White
                )
                VisibleButton(
                    iconRes = R.drawable.ic_close,
                    contentDescription = "关闭",
                    onClick = onClose,
                    backgroundColor = Color(0x80A30000),
                    iconColor = Color.White
                )
            }
        }
    }
}

@Composable
private fun VisibleButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconColor: Color,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .background(
                color = backgroundColor,
                shape = CircleShape
            )
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
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
private fun Modifier.visibleBackground(): Modifier {
    // Dark background with white border - visible on any page color
    return this
        .shadow(8.dp, RoundedCornerShape(24.dp))
        .background(
            color = Color(0x90111111), // Dark semi-transparent (visible on white)
            shape = RoundedCornerShape(24.dp)
        )
        .border(
            width = 1.5.dp,
            color = Color.White.copy(alpha = 0.5f), // White border for contrast
            shape = RoundedCornerShape(24.dp)
        )
}