package com.lockit.ui.screens.config

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.data.database.LockitDatabase
import com.lockit.data.sync.GoogleDriveSyncManager
import com.lockit.data.updater.AppUpdater
import com.lockit.data.updater.GitHubRelease
import com.lockit.domain.model.Credential
import com.lockit.ui.components.BrutalistButton
import com.lockit.ui.components.BrutalistConfirmDialog
import com.lockit.ui.components.BrutalistTextField
import com.lockit.ui.components.BrutalistToast
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.ButtonVariant
import com.lockit.ui.components.ScreenHero
import com.lockit.ui.components.TerminalFooter
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun ConfigScreen(
    app: LockitApp,
    onLockVault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showLockDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    // App update
    val appUpdater = remember { AppUpdater(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<GitHubRelease?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var githubTokenCredentialName by remember { mutableStateOf("GITHUB_TOKEN") }
    var showTokenConfigDialog by remember { mutableStateOf(false) }

    // Google Drive sync
    val syncManager = remember { GoogleDriveSyncManager(context) }
    var signedInAccount by remember { mutableStateOf(syncManager.getSignedInAccount()) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var lastBackupTime by remember { mutableStateOf<String?>(null) }

    // Google Sign-In launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            signedInAccount = task.result
            toastMessage = "GOOGLE_SIGNED_IN: ${task.result?.email}"
        } else {
            toastMessage = "GOOGLE_SIGN_IN_FAILED: ${task.exception?.message}"
        }
    }

    // Export share
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {}

    // Load last backup time when signed in
    if (signedInAccount != null && lastBackupTime == null && !isSyncing) {
        LaunchedEffect(signedInAccount) {
            val timeResult = syncManager.getLastBackupTime(signedInAccount!!)
            lastBackupTime = timeResult.getOrNull()
        }
    }

    if (showLockDialog) {
        BrutalistConfirmDialog(
            title = "LOCK_VAULT",
            message = "This will require re-entering the master password to continue.",
            confirmText = "LOCK",
            confirmVariant = ButtonVariant.Warning,
            onConfirm = {
                showLockDialog = false
                app.vaultManager.lockVault()
                onLockVault()
            },
            onDismiss = { showLockDialog = false },
        )
    }

    if (showChangePinDialog) {
        ChangePasswordDialog(
            app = app,
            onDismiss = { showChangePinDialog = false },
            onSuccess = {
                showChangePinDialog = false
                toastMessage = "PASSWORD_CHANGED_SUCCESS"
            },
        )
    }

    // Update dialog
    if (showUpdateDialog && availableUpdate != null) {
        UpdateDialog(
            release = availableUpdate!!,
            onDismiss = { showUpdateDialog = false },
            onDownload = {
                showUpdateDialog = false
                isDownloading = true
                val apkUrl = availableUpdate?.apkUrl
                if (apkUrl != null) {
                    appUpdater.downloadApk(apkUrl)
                    toastMessage = "DOWNLOAD_STARTED"
                    isDownloading = false
                }
            },
        )
    }

    // GitHub Token config dialog
    if (showTokenConfigDialog) {
        GitHubTokenConfigDialog(
            currentName = githubTokenCredentialName,
            onDismiss = { showTokenConfigDialog = false },
            onSave = { newName ->
                githubTokenCredentialName = newName
                showTokenConfigDialog = false
                toastMessage = "TOKEN_SOURCE_SET: $newName"
            },
        )
    }

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
                title = "CLI Setup",
                subtitle = "Initialize the command-line interface for the core repository",
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Vault Status Section
            ConfigSection(
                title = "VAULT_STATUS",
                items = listOf(
                    "STATE" to if (app.vaultManager.isUnlocked()) "UNLOCKED" else "LOCKED",
                    "ENCRYPTION" to "AES-256-GCM",
                    "KEY_DERIVATION" to "ARGON2ID",
                    "STORAGE" to "LOCAL_SQLITE",
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Actions Section
            ConfigSection(
                title = "ACTIONS",
                content = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrutalistButton(
                            text = "LOCK_VAULT",
                            onClick = { showLockDialog = true },
                            variant = ButtonVariant.Warning,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )
                        BrutalistButton(
                            text = "CHANGE_MASTER_PIN",
                            onClick = { showChangePinDialog = true },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )
                        BrutalistButton(
                            text = "EXPORT_KEYS",
                            onClick = {
                                exportKeys(app, context, scope) { uri, error ->
                                    if (error != null) {
                                        toastMessage = error
                                    } else if (uri != null) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/markdown"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        shareLauncher.launch(
                                            Intent.createChooser(shareIntent, "Share Keys"),
                                        )
                                    } else {
                                        toastMessage = "KEYS_EXPORTED"
                                    }
                                }
                            },
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )
                        BrutalistButton(
                            text = "EXPORT_LOGS",
                            onClick = {
                                exportLogs(app, context, scope) { uri, error ->
                                    if (error != null) {
                                        toastMessage = error
                                    } else if (uri != null) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/markdown"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        shareLauncher.launch(
                                            Intent.createChooser(shareIntent, "Share Logs"),
                                        )
                                    } else {
                                        toastMessage = "LOGS_EXPORTED"
                                    }
                                }
                            },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Cloud Sync Section
            ConfigSection(
                title = "CLOUD_SYNC",
                content = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Google account status
                        if (signedInAccount != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color.Gray)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "SIGNED_IN:",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 9.sp,
                                    color = Color.Gray,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = signedInAccount?.email ?: "",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = IndustrialOrange,
                                    modifier = Modifier.weight(1f),
                                )
                                if (lastBackupTime != null) {
                                    Text(
                                        text = "LAST_SYNC: ${lastBackupTime?.take(19) ?: "N/A"}",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 8.sp,
                                        color = Color.Gray,
                                    )
                                }
                            }
                        }

                        // Sync action buttons
                        BrutalistButton(
                            text = if (signedInAccount == null) "SIGN_IN_WITH_GOOGLE" else "SYNC_TO_DRIVE",
                            onClick = {
                                if (signedInAccount == null) {
                                    signInLauncher.launch(syncManager.getSignInIntent())
                                } else {
                                    syncToDrive(app, syncManager, signedInAccount!!, context, scope) { error ->
                                        if (error != null) {
                                            toastMessage = error
                                        } else {
                                            toastMessage = "SYNC_COMPLETE"
                                            scope.launch {
                                                val timeResult = syncManager.getLastBackupTime(signedInAccount!!)
                                                lastBackupTime = timeResult.getOrNull()
                                            }
                                        }
                                    }
                                }
                            },
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                            enabled = !isSyncing,
                        )
                        if (signedInAccount != null) {
                            BrutalistButton(
                                text = "SIGN_OUT",
                                onClick = {
                                    syncManager.signOut()
                                    signedInAccount = null
                                    lastBackupTime = null
                                    toastMessage = "SIGNED_OUT"
                                },
                                variant = ButtonVariant.Warning,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                            )
                        }

                        // Sync status
                        syncStatus?.let { status ->
                            Text(
                                text = status,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                                color = if (status.contains("ERROR")) TacticalRed else IndustrialOrange,
                            )
                        }
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Security Section
            ConfigSection(
                title = "SECURITY_INFO",
                items = listOf(
                    "SALT_LENGTH" to "16_BYTES",
                    "NONCE_LENGTH" to "12_BYTES",
                    "GCM_TAG_LENGTH" to "128_BITS",
                    "MASTER_KEY_LENGTH" to "256_BITS",
                    "ARGON2_MEMORY" to "64_MB",
                    "ARGON2_ITERATIONS" to "3",
                    "ARGON2_PARALLELISM" to "4",
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Update Section
            ConfigSection(
                title = "UPGRADE",
                content = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Current version info
                        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        val currentVersion = packageInfo.versionName ?: "Unknown"
                        val currentVersionCode = packageInfo.longVersionCode.toInt()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "当前版本",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                                color = Color.Gray,
                            )
                            Text(
                                text = "$currentVersion ($currentVersionCode)",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = IndustrialOrange,
                            )
                        }

                        // GitHub Token configuration
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Token来源",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                                color = Color.Gray,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = githubTokenCredentialName,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = IndustrialOrange,
                                    modifier = Modifier.clickable { showTokenConfigDialog = true },
                                )
                            }
                        }

                        BrutalistButton(
                            text = if (isCheckingUpdate) "检查中..." else if (isDownloading) "下载中..." else "检查更新",
                            onClick = {
                                isCheckingUpdate = true
                                scope.launch {
                                    // Read GitHub Token from vault
                                    val tokenCredential = app.vaultManager.getAllCredentials()
                                        .first()
                                        .find { it.name == githubTokenCredentialName }
                                    val token = tokenCredential?.value
                                    val result = appUpdater.checkForUpdate(currentVersionCode, token)
                                    isCheckingUpdate = false
                                    if (result.isFailure) {
                                        toastMessage = "检查失败: ${result.exceptionOrNull()?.message}"
                                    } else if (result.getOrNull() == null) {
                                        toastMessage = "已是最新版本"
                                    } else {
                                        availableUpdate = result.getOrNull()
                                        showUpdateDialog = true
                                    }
                                }
                            },
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                            enabled = !isCheckingUpdate && !isDownloading,
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            TerminalFooter(
                lines = listOf(
                    "> SYSTEM_INFO:" to IndustrialOrange,
                    "LOCKIT_ANDROID v0.1.0" to Color.Gray,
                    "COMPATIBLE_WITH_CLI v0.1.0" to Color.Gray,
                    "DESIGN: TECHNICAL_BRUTALISM_V1" to Color.Gray,
                ),
            )
        }
    }

    // Toast
    toastMessage?.let { message ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            BrutalistToast(
                message = message,
                onDismiss = { toastMessage = null },
            )
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    app: LockitApp,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isUpdating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(2.dp, Color.Black)
                .padding(24.dp),
        ) {
            Text(
                text = "CHANGE_PIN",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Primary,
            )
            Spacer(modifier = Modifier.height(16.dp))

            error?.let {
                Text(
                    text = it,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = TacticalRed,
                    modifier = Modifier.border(1.dp, TacticalRed).padding(8.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            BrutalistTextField(
                value = currentPassword,
                onValueChange = { if (it.length <= 4) { currentPassword = it; error = null } },
                label = "CURRENT_PIN",
                placeholder = "Enter 4-digit PIN",
            )
            Spacer(modifier = Modifier.height(8.dp))
            BrutalistTextField(
                value = newPassword,
                onValueChange = { if (it.length <= 4) { newPassword = it; error = null } },
                label = "NEW_PIN",
                placeholder = "Enter 4-digit PIN",
            )
            Spacer(modifier = Modifier.height(8.dp))
            BrutalistTextField(
                value = confirmPassword,
                onValueChange = { if (it.length <= 4) { confirmPassword = it; error = null } },
                label = "CONFIRM_NEW_PIN",
                placeholder = "Confirm 4-digit PIN",
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistButton(
                    text = "CANCEL",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = if (isUpdating) "UPDATING..." else "UPDATE",
                    onClick = {
                        if (isUpdating) return@BrutalistButton
                        if (currentPassword.isBlank() || newPassword.isBlank() || confirmPassword.isBlank()) {
                            error = "ALL_FIELDS_REQUIRED"
                            return@BrutalistButton
                        }
                        if (newPassword != confirmPassword) {
                            error = "PASSWORDS_DO_NOT_MATCH"
                            return@BrutalistButton
                        }
                        if (newPassword.length < 4) {
                            error = "MIN_LENGTH_4"
                            return@BrutalistButton
                        }
                        // Verify current password
                        val result = app.vaultManager.unlockVault(currentPassword)
                        if (result.isFailure) {
                            error = "CURRENT_PASSWORD_INCORRECT"
                            return@BrutalistButton
                        }
                        // Change password
                        isUpdating = true
                        scope.launch {
                            val changeResult = app.vaultManager.changePassword(currentPassword, newPassword)
                            if (changeResult.isSuccess) {
                                onSuccess()
                            } else {
                                error = "PASSWORD_CHANGE_FAILED: ${changeResult.exceptionOrNull()?.message}"
                                isUpdating = false
                            }
                        }
                    },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                    enabled = !isUpdating,
                )
            }
        }
    }
}

private fun exportKeys(
    app: LockitApp,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    callback: (android.net.Uri?, String?) -> Unit,
) {
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(
        java.time.LocalDateTime.now(),
    )
    val fileName = "lockit_keys_$timestamp.md"

    scope.launch(Dispatchers.IO) {
        try {
            // Get all credentials synchronously (need to collect from Flow)
            val credentials = app.vaultManager.getAllCredentials()
                .first()

            val content = buildString {
                appendLine("# Lockit Credentials Export")
                appendLine()
                appendLine("**Generated:** ${Instant.now()}")
                appendLine("**Device:** ${android.os.Build.MODEL}")
                appendLine("**Count:** ${credentials.size}")
                appendLine()

                if (credentials.isEmpty()) {
                    appendLine("No credentials found.")
                } else {
                    credentials.forEachIndexed { index, credential ->
                        appendLine("## ${credential.name.uppercase()}")
                        appendLine("- **Service:** ${credential.service}")
                        appendLine("- **Type:** ${credential.type.displayName}")
                        appendLine("- **Key:** ${credential.key}")
                        appendLine("- **Value:** ${credential.value}")
                        appendLine("- **Created:** ${credential.formatUpdatedAt()}")
                        appendLine()
                    }
                }

                appendLine("---")
                appendLine("*Warning: This file contains sensitive credentials. Store securely.*")
            }

            val file = File(context.cacheDir, fileName)
            file.writeText(content)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            withContext(Dispatchers.Main) {
                callback(uri, null)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                callback(null, "EXPORT_FAILED: ${e.message}")
            }
        }
    }
}

private fun exportLogs(
    app: LockitApp,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    callback: (android.net.Uri?, String?) -> Unit,
) {
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(
        java.time.LocalDateTime.now(),
    )
    val fileName = "lockit_logs_$timestamp.md"

    scope.launch(Dispatchers.IO) {
        try {
            val entries = app.auditLogger.getExportableEntries(maxDays = 365)

            val content = buildString {
                appendLine("# Lockit Audit Log Export")
                appendLine()
                appendLine("**Generated:** ${Instant.now()}")
                appendLine("**Device:** ${android.os.Build.MODEL}")
                appendLine("**Entries:** ${entries.size} (last 365 days)")
                appendLine()

                if (entries.isEmpty()) {
                    appendLine("No audit events found in the export window.")
                } else {
                    entries.forEach { entry ->
                        appendLine("- **${entry.timestamp}** | ${entry.action}")
                        appendLine("  - Detail: ${entry.detail}")
                        appendLine("  - Severity: ${entry.severity.name}")
                        appendLine()
                    }
                }

                appendLine("---")
                appendLine("*For complete log history, check the Logs screen in the app.*")
            }

            val file = File(context.cacheDir, fileName)
            file.writeText(content)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            withContext(Dispatchers.Main) {
                callback(uri, null)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                callback(null, "LOGS_EXPORT_FAILED: ${e.message}")
            }
        }
    }
}

/**
 * Upload the encrypted vault database to Google Drive app data folder.
 */
private fun syncToDrive(
    app: LockitApp,
    syncManager: GoogleDriveSyncManager,
    account: com.google.android.gms.auth.api.signin.GoogleSignInAccount,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    callback: (String?) -> Unit,
) {
    val dbPath = context.getDatabasePath("lockit.db").absolutePath
    scope.launch(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                LockitDatabase.closeAndReset(context)
            }
            kotlinx.coroutines.delay(100)
            // Copy WAL journal files too
            val dbDir = context.getDatabasePath("lockit.db").parentFile
            File(dbDir, "lockit.db-wal").copyTo(File(dbDir, "lockit.db-wal.bak"), overwrite = true)
            File(dbDir, "lockit.db-shm").copyTo(File(dbDir, "lockit.db-shm.bak"), overwrite = true)
            val result = syncManager.uploadVault(account, dbPath)
            // Restore journal files
            File(dbDir, "lockit.db-wal.bak").takeIf { it.exists() }?.renameTo(File(dbDir, "lockit.db-wal"))
            File(dbDir, "lockit.db-shm.bak").takeIf { it.exists() }?.renameTo(File(dbDir, "lockit.db-shm"))
            // Reinitialize
            LockitDatabase.getInstance(context)
            withContext(Dispatchers.Main) {
                callback(result.exceptionOrNull()?.message)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                callback("SYNC_ERROR: ${e.message}")
            }
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    items: List<Pair<String, String>> = emptyList(),
    content: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Primary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .height(1.dp)
                    .weight(1f)
                    .background(Primary),
            )
        }

        items.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = Color.Gray,
                )
                Text(
                    text = value,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = IndustrialOrange,
                )
            }
        }

        if (content != null) {
            content()
        }
    }
}

/**
 * Update dialog showing new version info and download button.
 */
@Composable
private fun UpdateDialog(
    release: GitHubRelease,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(2.dp, Color.Black)
                .padding(24.dp),
        ) {
            Text(
                text = "发现新版本 ${release.versionName}",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = IndustrialOrange,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Changelog
            if (release.changelog.isNotBlank()) {
                Text(
                    text = "更新日志",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .border(1.dp, Color.Gray)
                        .padding(8.dp),
                ) {
                    Text(
                        text = release.changelog,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        color = Color.Gray,
                    )
                }
            }

            // Download size
            if (release.downloadSize != null && release.downloadSize > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "大小: ${release.downloadSize / 1024 / 1024} MB",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = Color.Gray,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistButton(
                    text = "稍后",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = "下载更新",
                    onClick = onDownload,
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                    enabled = release.apkUrl != null,
                )
            }
        }
    }
}

/**
 * Dialog for configuring GitHub Token credential source.
 */
@Composable
private fun GitHubTokenConfigDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var newName by remember { mutableStateOf(currentName) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(2.dp, Color.Black)
                .padding(24.dp),
        ) {
            Text(
                text = "配置 GitHub Token",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "输入存储 GitHub Token 的凭据名称。Lockit 将自动读取该凭据的值作为 API 认证令牌（支持私有仓库）。",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = Color.Gray,
                lineHeight = 14.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))

            BrutalistTextField(
                value = newName,
                onValueChange = { newName = it },
                label = "TOKEN_CREDENTIAL_NAME",
                placeholder = "例如: GITHUB_TOKEN",
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistButton(
                    text = "取消",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = "保存",
                    onClick = { onSave(newName) },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
            }
        }
    }
}
