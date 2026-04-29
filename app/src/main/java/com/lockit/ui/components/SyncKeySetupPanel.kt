package com.lockit.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.R
import com.lockit.data.sync.SyncCrypto
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily

/**
 * Reusable sync key configuration panel. Decoupled from any specific sync engine:
 * callers provide the key get/set/generate callbacks.
 *
 * @param hasSyncKey Whether a sync key is currently configured
 * @param onGenerate Called when user taps GENERATE — caller should persist the new key
 * @param onImport Called with the pasted key string — caller should validate and persist
 * @param onCopy Called to get the current key string for clipboard copy
 */
@Composable
fun SyncKeySetupPanel(
    hasSyncKey: Boolean,
    onGenerate: () -> String,
    onImport: (String) -> Boolean,
    onCopy: () -> String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showInput by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Status indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasSyncKey) "SYNC KEY: CONFIGURED" else "SYNC KEY: NEEDED FOR SYNC",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasSyncKey) IndustrialOrange else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Generate (only when no key set)
        if (!hasSyncKey) {
            BrutalistButton(
                text = "GENERATE SYNC KEY",
                onClick = { onGenerate() },
                variant = ButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
                useMonoFont = true,
            )
        }

        // Import / Link another device
        if (showInput) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BrutalistTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    label = "SYNC KEY",
                    placeholder = "Paste Sync Key (Base64)",
                    modifier = Modifier.weight(1f),
                )
                BrutalistButton(
                    text = "SAVE",
                    onClick = {
                        if (onImport(inputValue)) {
                            showInput = false
                            inputValue = ""
                        }
                    },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.width(64.dp),
                    useMonoFont = true,
                )
            }
        } else {
            Text(
                text = if (hasSyncKey)
                    "Copy your Sync Key from another device and paste it here to link them."
                else
                    "Already have a Sync Key on another device? Paste it here.",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrutalistButton(
                text = if (hasSyncKey) "LINK ANOTHER DEVICE" else "LINK ANOTHER DEVICE",
                onClick = {
                    showInput = true
                    inputValue = ""
                },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
                useMonoFont = true,
            )
        }

        // Copy (only when key is set)
        if (hasSyncKey) {
            BrutalistButton(
                text = "COPY SYNC KEY",
                onClick = {
                    val key = onCopy() ?: return@BrutalistButton
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Sync Key", key))
                },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
                useMonoFont = true,
            )
        }
    }
}
