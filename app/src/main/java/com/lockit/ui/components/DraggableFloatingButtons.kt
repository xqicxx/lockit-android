package com.lockit.ui.components

import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lockit.R
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
    // Get screen bounds for clamping (recalculated on config change)
    val density = LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val buttonWidthPx = with(density) { 88.dp.toPx() }  // 2 buttons + spacing
    val buttonHeightPx = with(density) { 96.dp.toPx() } // 2 rows + spacing

    // Left/right side toggle state
    val isOnRight = remember { mutableStateOf(true) }

    // Position: relative offset from gravity anchor (TOP|RIGHT)
    // offsetX: 0 = right edge, negative = moves left
    // offsetY: 0 = top margin (100dp), positive = moves down
    val offsetX = remember { mutableStateOf(0f) }
    val offsetY = remember { mutableStateOf(screenHeightPx / 2 - 100f - buttonHeightPx / 2) }

    // Recalculate Y position on screen rotation
    LaunchedEffect(configuration.screenHeightDp) {
        if (offsetY.value > screenHeightPx - buttonHeightPx - 100f) {
            offsetY.value = screenHeightPx - buttonHeightPx - 100f
        }
    }

    // Snap to side function
    fun snapToSide(right: Boolean) {
        isOnRight.value = right
        // offsetX: 0 = right, -(screenWidth - buttonWidth) = left
        offsetX.value = if (right) 0f else -(screenWidthPx - buttonWidthPx - 32f)
    }

    // Button container - positioned relative to gravity anchor
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val newX = offsetX.value + dragAmount.x
                    val newY = offsetY.value + dragAmount.y
                    offsetX.value = newX.coerceIn(-(screenWidthPx - buttonWidthPx - 32f), 0f)
                    offsetY.value = newY.coerceIn(0f, screenHeightPx - buttonHeightPx - 100f)
                }
            }
            .visibleBackground()
            .padding(8.dp)
    ) {
        // 2x2 grid layout
        Column {
            Row {
                VisibleButton(
                    iconRes = R.drawable.ic_swap_horiz,
                    contentDescription = "切换位置",
                    onClick = { snapToSide(!isOnRight.value) },
                    backgroundColor = Color(0x80B34700),
                    iconColor = Color.White
                )
                Spacer(modifier = Modifier.width(4.dp))
                VisibleButton(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = "返回",
                    onClick = onBack,
                    backgroundColor = Color(0x80111111),
                    iconColor = Color.White
                )
            }
            Spacer(modifier = Modifier.padding(4.dp))
            Row {
                VisibleButton(
                    iconRes = R.drawable.ic_refresh,
                    contentDescription = "重新登录",
                    onClick = onReset,
                    backgroundColor = Color(0x80B34700),
                    iconColor = Color.White
                )
                Spacer(modifier = Modifier.width(4.dp))
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
    return this
        .shadow(8.dp, RoundedCornerShape(24.dp))
        .background(
            color = Color(0x90111111),
            shape = RoundedCornerShape(24.dp)
        )
        .border(
            width = 1.5.dp,
            color = Color.White.copy(alpha = 0.5f),
            shape = RoundedCornerShape(24.dp)
        )
}