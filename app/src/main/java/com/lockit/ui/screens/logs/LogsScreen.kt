package com.lockit.ui.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AddCircleOutline
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.R
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

private const val INITIAL_LOAD_COUNT = 100
private const val LOAD_MORE_COUNT = 20
private const val EXPORT_REMINDER_THRESHOLD = 200

@Composable
fun LogsScreen(
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as LockitApp
    var displayedCount by remember { mutableStateOf(INITIAL_LOAD_COUNT) }
    var showExportReminder by remember { mutableStateOf(false) }

    // Load logs on background thread using produceState
    val logs by produceState<List<AuditEntry>>(
        initialValue = emptyList(),
        key1 = displayedCount,
    ) {
        // Run I/O operation on Dispatchers.IO to prevent ANR
        value = withContext(Dispatchers.IO) {
            app.auditLogger.getRecentEntriesByCount(displayedCount)
        }
    }

    // Get total count on background thread
    val totalCount by produceState<Int>(
        initialValue = 0,
    ) {
        value = withContext(Dispatchers.IO) {
            app.auditLogger.getTotalCount()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        BrutalistTopBar()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ScreenHero(
                title = stringResource(R.string.logs_title),
                subtitle = stringResource(R.string.logs_subtitle),
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = stringResource(R.string.logs_event_stream),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = Primary,
                )
                Text(
                    text = stringResource(R.string.logs_entries, logs.size),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = stringResource(R.string.logs_no_events),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                logs.forEach { entry ->
                    LogRow(entry)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Load more button
                if (displayedCount < totalCount) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, IndustrialOrange)
                            .clickable {
                                val newCount = displayedCount + LOAD_MORE_COUNT
                                displayedCount = minOf(newCount, totalCount)
                                if (displayedCount >= EXPORT_REMINDER_THRESHOLD && !showExportReminder) {
                                    showExportReminder = true
                                }
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AddCircleOutline,
                                contentDescription = null,
                                tint = IndustrialOrange,
                                modifier = Modifier.height(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.logs_load_more),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = IndustrialOrange,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.logs_remaining, totalCount - displayedCount),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }

                // Export reminder
                if (showExportReminder) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(IndustrialOrange.copy(0.1f))
                            .border(1.dp, IndustrialOrange.copy(0.5f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.logs_export_reminder),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = IndustrialOrange,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            TerminalFooter(
                lines = listOf(
                    "> AUDIT_LOG_STATUS:" to IndustrialOrange,
                    stringResource(R.string.logs_storage_local) to MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.logs_retention) to MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.logs_encryption) to MaterialTheme.colorScheme.onSurfaceVariant,
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

    // Translate action names for display
    val actionDisplay = when (entry.action) {
        "VAULT_INITIALIZED" -> stringResource(R.string.action_vault_initialized)
        "VAULT_UNLOCKED" -> stringResource(R.string.action_vault_unlocked)
        "VAULT_UNLOCK_FAILED" -> stringResource(R.string.action_vault_unlock_failed)
        "VAULT_LOCKED" -> stringResource(R.string.action_vault_locked)
        "PASSWORD_CHANGED" -> stringResource(R.string.action_password_changed)
        "CREDENTIAL_CREATED" -> stringResource(R.string.action_credential_created)
        "CREDENTIAL_UPDATED" -> stringResource(R.string.action_credential_updated)
        "CREDENTIAL_DELETED" -> stringResource(R.string.action_credential_deleted)
        "CREDENTIAL_VIEWED" -> stringResource(R.string.action_credential_viewed)
        "CREDENTIAL_COPIED" -> stringResource(R.string.action_credential_copied)
        else -> entry.action
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(0.15f))
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
                    text = actionDisplay,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Primary,
                )
                Text(
                    text = entry.detail,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = formatTime(Instant.ofEpochMilli(entry.timestamp)),
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f),
        )
    }
}