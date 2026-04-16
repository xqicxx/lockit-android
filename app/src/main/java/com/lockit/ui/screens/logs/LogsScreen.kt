package com.lockit.ui.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.data.audit.AuditEntry
import com.lockit.data.audit.AuditSeverity
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.ScreenHero
import com.lockit.ui.components.TerminalFooter
import com.lockit.ui.components.formatTime
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import java.time.Instant

@Composable
fun LogsScreen(
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as LockitApp
    // Show last 30 days
    val logs by remember { mutableStateOf(app.auditLogger.getRecentEntries(days = 30)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(White),
    ) {
        BrutalistTopBar()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ScreenHero(
                title = "Audit Log",
                subtitle = "Security event timeline // Local device only // 30 days",
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "EVENT_STREAM",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = Primary,
                )
                Text(
                    text = "${logs.size} entries",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = Color.Gray,
                )
            }

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "NO_AUDIT_EVENTS",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 14.sp,
                        color = Color.Gray,
                    )
                }
            } else {
                logs.forEach { entry ->
                    LogRow(entry)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            TerminalFooter(
                lines = listOf(
                    "> AUDIT_LOG_STATUS:" to IndustrialOrange,
                    "STORAGE: LOCAL_DEVICE_ONLY" to Color.Gray,
                    "RETENTION: 30_DAYS" to Color.Gray,
                    "ENCRYPTION: AES-256-GCM" to Color.Gray,
                ),
            )
        }
    }
}

@Composable
private fun LogRow(entry: AuditEntry) {
    val icon = when (entry.action) {
        "VAULT_INITIALIZED" -> Icons.Default.AddCircle
        "VAULT_UNLOCKED" -> Icons.Default.LockOpen
        "VAULT_UNLOCK_FAILED" -> Icons.Default.Fingerprint
        "VAULT_LOCKED" -> Icons.Default.Lock
        "PASSWORD_CHANGED" -> Icons.Default.Key
        "CREDENTIAL_CREATED" -> Icons.Default.AddCircle
        "CREDENTIAL_UPDATED" -> Icons.Default.Edit
        "CREDENTIAL_DELETED" -> Icons.Default.Delete
        "CREDENTIAL_VIEWED" -> Icons.Default.Visibility
        "CREDENTIAL_COPIED" -> Icons.Default.ContentCopy
        else -> Icons.Default.Info
    }
    val iconColor = when (entry.severity) {
        AuditSeverity.Info -> Primary
        AuditSeverity.Warning -> IndustrialOrange
        AuditSeverity.Danger -> TacticalRed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Black.copy(0.15f))
            .background(
                when (entry.severity) {
                    AuditSeverity.Info -> Color.Transparent
                    AuditSeverity.Warning -> IndustrialOrange.copy(0.05f)
                    AuditSeverity.Danger -> TacticalRed.copy(0.05f)
                },
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.height(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = entry.action,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Primary,
                )
                Text(
                    text = entry.detail,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = Color.Gray,
                )
            }
        }
        Text(
            text = formatTime(Instant.ofEpochMilli(entry.timestamp)),
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = Color.Gray.copy(0.6f),
        )
    }
}

