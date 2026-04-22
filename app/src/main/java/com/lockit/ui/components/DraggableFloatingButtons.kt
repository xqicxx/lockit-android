package com.lockit.ui.components

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.onGloballyPositioned
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
 * Uses MATCH_PARENT with touch passthrough for proper drag support.
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
    val density = LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // Actual measured button size (not hardcoded)
    val buttonWidthPx = remember { mutableStateOf(0f) }
    val buttonHeightPx = remember { mutableStateOf(0f) }

    // Left/right side toggle state
    val isOnRight = remember { mutableStateOf(true) }

    // Position: absolute screen coordinates
    val offsetX = remember { mutableStateOf(screenWidthPx - 100f) }
    val offsetY = remember { mutableStateOf(screenHeightPx / 2) }

    // Recalculate position on config change (rotation, split-screen)
    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp, buttonWidthPx.value, buttonHeightPx.value) {
        // Snap to correct side after rotation
        val btnW = buttonWidthPx.value
        val btnH = buttonHeightPx.value
        if (btnW > 0 && btnH > 0) {
            if (isOnRight.value) {
                offsetX.value = screenWidthPx - btnW - 16f
            } else {
                offsetX.value = 16f
            }
            // Ensure Y is in bounds
            offsetY.value = offsetY.value.coerceIn(0f, screenHeightPx - btnH - 50f)
        }
    }

    // Snap to side function
    fun snapToSide(right: Boolean) {
        isOnRight.value = right
        val btnW = buttonWidthPx.value
        if (btnW > 0) {
            offsetX.value = if (right) screenWidthPx - btnW - 16f else 16f
        }
    }

    // Safe coerceIn (handles case where min > max)
    fun safeCoerceIn(value: Float, min: Float, max: Float): Float {
        return if (min <= max) value.coerceIn(min, max) else if (value < min) min else if (value > max) max else value
    }

    // Full-screen container with measured button box
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    buttonWidthPx.value = coordinates.size.width.toFloat()
                    buttonHeightPx.value = coordinates.size.height.toFloat()
                    // Initialize position on first measure
                    if (offsetX.value == screenWidthPx - 100f) {
                        offsetX.value = screenWidthPx - coordinates.size.width - 16f
                        offsetY.value = screenHeightPx / 2 - coordinates.size.height / 2
                    }
                }
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .pointerInput(configuration.screenWidthDp, configuration.screenHeightDp) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val btnW = buttonWidthPx.value
                        val btnH = buttonHeightPx.value
                        if (btnW > 0 && btnH > 0) {
                            val newX = offsetX.value + dragAmount.x
                            val newY = offsetY.value + dragAmount.y
                            offsetX.value = safeCoerceIn(newX, 0f, screenWidthPx - btnW)
                            offsetY.value = safeCoerceIn(newY, 50f, screenHeightPx - btnH - 50f)
                        }
                    }
                }
                .visibleBackground()
                .padding(8.dp)
        ) {
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