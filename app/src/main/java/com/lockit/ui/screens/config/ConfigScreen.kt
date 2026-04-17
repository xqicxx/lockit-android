package com.lockit.ui.screens.config

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.R
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
import com.lockit.ui.components.parseCredentialFields
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import com.lockit.utils.LocaleHelper
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
    var lastCheckedToken by remember { mutableStateOf<String?>(null) } // Store token for download

    // Dynamic version string for footer
    val versionString = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "LOCKIT_ANDROID v${packageInfo.versionName ?: "Unknown"}"
        } catch (e: Exception) {
            "LOCKIT_ANDROID vUnknown"
        }
    }

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

    if (showChangePinDialog) {
        ChangePasswordDialog(
            app = app,
            onDismiss = { showChangePinDialog = false },
            onSuccess = {
                showChangePinDialog = false
                toastMessage = context.getString(R.string.toast_password_changed)
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
                    appUpdater.downloadApk(apkUrl, lastCheckedToken)
                    val downloadDir = appUpdater.getDownloadDirectory()
                    toastMessage = "${context.getString(R.string.toast_download_started)}\n${context.getString(R.string.toast_download_location)} $downloadDir"
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
                toastMessage = "${context.getString(R.string.toast_token_source_set)} $newName"
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
                title = stringResource(R.string.config_title),
                subtitle = stringResource(R.string.config_subtitle),
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Vault Status Section
            ConfigSection(
                title = stringResource(R.string.config_vault_status),
                items = listOf(
                    stringResource(R.string.config_state) to if (app.vaultManager.isUnlocked()) stringResource(R.string.config_unlocked) else stringResource(R.string.config_locked),
                    stringResource(R.string.config_encryption) to stringResource(R.string.config_aes_gcm),
                    stringResource(R.string.config_key_derivation) to stringResource(R.string.config_argon2id),
                    stringResource(R.string.config_storage) to stringResource(R.string.config_sqlite),
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Actions Section
            ConfigSection(
                title = stringResource(R.string.config_actions),
                content = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrutalistButton(
                            text = stringResource(R.string.config_lock_vault),
                            onClick = {
                                toastMessage = null
                                app.vaultManager.lockVault()
                                onLockVault()
                            },
                            variant = ButtonVariant.Warning,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )
                        BrutalistButton(
                            text = stringResource(R.string.config_change_pin),
                            onClick = { showChangePinDialog = true },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )
                        BrutalistButton(
                            text = stringResource(R.string.config_export_keys),
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
                                        toastMessage = context.getString(R.string.toast_keys_exported)
                                    }
                                }
                            },
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )
                        BrutalistButton(
                            text = stringResource(R.string.config_export_logs),
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
                                        toastMessage = context.getString(R.string.toast_logs_exported)
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
                title = stringResource(R.string.config_cloud_sync),
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
                                    text = stringResource(R.string.config_signed_in),
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
                                        text = "${stringResource(R.string.config_last_sync)} ${lastBackupTime?.take(19) ?: "N/A"}",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 8.sp,
                                        color = Color.Gray,
                                    )
                                }
                            }
                        }

                        // Sync action buttons
                        BrutalistButton(
                            text = if (signedInAccount == null) stringResource(R.string.config_sign_in_google) else stringResource(R.string.config_sync_drive),
                            onClick = {
                                if (signedInAccount == null) {
                                    signInLauncher.launch(syncManager.getSignInIntent())
                                } else {
                                    syncToDrive(app, syncManager, signedInAccount!!, context, scope) { error ->
                                        if (error != null) {
                                            toastMessage = error
                                        } else {
                                            toastMessage = context.getString(R.string.toast_sync_complete)
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
                                text = stringResource(R.string.config_sign_out),
                                onClick = {
                                    syncManager.signOut()
                                    signedInAccount = null
                                    lastBackupTime = null
                                    toastMessage = context.getString(R.string.toast_signed_out)
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
                title = stringResource(R.string.config_security),
                items = listOf(
                    stringResource(R.string.config_salt_length) to stringResource(R.string.config_16_bytes),
                    stringResource(R.string.config_nonce_length) to stringResource(R.string.config_12_bytes),
                    stringResource(R.string.config_gcm_tag) to stringResource(R.string.config_128_bits),
                    stringResource(R.string.config_master_key) to stringResource(R.string.config_256_bits),
                    stringResource(R.string.config_argon2_memory) to stringResource(R.string.config_16_mb),
                    stringResource(R.string.config_argon2_iterations) to stringResource(R.string.config_2_iter),
                    stringResource(R.string.config_argon2_parallelism) to stringResource(R.string.config_1_parallel),
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Language Section
            val currentLanguage = LocaleHelper.getSavedLanguage(context)
            var languageExpanded by remember { mutableStateOf(false) }
            val languageOptions = listOf(
                LocaleHelper.LANG_ZH to stringResource(R.string.config_lang_zh),
                LocaleHelper.LANG_EN to stringResource(R.string.config_lang_en),
            )
            val currentLanguageLabel = languageOptions.find { it.first == currentLanguage }?.second
                ?: stringResource(R.string.config_lang_zh)

            ConfigSection(
                title = stringResource(R.string.config_language),
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.config_language_desc),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Primary)
                                .clickable { languageExpanded = true }
                                .padding(12.dp),
                        ) {
                            Text(
                                text = currentLanguageLabel,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = IndustrialOrange,
                            )
                            DropdownMenu(
                                expanded = languageExpanded,
                                onDismissRequest = { languageExpanded = false },
                                modifier = Modifier.background(White).border(1.dp, Primary),
                            ) {
                                languageOptions.forEach { (langCode, langLabel) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = langLabel,
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 12.sp,
                                                fontWeight = if (langCode == currentLanguage) FontWeight.Bold else FontWeight.Normal,
                                                color = if (langCode == currentLanguage) IndustrialOrange else Color.Gray,
                                            )
                                        },
                                        onClick = {
                                            languageExpanded = false
                                            if (langCode != currentLanguage) {
                                                LocaleHelper.saveLanguage(context, langCode)
                                                (context as? android.app.Activity)?.recreate()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Update Section
            ConfigSection(
                title = stringResource(R.string.config_upgrade),
                content = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Current version info with safe handling
                        val packageInfo = try {
                            context.packageManager.getPackageInfo(context.packageName, 0)
                        } catch (e: Exception) {
                            null
                        }
                        val currentVersion = packageInfo?.versionName ?: "Unknown"
                        val currentVersionCode = packageInfo?.let {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                it.longVersionCode.toInt()
                            } else {
                                @Suppress("DEPRECATION")
                                it.versionCode
                            }
                        } ?: 0
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.config_current_version),
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
                                text = stringResource(R.string.config_token_source),
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
                            text = if (isCheckingUpdate) stringResource(R.string.config_checking) else if (isDownloading) stringResource(R.string.config_downloading) else stringResource(R.string.config_check_update),
                            onClick = {
                                isCheckingUpdate = true
                                scope.launch {
                                    // Check vault is unlocked before reading credentials
                                    if (!app.vaultManager.isUnlocked()) {
                                        toastMessage = context.getString(R.string.toast_vault_locked)
                                        isCheckingUpdate = false
                                        return@launch
                                    }
                                    // Read GitHub Token from vault
                                    val tokenCredential = app.vaultManager.getAllCredentials()
                                        .first()
                                        .find { it.name == githubTokenCredentialName }
                                    // Parse combined value to extract TOKEN_VALUE (index 3)
                                    // For GitHub/Token/ApiKey types, the secret is always at field index 3
                                    val fields = tokenCredential?.value?.let { parseCredentialFields(it) }
                                    val token = fields?.getOrNull(3)?.takeIf { it.isNotBlank() }
                                    lastCheckedToken = token // Store for download
                                    val result = appUpdater.checkForUpdate(currentVersionCode, token)
                                    isCheckingUpdate = false
                                    if (result.isFailure) {
                                        toastMessage = "${context.getString(R.string.toast_check_failed)} ${result.exceptionOrNull()?.message}"
                                    } else if (result.getOrNull() == null) {
                                        toastMessage = context.getString(R.string.toast_already_latest)
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
                    stringResource(R.string.footer_system_info) to IndustrialOrange,
                    versionString to Color.Gray,
                    stringResource(R.string.footer_compatible) to Color.Gray,
                    stringResource(R.string.footer_design) to Color.Gray,
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
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(2.dp, Color.Black)
                .padding(24.dp),
        ) {
            Text(
                text = context.getString(R.string.change_pin_title),
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
                label = context.getString(R.string.change_pin_current),
                placeholder = context.getString(R.string.change_pin_placeholder),
            )
            Spacer(modifier = Modifier.height(8.dp))
            BrutalistTextField(
                value = newPassword,
                onValueChange = { if (it.length <= 4) { newPassword = it; error = null } },
                label = context.getString(R.string.change_pin_new),
                placeholder = context.getString(R.string.change_pin_placeholder),
            )
            Spacer(modifier = Modifier.height(8.dp))
            BrutalistTextField(
                value = confirmPassword,
                onValueChange = { if (it.length <= 4) { confirmPassword = it; error = null } },
                label = context.getString(R.string.change_pin_confirm),
                placeholder = context.getString(R.string.change_pin_placeholder_confirm),
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistButton(
                    text = context.getString(R.string.btn_cancel),
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = if (isUpdating) context.getString(R.string.change_pin_updating) else context.getString(R.string.change_pin_update),
                    onClick = {
                        if (isUpdating) return@BrutalistButton
                        if (currentPassword.isBlank() || newPassword.isBlank() || confirmPassword.isBlank()) {
                            error = context.getString(R.string.change_pin_all_required)
                            return@BrutalistButton
                        }
                        if (newPassword != confirmPassword) {
                            error = context.getString(R.string.change_pin_mismatch)
                            return@BrutalistButton
                        }
                        if (newPassword.length < 4) {
                            error = context.getString(R.string.change_pin_min_length)
                            return@BrutalistButton
                        }
                        // Verify current password
                        val result = app.vaultManager.unlockVault(currentPassword)
                        if (result.isFailure) {
                            error = context.getString(R.string.change_pin_current_wrong)
                            return@BrutalistButton
                        }
                        // Change password
                        isUpdating = true
                        scope.launch {
                            val changeResult = app.vaultManager.changePassword(currentPassword, newPassword)
                            if (changeResult.isSuccess) {
                                onSuccess()
                            } else {
                                error = "${context.getString(R.string.change_pin_failed)} ${changeResult.exceptionOrNull()?.message}"
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
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(2.dp, Color.Black)
                .padding(24.dp),
        ) {
            Text(
                text = "${context.getString(R.string.update_new_version)} ${release.versionName}",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = IndustrialOrange,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Changelog
            if (release.changelog.isNotBlank()) {
                Text(
                    text = context.getString(R.string.update_changelog),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .border(1.dp, Color.Gray)
                        .verticalScroll(rememberScrollState())
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
                    text = "${context.getString(R.string.update_size)} ${release.downloadSize / 1024 / 1024} MB",
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
                    text = context.getString(R.string.update_later),
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = context.getString(R.string.update_download),
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
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(White)
                .border(2.dp, Color.Black)
                .padding(24.dp),
        ) {
            Text(
                text = context.getString(R.string.github_token_title),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = context.getString(R.string.github_token_desc),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = Color.Gray,
                lineHeight = 14.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))

            BrutalistTextField(
                value = newName,
                onValueChange = { newName = it },
                label = context.getString(R.string.github_token_label),
                placeholder = context.getString(R.string.github_token_placeholder),
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistButton(
                    text = context.getString(R.string.btn_cancel),
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = context.getString(R.string.btn_save),
                    onClick = { onSave(newName) },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
            }
        }
    }
}
