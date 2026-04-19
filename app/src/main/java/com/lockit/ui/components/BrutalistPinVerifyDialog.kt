package com.lockit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.ui.theme.Grey400
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.SurfaceContainer
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import com.lockit.utils.BiometricUtils

/**
 * Brutalist PIN verification dialog matching the biometric handshake design.
 * Used when biometric is unavailable or fails.
 *
 * Design elements:
 * - Black header bar with "BIOMETRIC_HANDSHAKE"
 * - Fingerprint icon + status text
 * - Session token display
 * - PIN dots + keypad
 * - "TERMINATE_PROCEDURE" dismiss button
 * - Status bar with pulsing indicator
 * - Grid dot background + brutalist offset shadow
 */
@Composable
fun BrutalistPinVerifyDialog(
    app: LockitApp,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val sessionId = remember { (1000..9999).random() }

    fun onDigit(digit: String) {
        if (pin.length < 4) {
            pin += digit
            errorMessage = null
        }
    }

    fun onBackspace() {
        if (pin.isNotEmpty()) {
            pin = pin.dropLast(1)
            errorMessage = null
        }
    }

    fun onSubmit() {
        if (pin.length < 4) {
            errorMessage = "PIN_TOO_SHORT"
            return
        }
        val result = app.vaultManager.unlockVault(pin)
        if (result.isSuccess) {
            BiometricUtils.refreshCache()
            onVerified()
        } else {
            errorMessage = "WRONG_PIN"
            pin = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // Outer wrapper for offset shadow
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(2.dp, Primary),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // === Header Bar ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Primary)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "BIOMETRIC_HANDSHAKE",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = White,
                            letterSpacing = 1.sp,
                        )
                    }
                    // 3 dots
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(White.copy(0.2f)),
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(White.copy(0.4f)),
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                    }
                }

                // === Content Body ===
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Grid background
                    val gridDotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .matchParentSize(),
                    ) {
                        val dotRadius = 1.dp.toPx()
                        val spacing = 24.dp.toPx()
                        var x = 0f
                        while (x < size.width) {
                            var y = 0f
                            while (y < size.height) {
                                drawCircle(
                                    color = gridDotColor,
                                    radius = dotRadius,
                                    center = Offset(x, y),
                                )
                                y += spacing
                            }
                            x += spacing
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Fingerprint icon box
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .border(1.dp, Primary)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = TacticalRed,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))

                        // Status text
                        Text(
                            text = "ENTER PIN VERIFICATION",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Primary,
                            letterSpacing = 1.sp,
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Session token
                        Box(
                            modifier = Modifier
                                .border(1.dp, Grey400)
                                .background(SurfaceContainer)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "SESSION_TOKEN: 0x${sessionId.toString(16).uppercase()}_LOCKIT_AUTH",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 8.sp,
                                color = Grey400,
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))

                        // PIN dots
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(4) { index ->
                                Box(
                                    modifier = Modifier
                                        .requiredSize(12.dp)
                                        .border(1.5.dp, Primary)
                                        .background(if (index < pin.length) Primary else White),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))

                        // Keypad
                        val keys = listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9"),
                            listOf("DEL", "0", "OK"),
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            keys.forEach { row ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    row.forEachIndexed { index, key ->
                                        PinKey(
                                            modifier = Modifier.weight(1f),
                                            key = key,
                                            onDigit = { onDigit(it) },
                                            onBackspace = ::onBackspace,
                                            onSubmit = ::onSubmit,
                                            hasRightBorder = index < row.size - 1,
                                            hasBottomBorder = true,
                                        )
                                    }
                                }
                            }
                        }

                        // Error message
                        errorMessage?.let { error ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = error,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                                color = TacticalRed,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // Dismiss button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Primary)
                                .clickable(onClick = onDismiss)
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "TERMINATE_PROCEDURE",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = Primary,
                                letterSpacing = 1.sp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // === Status Bar ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Primary)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Pulsing indicator
                        Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(
                                color = IndustrialOrange,
                                radius = size.minDimension / 2,
                            )
                        }
                        Text(
                            text = "PIN_ENTRY_REQUIRED",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = IndustrialOrange,
                        )
                    }
                    Text(
                        text = "OP_ID: $sessionId",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = White.copy(0.5f),
                    )
                }
            }

            // Brutalist offset shadow (2px down-right)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(2.dp, 2.dp)
                    .border(2.dp, Primary)
                    .background(Color.Transparent),
            )
        }
    }
}

@Composable
private fun PinKey(
    modifier: Modifier,
    key: String,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    hasRightBorder: Boolean,
    hasBottomBorder: Boolean,
) {
    Box(
        modifier = modifier
            .aspectRatio(4f / 3f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawBehind {
                val sw = 1.dp.toPx()
                if (hasRightBorder) {
                    drawLine(Primary, Offset(size.width, 0f), Offset(size.width, size.height), sw)
                }
                if (hasBottomBorder) {
                    drawLine(Primary, Offset(0f, size.height), Offset(size.width, size.height), sw)
                }
            }
            .clickable {
                when (key) {
                    "DEL" -> onBackspace()
                    "OK" -> onSubmit()
                    else -> onDigit(key)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            "DEL" -> {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Primary,
                    modifier = Modifier.requiredSize(18.dp),
                )
            }
            "OK" -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Submit",
                    tint = IndustrialOrange,
                    modifier = Modifier.requiredSize(20.dp),
                )
            }
            else -> {
                Text(
                    text = key,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Primary,
                )
            }
        }
    }
}
