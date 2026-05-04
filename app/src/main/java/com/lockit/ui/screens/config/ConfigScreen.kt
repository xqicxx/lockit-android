package com.lockit.ui.screens.config

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.R
import com.lockit.data.database.LockitDatabase
import com.lockit.data.biometric.BiometricPinStorage
import com.lockit.data.sync.CloudBackupCoordinator
import com.lockit.data.sync.CloudBackupMeta
import com.lockit.data.sync.GoogleDriveBackend
import com.lockit.data.sync.SharedPrefsSyncStateStore
import com.lockit.data.sync.vault.JsonVaultPayloadProvider
import com.lockit.data.sync.SyncConflictException
import com.lockit.data.sync.SyncCrypto
import com.lockit.data.sync.SyncKeyManager
import com.lockit.data.sync.SyncOutcome
import com.lockit.data.sync.SyncStatus
import com.lockit.data.sync.VaultSyncEngine
import com.lockit.data.sync.WebDavBackend
import com.lockit.data.updater.AppUpdater
import com.lockit.data.updater.GitHubRelease
import com.lockit.domain.model.Credential
import com.lockit.ui.components.BrutalistButton
import com.lockit.ui.components.BrutalistConfirmDialog
import com.lockit.ui.components.SyncKeySetupPanel
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
import com.lockit.ui.theme.ThemeMode
import com.lockit.ui.theme.ThemePreference
import com.lockit.ui.theme.White
import com.lockit.utils.LocaleHelper
import com.lockit.utils.BiometricUtils
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    fun getActivity(): FragmentActivity? {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            val base = ctx.baseContext
            if (base === ctx) break  // Prevent infinite loop
            ctx = base
        }
        // Fallback: traverse View hierarchy to find Activity
        var v: android.view.View? = view
        while (v != null) {
            val context = v.context
            if (context is FragmentActivity) return context
            v = v.parent as? android.view.View
        }
        return null
    }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var showLinkBiometricDialog by remember { mutableStateOf(false) }
    var showArgon2UpgradeDialog by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }

    // Biometric status
    val biometricStorage = remember { BiometricPinStorage(context.getSharedPreferences("lockit_biometric_prefs", android.content.Context.MODE_PRIVATE)) }
    var isBiometricLinked by remember { mutableStateOf(biometricStorage.isBiometricLinked()) }

    // App update
    val appUpdater = remember { AppUpdater(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<GitHubRelease?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
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

    // Check if vault needs recovery from failed Argon2 upgrade
    // Observe the shared needsRecovery state from LockitApp (set by VaultExplorerViewModel)
    val needsRecovery by app.needsRecovery.collectAsStateWithLifecycle()
    android.util.Log.d("ConfigScreen", "needsRecovery state: $needsRecovery")

    // Google Drive sync
    val syncPrefs = remember { context.getSharedPreferences("lockit_sync", Context.MODE_PRIVATE) }
    val googleKeyManager = remember { SyncKeyManager(syncPrefs) }
    val googleStateStore = remember { SharedPrefsSyncStateStore(syncPrefs) }
    val googleVaultFile = remember { JsonVaultPayloadProvider(app.vaultManager, app.database) }
    val googleDriveBackend = app.googleDriveBackend
    val googleSyncEngine = remember { VaultSyncEngine(googleDriveBackend, googleKeyManager, googleStateStore, googleVaultFile) }
    var signedInAccount by remember { mutableStateOf(googleDriveBackend.getSignedInAccount()) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusMessage by remember { mutableStateOf<String?>(null) }
    var lastBackupTime by remember { mutableStateOf<String?>(null) }
    var googleSyncStatus by remember { mutableStateOf<SyncStatus>(SyncStatus.NotConfigured) }

    // WebDAV sync — shares sync key and state store via same prefs
    val webDavKeyManager = remember { SyncKeyManager(syncPrefs) }
    val webDavStateStore = remember { SharedPrefsSyncStateStore(syncPrefs) }
    val webDavVaultFile = remember { JsonVaultPayloadProvider(app.vaultManager, app.database) }
    val webDavBackend = remember { WebDavBackend(context) }
    val webDavSyncEngine = remember { VaultSyncEngine(webDavBackend, webDavKeyManager, webDavStateStore, webDavVaultFile) }
    var webDavConfigured by remember { mutableStateOf(false) }
    var webDavConfiguring by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var webDavServerUrl by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var webDavBasePath by remember { mutableStateOf("/lockit-sync") }
    var webDavSyncStatus by remember { mutableStateOf<SyncStatus>(SyncStatus.NotConfigured) }

    // Check WebDAV configuration status on init
    LaunchedEffect(Unit) {
        webDavConfigured = webDavBackend.isConfigured()
    }

    // Trigger configure after successful sign-in
    var pendingConfigure by remember { mutableStateOf(false) }

    // Google Sign-In result handler (lateinit to allow self-reference)
    lateinit var signInLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
    signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful && task.result != null) {
            val account = task.result!!
            signedInAccount = account
            if (GoogleDriveBackend.hasRequiredPermissions(account)) {
                pendingConfigure = true
            } else {
                signInLauncher.launch(googleDriveBackend.getSignInIntent())
            }
        } else {
            val ex = task.exception
            if (ex is com.google.android.gms.common.api.ApiException && ex.statusCode == 9001) {
                toastMessage = "DRIVE_PERMISSION_DENIED"
                googleDriveBackend.signOut()
                signedInAccount = null
            } else {
                toastMessage = "GOOGLE_SIGN_IN_FAILED: ${ex?.message}"
            }
        }
    }

    // Execute Drive configure when sign-in completes with permissions
    LaunchedEffect(pendingConfigure) {
        if (!pendingConfigure) return@LaunchedEffect
        pendingConfigure = false
        val account = googleDriveBackend.getSignedInAccount()
        if (!GoogleDriveBackend.hasRequiredPermissions(account)) {
            // Clear local token to force fresh consent with all required scopes
            googleDriveBackend.signOut()
            signedInAccount = null
            signInLauncher.launch(googleDriveBackend.getSignInIntent())
            return@LaunchedEffect
        }
        val cfgResult = googleDriveBackend.configure(emptyMap())
        if (cfgResult.isSuccess) {
            toastMessage = "GOOGLE_SIGNED_IN: ${signedInAccount?.email}"
            googleSyncStatus = googleSyncEngine.getSyncStatus()
        } else {
            val msg = cfgResult.exceptionOrNull()?.message ?: ""
            if (msg.contains("Permission", ignoreCase = true)) {
                googleDriveBackend.signOut()
                signedInAccount = null
                signInLauncher.launch(googleDriveBackend.getSignInIntent())
            } else {
                toastMessage = "DRIVE_INIT_FAILED: $msg"
            }
        }
    }

    // Export share
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {}

    // Load Google Drive sync status when signed in
    LaunchedEffect(signedInAccount) {
        if (signedInAccount != null && !isSyncing) {
            try {
                if (GoogleDriveBackend.shouldConfigureDrive(signedIn = true, driveReady = googleDriveBackend.isDriveReady())) {
                    val cfgResult = googleDriveBackend.configure(emptyMap())
                    if (cfgResult.isFailure) {
                        googleSyncStatus = SyncStatus.Error
                        return@LaunchedEffect
                    }
                }
                googleSyncStatus = googleSyncEngine.getSyncStatus()
                val timeResult = googleDriveBackend.getLastBackupTime()
                lastBackupTime = timeResult.getOrNull()
            } catch (_: Exception) { }
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

    if (showLinkBiometricDialog) {
        LinkBiometricDialog(
            app = app,
            biometricStorage = biometricStorage,
            onDismiss = { showLinkBiometricDialog = false },
            onSuccess = {
                showLinkBiometricDialog = false
                isBiometricLinked = true
                toastMessage = context.getString(R.string.toast_biometric_linked)
            },
            onError = { err ->
                showLinkBiometricDialog = false
                toastMessage = err
            },
        )
    }

    if (showArgon2UpgradeDialog) {
        Argon2UpgradeDialog(
            app = app,
            onDismiss = { showArgon2UpgradeDialog = false },
            onSuccess = {
                showArgon2UpgradeDialog = false
                toastMessage = context.getString(R.string.toast_argon2_upgraded)
                app.setNeedsRecovery(false)
            },
            onError = { err ->
                showArgon2UpgradeDialog = false
                toastMessage = err
            },
        )
    }

    // Recovery dialog for failed Argon2 upgrade
    if (showRecoveryDialog) {
        RecoveryDialog(
            app = app,
            onDismiss = { showRecoveryDialog = false },
            onSuccess = {
                showRecoveryDialog = false
                app.setNeedsRecovery(false)
                toastMessage = context.getString(R.string.toast_recovery_success)
            },
            onError = { err ->
                showRecoveryDialog = false
                toastMessage = err
            },
        )
    }

    // Update dialog - Pre-fetch string resources to avoid context usage in coroutine after UI disposal
    availableUpdate?.let { release ->
        if (showUpdateDialog) {
            val apkUrl = release.apkUrl
            val versionName = release.versionName
            val strDownloadComplete = context.getString(R.string.toast_download_complete)
            val strDownloadFailed = context.getString(R.string.toast_download_failed)
            val strDownloadStarted = context.getString(R.string.toast_download_started)
            val strDownloadLocation = context.getString(R.string.toast_download_location)
            val strNoApkUrl = context.getString(R.string.toast_no_apk_url)
            UpdateDialog(
                release = release,
            onDismiss = { showUpdateDialog = false },
            onDownload = {
                if (apkUrl == null) {
                    toastMessage = strNoApkUrl
                    showUpdateDialog = false
                } else {
                    showUpdateDialog = false
                    isDownloading = true
                    downloadError = null
                    val newDownloadId = appUpdater.downloadApk(apkUrl, versionName, lastCheckedToken)
                    downloadId = newDownloadId
                    val downloadDir = appUpdater.getDownloadDirectory()
                    toastMessage = "$strDownloadStarted\n$strDownloadLocation $downloadDir"
                    scope.launch {
                        var status = DownloadManager.STATUS_PENDING
                        while (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING) {
                            kotlinx.coroutines.delay(2000)
                            status = appUpdater.getDownloadStatus(newDownloadId)
                        }
                        isDownloading = false
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                toastMessage = strDownloadComplete
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val errorReason = appUpdater.getDownloadErrorReason(newDownloadId)
                                val errorMsg = when (errorReason) {
                                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient storage space"
                                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
                                    else -> "Download failed (error $errorReason)"
                                }
                                downloadError = errorMsg
                                toastMessage = "$strDownloadFailed: $errorMsg"
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                downloadError = "Download paused (network issue)"
                                toastMessage = "$strDownloadFailed: Network connection lost"
                            }
                        }
                    }
                }
            },
            )
        }
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
                title = stringResource(R.string.config_title),
                subtitle = stringResource(R.string.config_subtitle),
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Vault Status Section - Dynamic values from code constants
            val cryptoConstants = app.vaultManager.getCryptoConstants()
            ConfigSection(
                title = stringResource(R.string.config_vault_status),
                items = listOf(
                    stringResource(R.string.config_state) to if (app.vaultManager.isUnlocked()) stringResource(R.string.config_unlocked) else stringResource(R.string.config_locked),
                    stringResource(R.string.config_encryption) to cryptoConstants.ENCRYPTION_ALGORITHM,
                    stringResource(R.string.config_key_derivation) to cryptoConstants.KEY_DERIVATION_ALGORITHM,
                    stringResource(R.string.config_storage) to cryptoConstants.STORAGE_TYPE,
                ),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Biometric Status Section
            ConfigSection(
                title = stringResource(R.string.config_biometric_status),
                items = listOf(
                    stringResource(R.string.config_biometric_linked) to if (isBiometricLinked) stringResource(R.string.config_yes) else stringResource(R.string.config_no),
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
                        // Biometric button - show link or unlink based on state
                        if (isBiometricLinked) {
                            BrutalistButton(
                                text = stringResource(R.string.config_unlink_biometric),
                                onClick = {
                                    // Use unlink() to only remove encrypted PIN, preserve prompt tracker
                                    biometricStorage.unlink()
                                    isBiometricLinked = false
                                    toastMessage = context.getString(R.string.toast_biometric_unlinked)
                                },
                                variant = ButtonVariant.Danger,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                            )
                        } else {
                            BrutalistButton(
                                text = stringResource(R.string.config_link_biometric),
                                onClick = {
                                    showLinkBiometricDialog = true
                                },
                                variant = ButtonVariant.Primary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                            )
                        }
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

            // Sync Key Section
            ConfigSection(
                title = stringResource(R.string.config_sync_key_title),
                content = {
                    SyncKeySetupPanel(
                        hasSyncKey = googleSyncEngine.hasSyncKey(),
                        onGenerate = {
                            val key = SyncCrypto.generateSyncKey()
                            googleSyncEngine.setSyncKey(SyncCrypto.encodeSyncKey(key))
                            toastMessage = context.getString(R.string.toast_sync_key_generated)
                            "" // return value unused
                        },
                        onImport = { pastedKey ->
                            try {
                                googleSyncEngine.setSyncKey(pastedKey)
                                toastMessage = context.getString(R.string.toast_sync_key_saved)
                                true
                            } catch (_: Exception) {
                                toastMessage = context.getString(R.string.toast_sync_key_invalid)
                                false
                            }
                        },
                        onCopy = { googleSyncEngine.getSyncKeyEncoded() },
                    )
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

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
                                    .border(1.dp, MaterialTheme.colorScheme.outline)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.config_signed_in),
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // Sync status display
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${stringResource(R.string.config_sync_status)}: ${syncStatusLabel(googleSyncStatus)}",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (googleSyncStatus) {
                                    SyncStatus.UpToDate -> IndustrialOrange
                                    SyncStatus.Conflict, SyncStatus.Error -> TacticalRed
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }

                        // Refresh status button
                        BrutalistButton(
                            text = stringResource(R.string.config_sync_refresh_status),
                            onClick = {
                                scope.launch {
                                    try {
                                        googleSyncStatus = googleSyncEngine.getSyncStatus()
                                    } catch (_: Exception) {
                                        googleSyncStatus = SyncStatus.Error
                                    }
                                }
                            },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )

                        // Sign in / Push / Pull buttons
                        if (!GoogleDriveBackend.hasRequiredPermissions(signedInAccount)) {
                            BrutalistButton(
                                text = stringResource(R.string.config_sign_in_google),
                                onClick = {
                                    signInLauncher.launch(googleDriveBackend.getSignInIntent())
                                },
                                variant = ButtonVariant.Primary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                            )
                        } else {
                            val canSync = googleSyncEngine.hasSyncKey()
                            var syncOutcome by remember { mutableStateOf<SyncOutcome?>(null) }
                            var showCloudBackupList by remember { mutableStateOf(false) }
                            var cloudBackupList by remember { mutableStateOf<List<CloudBackupMeta>>(emptyList()) }
                            var cloudBackupLoading by remember { mutableStateOf(false) }
                            var showCloudRestoreConfirm by remember { mutableStateOf<CloudBackupMeta?>(null) }
                            val cloudBackupCoordinator = remember {
                                CloudBackupCoordinator(
                                    syncKeyProvider = { googleSyncEngine.getSyncKeyEncoded() },
                                    vaultFile = googleVaultFile,
                                )
                            }
                            val ensureDriveReady: suspend () -> Boolean = {
                                if (signedInAccount == null) {
                                    false
                                } else if (!GoogleDriveBackend.shouldConfigureDrive(signedIn = true, driveReady = googleDriveBackend.isDriveReady())) {
                                    true
                                } else {
                                    val cfgResult = googleDriveBackend.configure(emptyMap())
                                    if (cfgResult.isFailure) {
                                        val msg = cfgResult.exceptionOrNull()?.message ?: ""
                                        if (msg.contains("Permission", ignoreCase = true)) {
                                            googleDriveBackend.signOut()
                                            signedInAccount = null
                                            signInLauncher.launch(googleDriveBackend.getSignInIntent())
                                        } else {
                                            toastMessage = "Drive init failed: $msg"
                                        }
                                        false
                                    } else {
                                        true
                                    }
                                }
                            }
                            val refreshCloudBackupList: suspend () -> Unit = {
                                cloudBackupLoading = true
                                googleDriveBackend.listBackups()
                                    .onSuccess { backups ->
                                        cloudBackupList = backups.sortedByDescending { it.timestamp }
                                        showCloudBackupList = true
                                    }
                                    .onFailure { toastMessage = "Cloud backup list failed: ${it.message}" }
                                cloudBackupLoading = false
                            }
                            val uploadCloudVersion: suspend () -> Unit = {
                                cloudBackupCoordinator.uploadCurrentVault(googleDriveBackend)
                                    .onFailure { toastMessage = "Cloud backup failed: ${it.message}" }
                            }
                            // SYNC button
                            BrutalistButton(
                                text = "SYNC",
                                onClick = {
                                    isSyncing = true
                                    syncOutcome = null
                                    scope.launch {
                                        if (!ensureDriveReady()) {
                                            isSyncing = false
                                            googleSyncStatus = SyncStatus.Error
                                            return@launch
                                        }
                                        val result = googleSyncEngine.sync()
                                        isSyncing = false
                                        if (result.isSuccess) {
                                            syncOutcome = result.getOrNull()
                                            uploadCloudVersion()
                                            googleSyncStatus = googleSyncEngine.getSyncStatus()
                                        } else {
                                            val err = result.exceptionOrNull()
                                            syncOutcome = SyncOutcome.Error
                                            if (err is SyncConflictException) {
                                                toastMessage = context.getString(R.string.toast_sync_conflict)
                                                googleSyncStatus = SyncStatus.Conflict
                                            } else {
                                                toastMessage = "${context.getString(R.string.toast_sync_error)} ${err?.message}"
                                                googleSyncStatus = SyncStatus.Error
                                            }
                                        }
                                    }
                                },
                                variant = ButtonVariant.Primary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                                enabled = !isSyncing && canSync,
                            )
                            syncOutcome?.let { outcome ->
                                Text(
                                    text = when (outcome) {
                                        SyncOutcome.AlreadyUpToDate -> "Already up to date"
                                        SyncOutcome.Pushed -> "Pushed to cloud"
                                        SyncOutcome.Pulled -> "Pulled from cloud"
                                        SyncOutcome.LocalWon -> "Conflict -> kept local"
                                        SyncOutcome.CloudWon -> "Conflict -> kept cloud"
                                        SyncOutcome.Error -> "Sync failed — check toast"
                                    },
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 9.sp,
                                    color = when (outcome) {
                                        SyncOutcome.Error -> TacticalRed
                                        else -> IndustrialOrange
                                    },
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            // Push button
                            BrutalistButton(
                                text = stringResource(R.string.config_sync_push),
                                onClick = {
                                    isSyncing = true
                                    scope.launch {
                                        if (!ensureDriveReady()) {
                                            isSyncing = false
                                            googleSyncStatus = SyncStatus.Error
                                            return@launch
                                        }
                                        val result = googleSyncEngine.push()
                                        isSyncing = false
                                        if (result.isSuccess) {
                                            uploadCloudVersion()
                                            toastMessage = context.getString(R.string.toast_sync_push_complete)
                                            googleSyncStatus = googleSyncEngine.getSyncStatus()
                                        } else {
                                            val err = result.exceptionOrNull()
                                            if (err is com.lockit.data.sync.SyncConflictException) {
                                                toastMessage = context.getString(R.string.toast_sync_conflict)
                                                googleSyncStatus = SyncStatus.Conflict
                                            } else {
                                                toastMessage = "${context.getString(R.string.toast_sync_error)} ${err?.message}"
                                                googleSyncStatus = SyncStatus.Error
                                            }
                                        }
                                    }
                                },
                                variant = ButtonVariant.Primary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                                enabled = !isSyncing && canSync,
                            )
                            // Pull button
                            BrutalistButton(
                                text = stringResource(R.string.config_sync_pull),
                                onClick = {
                                    isSyncing = true
                                    scope.launch {
                                        if (!ensureDriveReady()) {
                                            isSyncing = false
                                            googleSyncStatus = SyncStatus.Error
                                            return@launch
                                        }
                                        val result = googleSyncEngine.pull()
                                        isSyncing = false
                                        if (result.isSuccess) {
                                            toastMessage = context.getString(R.string.toast_sync_pull_complete)
                                            googleSyncStatus = googleSyncEngine.getSyncStatus()
                                        } else {
                                            val err = result.exceptionOrNull()
                                            if (err is com.lockit.data.sync.SyncConflictException) {
                                                toastMessage = context.getString(R.string.toast_sync_conflict)
                                                googleSyncStatus = SyncStatus.Conflict
                                            } else {
                                                toastMessage = "${context.getString(R.string.toast_sync_error)} ${err?.message}"
                                                googleSyncStatus = SyncStatus.Error
                                            }
                                        }
                                    }
                                },
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                                enabled = !isSyncing && canSync,
                            )
                            BrutalistButton(
                                text = if (showCloudBackupList) "HIDE CLOUD VERSIONS" else "RESTORE CLOUD VERSION",
                                onClick = {
                                    scope.launch {
                                        if (showCloudBackupList) {
                                            showCloudBackupList = false
                                            return@launch
                                        }
                                        if (!ensureDriveReady()) {
                                            googleSyncStatus = SyncStatus.Error
                                            return@launch
                                        }
                                        refreshCloudBackupList()
                                    }
                                },
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                                enabled = !isSyncing && canSync && !cloudBackupLoading,
                            )
                            if (showCloudBackupList) {
                                if (cloudBackupList.isEmpty()) {
                                    Text(
                                        "No cloud versions found.",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    val cloudDtf = remember {
                                        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                                            .withZone(java.time.ZoneId.systemDefault())
                                    }
                                    cloudBackupList.take(10).forEach { meta ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(1.dp, MaterialTheme.colorScheme.outline)
                                                .clickable { showCloudRestoreConfirm = meta }
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(cloudDtf.format(meta.timestamp), fontFamily = JetBrainsMonoFamily, fontSize = 10.sp)
                                            Text(
                                                "${meta.sizeBytes / 1024} KB",
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }
                                }
                            }
                            showCloudRestoreConfirm?.let { meta ->
                                val cloudDtf = remember {
                                    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                                        .withZone(java.time.ZoneId.systemDefault())
                                }
                                BrutalistConfirmDialog(
                                    title = "Restore Cloud Version",
                                    message = "Restore cloud version ${cloudDtf.format(meta.timestamp)}?\n\nYour current vault will be replaced.",
                                    confirmText = "RESTORE",
                                    onConfirm = {
                                        scope.launch {
                                            if (!ensureDriveReady()) {
                                                googleSyncStatus = SyncStatus.Error
                                                showCloudRestoreConfirm = null
                                                return@launch
                                            }
                                            cloudBackupCoordinator.restoreBackup(googleDriveBackend, meta.id)
                                                .onSuccess {
                                                    googleStateStore.clear()
                                                    googleSyncStatus = googleSyncEngine.getSyncStatus()
                                                    toastMessage = "Restored cloud version ${cloudDtf.format(meta.timestamp)}"
                                                    showCloudBackupList = false
                                                }
                                                .onFailure { toastMessage = "Cloud restore failed: ${it.message}" }
                                            showCloudRestoreConfirm = null
                                        }
                                    },
                                    onDismiss = { showCloudRestoreConfirm = null },
                                )
                            }
                            // Sign out
                            BrutalistButton(
                                text = stringResource(R.string.config_sign_out),
                                onClick = {
                                    googleDriveBackend.signOut()
                                    signedInAccount = null
                                    lastBackupTime = null
                                    googleSyncStatus = SyncStatus.NotConfigured
                                    toastMessage = context.getString(R.string.toast_signed_out)
                                },
                                variant = ButtonVariant.Warning,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                            )
                        }

                        // Sync status message
                        syncStatusMessage?.let { status ->
                            Text(
                                text = status,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                                color = if (status.contains("ERROR")) TacticalRed else IndustrialOrange,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                    }
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // WebDAV Sync Section
            ConfigSection(
                title = stringResource(R.string.config_webdav),
                content = {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // WebDAV status
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (webDavConfigured) stringResource(R.string.config_webdav_connected) else stringResource(R.string.config_webdav_not_configured),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (webDavConfigured) IndustrialOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // WebDAV configure button
                        BrutalistButton(
                            text = stringResource(R.string.config_webdav_configure),
                            onClick = { showWebDavDialog = true },
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )

                        if (webDavConfigured) {
                            // Sync status display
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outline)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${stringResource(R.string.config_sync_status)}: ${syncStatusLabel(webDavSyncStatus)}",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (webDavSyncStatus) {
                                        SyncStatus.UpToDate -> IndustrialOrange
                                        SyncStatus.Conflict, SyncStatus.Error -> TacticalRed
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }

                            // Refresh status
                            BrutalistButton(
                                text = stringResource(R.string.config_sync_refresh_status),
                                onClick = {
                                    scope.launch {
                                        try {
                                            webDavSyncStatus = webDavSyncEngine.getSyncStatus()
                                        } catch (_: Exception) {
                                            webDavSyncStatus = SyncStatus.Error
                                        }
                                    }
                                },
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                            )

                            val canSync = webDavSyncEngine.hasSyncKey()
                            // Push button
                            BrutalistButton(
                                text = stringResource(R.string.config_sync_push),
                                onClick = {
                                    isSyncing = true
                                    scope.launch {
                                        val result = webDavSyncEngine.push()
                                        isSyncing = false
                                        if (result.isSuccess) {
                                            toastMessage = context.getString(R.string.toast_sync_push_complete)
                                            webDavSyncStatus = webDavSyncEngine.getSyncStatus()
                                        } else {
                                            val err = result.exceptionOrNull()
                                            if (err is SyncConflictException) {
                                                toastMessage = context.getString(R.string.toast_sync_conflict)
                                                webDavSyncStatus = SyncStatus.Conflict
                                            } else {
                                                toastMessage = "${context.getString(R.string.toast_sync_error)} ${err?.message}"
                                                webDavSyncStatus = SyncStatus.Error
                                            }
                                        }
                                    }
                                },
                                variant = ButtonVariant.Primary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                                enabled = !isSyncing && canSync,
                            )
                            // Pull button
                            BrutalistButton(
                                text = stringResource(R.string.config_sync_pull),
                                onClick = {
                                    isSyncing = true
                                    scope.launch {
                                        val result = webDavSyncEngine.pull()
                                        isSyncing = false
                                        if (result.isSuccess) {
                                            toastMessage = context.getString(R.string.toast_sync_pull_complete)
                                            webDavSyncStatus = webDavSyncEngine.getSyncStatus()
                                        } else {
                                            val err = result.exceptionOrNull()
                                            if (err is SyncConflictException) {
                                                toastMessage = context.getString(R.string.toast_sync_conflict)
                                                webDavSyncStatus = SyncStatus.Conflict
                                            } else {
                                                toastMessage = "${context.getString(R.string.toast_sync_error)} ${err?.message}"
                                                webDavSyncStatus = SyncStatus.Error
                                            }
                                        }
                                    }
                                },
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                                enabled = !isSyncing && canSync,
                            )
                            // Clear config
                            BrutalistButton(
                                text = stringResource(R.string.config_webdav_clear),
                                onClick = {
                                    // Clear WebDAV configuration
                                    webDavBackend.clearConfig()
                                    webDavConfigured = false
                                    webDavSyncStatus = SyncStatus.NotConfigured
                                    // Reset sensitive UI state variables
                                    webDavPassword = ""
                                    webDavServerUrl = ""
                                    webDavUsername = ""
                                    webDavBasePath = "/lockit-sync"
                                    toastMessage = context.getString(R.string.toast_signed_out)
                                },
                                variant = ButtonVariant.Warning,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                            )
                        }
                    }
                },
            )

            // WebDAV Configuration Dialog
            if (showWebDavDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorScheme.surface.copy(alpha = 0.8f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(2.dp, colorScheme.outlineVariant.copy(alpha = 0.4f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.config_webdav_configure),
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = IndustrialOrange,
                        )
                        BrutalistTextField(
                            value = webDavServerUrl,
                            onValueChange = { webDavServerUrl = it },
                            label = stringResource(R.string.config_webdav_server_url),
                            placeholder = "https://dav.jianguoyun.com/dav/",
                        )
                        BrutalistTextField(
                            value = webDavUsername,
                            onValueChange = { webDavUsername = it },
                            label = stringResource(R.string.config_webdav_username),
                            placeholder = "user@example.com",
                        )
                        BrutalistTextField(
                            value = webDavPassword,
                            onValueChange = { webDavPassword = it },
                            label = stringResource(R.string.config_webdav_password),
                            placeholder = "app_password",
                            isPassword = true,
                        )
                        BrutalistTextField(
                            value = webDavBasePath,
                            onValueChange = { webDavBasePath = it },
                            label = stringResource(R.string.config_webdav_base_path),
                            placeholder = "/lockit-sync",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BrutalistButton(
                                text = stringResource(R.string.btn_cancel),
                                onClick = { showWebDavDialog = false },
                                variant = ButtonVariant.Secondary,
                                modifier = Modifier.weight(1f),
                                useMonoFont = true,
                            )
                            BrutalistButton(
                                text = stringResource(R.string.config_webdav_save),
                                onClick = {
                                    if (webDavConfiguring) return@BrutalistButton  // Prevent concurrent attempts
                                    webDavConfiguring = true
                                    scope.launch {
                                        val result = webDavBackend.configure(mapOf(
                                            "serverUrl" to webDavServerUrl,
                                            "username" to webDavUsername,
                                            "password" to webDavPassword,
                                            "basePath" to webDavBasePath,
                                        ))
                                        webDavConfiguring = false
                                        if (result.isSuccess) {
                                            webDavConfigured = true
                                            showWebDavDialog = false
                                            toastMessage = context.getString(R.string.toast_webdav_configured)
                                        } else {
                                            toastMessage = "${context.getString(R.string.toast_webdav_error)} ${result.exceptionOrNull()?.message}"
                                        }
                                    }
                                },
                                variant = ButtonVariant.Primary,
                                modifier = Modifier.weight(1f),
                                useMonoFont = true,
                                enabled = !webDavConfiguring,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Security Section - All values from code constants (dynamic)
            val argon2Params = app.vaultManager.getArgon2ParamsInfo()
            val (argon2MemoryKB, argon2Iterations, argon2Parallelism) = argon2Params

            ConfigSection(
                title = stringResource(R.string.config_security),
                items = listOf(
                    stringResource(R.string.config_salt_length) to "${cryptoConstants.SALT_LENGTH}_BYTES",
                    stringResource(R.string.config_nonce_length) to "${cryptoConstants.NONCE_LENGTH}_BYTES",
                    stringResource(R.string.config_gcm_tag) to "${cryptoConstants.GCM_TAG_LENGTH}_BITS",
                    stringResource(R.string.config_master_key) to "${cryptoConstants.KEY_LENGTH * 8}_BITS",
                    stringResource(R.string.config_argon2_memory) to "${argon2MemoryKB / 1024}MB",
                    stringResource(R.string.config_argon2_iterations) to argon2Iterations.toString(),
                    stringResource(R.string.config_argon2_parallelism) to argon2Parallelism.toString(),
                ),
            )

            // Argon2 Upgrade Section - Show if legacy params detected
            if (app.vaultManager.needsArgon2Upgrade()) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(IndustrialOrange.copy(alpha = 0.1f))
                        .border(1.dp, IndustrialOrange)
                        .padding(12.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.config_argon2_upgrade_title),
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = IndustrialOrange,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.config_argon2_upgrade_desc),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BrutalistButton(
                            text = stringResource(R.string.config_argon2_upgrade_btn),
                            onClick = { showArgon2UpgradeDialog = true },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                            useMonoFont = true,
                        )

                        // Recovery button - shown if vault needs recovery
                        if (needsRecovery) {
                            Spacer(modifier = Modifier.height(8.dp))
                            BrutalistButton(
                                text = stringResource(R.string.config_recovery_btn),
                                onClick = { showRecoveryDialog = true },
                                variant = ButtonVariant.Danger,
                                modifier = Modifier.fillMaxWidth(),
                                useMonoFont = true,
                            )
                        }
                    }
                }
            }

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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.4f))
                                .clickable { languageExpanded = true }
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = currentLanguageLabel,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = IndustrialOrange,
                                )
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = "Dropdown",
                                    tint = IndustrialOrange,
                                )
                            }
                            DropdownMenu(
                                expanded = languageExpanded,
                                onDismissRequest = { languageExpanded = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.background).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            ) {
                                languageOptions.forEach { (langCode, langLabel) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = langLabel,
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 12.sp,
                                                fontWeight = if (langCode == currentLanguage) FontWeight.Bold else FontWeight.Normal,
                                                color = if (langCode == currentLanguage) IndustrialOrange else MaterialTheme.colorScheme.onSurfaceVariant,
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

            // Theme Section
            val currentThemeMode = ThemePreference.getThemeMode(context)
            var themeExpanded by remember { mutableStateOf(false) }
            val themeOptions = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.config_theme_system),
                ThemeMode.LIGHT to stringResource(R.string.config_theme_light),
                ThemeMode.DARK to stringResource(R.string.config_theme_dark),
            )
            val currentThemeLabel = themeOptions.find { it.first == currentThemeMode }?.second
                ?: stringResource(R.string.config_theme_system)

            ConfigSection(
                title = stringResource(R.string.config_theme),
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.config_theme_desc),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.4f))
                                .clickable { themeExpanded = true }
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = currentThemeLabel,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = IndustrialOrange,
                                )
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = "Dropdown",
                                    tint = IndustrialOrange,
                                )
                            }
                            DropdownMenu(
                                expanded = themeExpanded,
                                onDismissRequest = { themeExpanded = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.background).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            ) {
                                themeOptions.forEach { (mode, modeLabel) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = modeLabel,
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 12.sp,
                                                fontWeight = if (mode == currentThemeMode) FontWeight.Bold else FontWeight.Normal,
                                                color = if (mode == currentThemeMode) IndustrialOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        onClick = {
                                            themeExpanded = false
                                            if (mode != currentThemeMode) {
                                                ThemePreference.setThemeMode(context, mode)
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    try {
                                        // Use Dispatchers.IO to move CPU-intensive decryption off main thread
                                        // Track if there was a critical error during credential fetch
                                        var credentialFetchError: Boolean = false
                                        val credentials: List<Credential> = withContext(Dispatchers.IO) {
                                            // Room Flow is infinite - use withTimeoutOrNull to prevent indefinite hang
                                            // if Flow doesn't emit within 5 seconds
                                            try {
                                                withTimeoutOrNull(5000) {
                                                    app.vaultManager.getAllCredentials().firstOrNull()
                                                } ?: emptyList()  // Timeout returns null, default to empty
                                            } catch (e: CancellationException) {
                                                throw e  // Re-throw to maintain coroutine cancellation
                                            } catch (e: IllegalStateException) {
                                                // Vault locked - will be handled by vault state check below
                                                emptyList()
                                            } catch (e: Exception) {
                                                // Critical error (corruption, decryption failure) when vault should be unlocked
                                                credentialFetchError = true
                                                emptyList()
                                            }
                                        }
                                        // Check for critical errors first - vault unlocked but fetch failed
                                        if (credentialFetchError && app.vaultManager.isUnlocked()) {
                                            toastMessage = context.getString(R.string.toast_credential_error)
                                        } else if (!app.vaultManager.isUnlocked()) {
                                            toastMessage = context.getString(R.string.toast_vault_locked)
                                        } else {
                                            // Proceed with update check (token will be null for empty vault)
                                            val tokenCredential = credentials.find { it.name == githubTokenCredentialName }

                                            // Token is optional - public repos work without it
                                            // Show diagnostic toast if configured token has issues (non-blocking)
                                            var tokenDiagnostic: String? = null
                                            val token: String? = if (tokenCredential != null) {
                                                val fields = tokenCredential.value?.let { parseCredentialFields(it) }
                                                val parsedToken = fields?.getOrNull(3)?.takeIf { it.isNotBlank() }
                                                if (parsedToken == null) {
                                                    // Credential exists but token field is empty/blank
                                                    tokenDiagnostic = context.getString(R.string.toast_token_empty)
                                                }
                                                parsedToken
                                            } else {
                                                // Token credential not found in vault - OK for public repos
                                                if (credentials.isNotEmpty()) {
                                                    tokenDiagnostic = context.getString(R.string.toast_token_not_found)
                                                }
                                                null
                                            }

                                            // Show diagnostic toast if token has issues (non-blocking, just info)
                                            if (tokenDiagnostic != null) {
                                                Toast.makeText(context, tokenDiagnostic, Toast.LENGTH_SHORT).show()
                                            }

                                            lastCheckedToken = token // Store for download (null if public repo)
                                            val result = appUpdater.checkForUpdate(currentVersionCode, token)
                                    if (result.isFailure) {
                                        val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                                        // AppUpdater returns descriptive messages for common errors:
                                        // - 403: "GitHub API rate limit exceeded..."
                                        // - 404: "Release not found" (could mean private repo permission issue if token used)
                                        // - Other HTTP codes: "HTTP X"
                                        // Since we only reach here with a valid token, 404 likely means permission issue
                                        val detailedMessage = when {
                                            errorMsg.contains("rate limit", ignoreCase = true) -> context.getString(R.string.toast_rate_limit)
                                            errorMsg.contains("Release not found", ignoreCase = true) -> {
                                                // If token was used, likely permission issue; otherwise just no release
                                                if (token != null) {
                                                    "${context.getString(R.string.toast_private_repo_denied)} (Token needs 'repo' scope)"
                                                } else {
                                                    context.getString(R.string.toast_release_not_found)
                                                }
                                            }
                                            else -> "${context.getString(R.string.toast_check_failed)} $errorMsg"
                                            }
                                            toastMessage = detailedMessage
                                        } else if (result.getOrNull() == null) {
                                            toastMessage = context.getString(R.string.toast_already_latest)
                                        } else {
                                            availableUpdate = result.getOrNull()
                                            showUpdateDialog = true
                                        }
                                        }
                                    } finally {
                                        isCheckingUpdate = false  // Always reset loading state
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
                    versionString to MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.footer_compatible) to MaterialTheme.colorScheme.onSurfaceVariant,
                    stringResource(R.string.footer_design) to MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * Dialog to upgrade Argon2 parameters to OWASP recommended values.
 */
@Composable
private fun Argon2UpgradeDialog(
    app: LockitApp,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isUpgrading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, IndustrialOrange)
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.config_argon2_upgrade_title),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = IndustrialOrange,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.config_argon2_upgrade_dialog_desc),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                value = pin,
                onValueChange = { if (it.length <= 4) { pin = it; error = null } },
                label = stringResource(R.string.change_pin_current),
                placeholder = stringResource(R.string.change_pin_placeholder),
                isPassword = true,
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistButton(
                    text = stringResource(R.string.btn_cancel),
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = if (isUpgrading) context.getString(R.string.config_upgrading) else context.getString(R.string.config_argon2_upgrade_btn),
                    onClick = {
                        if (isUpgrading) return@BrutalistButton
                        if (pin.length < 4) {
                            error = context.getString(R.string.error_pin_too_short)
                            return@BrutalistButton
                        }

                        val pinToVerify = pin
                        isUpgrading = true

                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                app.vaultManager.upgradeArgon2Params(pinToVerify)
                            }
                            if (result.isSuccess) {
                                isUpgrading = false
                                onSuccess()
                            } else {
                                isUpgrading = false
                                val errorMsg = result.exceptionOrNull()?.message ?: "UPGRADE_FAILED"
                                error = when (errorMsg) {
                                    "WRONG_PIN" -> context.getString(R.string.error_wrong_pin)
                                    "Vault must be unlocked before upgrade" -> context.getString(R.string.config_locked)
                                    else -> errorMsg
                                }
                            }
                        }
                    },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                    enabled = !isUpgrading,
                )
            }
        }
    }
}

/**
 * Dialog to recover vault from failed Argon2 upgrade.
 * Uses legacy params to derive key and re-encrypt credentials with OWASP params.
 */
@Composable
private fun RecoveryDialog(
    app: LockitApp,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isRecovering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, TacticalRed)
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.config_recovery_title),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = TacticalRed,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.config_recovery_desc),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                value = pin,
                onValueChange = { if (it.length <= 4) { pin = it; error = null } },
                label = stringResource(R.string.change_pin_current),
                placeholder = stringResource(R.string.change_pin_placeholder),
                isPassword = true,
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistButton(
                    text = stringResource(R.string.btn_cancel),
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = if (isRecovering) context.getString(R.string.config_recovering) else context.getString(R.string.config_recovery_btn),
                    onClick = {
                        if (isRecovering) return@BrutalistButton
                        if (pin.length < 4) {
                            error = context.getString(R.string.error_pin_too_short)
                            return@BrutalistButton
                        }

                        val pinToVerify = pin
                        isRecovering = true

                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                app.vaultManager.recoverFromFailedUpgrade(pinToVerify)
                            }
                            if (result.isSuccess) {
                                isRecovering = false
                                onSuccess()
                            } else {
                                isRecovering = false
                                val errorMsg = result.exceptionOrNull()?.message ?: "RECOVERY_FAILED"
                                error = when (errorMsg) {
                                    "WRONG_PIN" -> context.getString(R.string.error_wrong_pin)
                                    else -> errorMsg
                                }
                            }
                        }
                    },
                    variant = ButtonVariant.Danger,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                    enabled = !isRecovering,
                )
            }
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    app: LockitApp,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
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
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, colorScheme.outlineVariant.copy(alpha = 0.4f))
                .padding(24.dp),
        ) {
            Text(
                text = context.getString(R.string.change_pin_title),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun LinkBiometricDialog(
    app: LockitApp,
    biometricStorage: BiometricPinStorage,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current

    fun getActivity(): FragmentActivity? {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is FragmentActivity) return ctx
            val base = ctx.baseContext
            if (base === ctx) break  // Prevent infinite loop
            ctx = base
        }
        // Fallback: traverse View hierarchy to find Activity
        var v: android.view.View? = view
        while (v != null) {
            val context = v.context
            if (context is FragmentActivity) return context
            v = v.parent as? android.view.View
        }
        return null
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, colorScheme.outlineVariant.copy(alpha = 0.4f))
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.config_link_biometric),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.config_link_biometric_desc),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                value = pin,
                onValueChange = { if (it.length <= 4) { pin = it; error = null } },
                label = stringResource(R.string.change_pin_current),
                placeholder = stringResource(R.string.change_pin_placeholder),
                isPassword = true,
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistButton(
                    text = stringResource(R.string.btn_cancel),
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                )
                BrutalistButton(
                    text = if (isLinking) "LINKING..." else "LINK",
                    onClick = {
                        if (isLinking) return@BrutalistButton
                        if (pin.length < 4) {
                            error = context.getString(R.string.error_pin_too_short)
                            return@BrutalistButton
                        }

                        // Capture PIN value BEFORE async verification to prevent race condition
                        // If user modifies input during async operation, we verify/store the original value
                        val pinToVerify = pin

                        // Set linking state immediately to prevent race condition (multiple clicks)
                        isLinking = true

                        // First verify PIN is correct
                        scope.launch {
                            val verifyResult = withContext(Dispatchers.IO) {
                                app.vaultManager.unlockVault(pinToVerify)
                            }
                            if (verifyResult.isFailure) {
                                isLinking = false
                                error = context.getString(R.string.error_wrong_pin)
                                return@launch
                            }

                            // PIN verified, now link biometric
                            val activity = getActivity()
                            if (activity == null) {
                                isLinking = false
                                error = "ACTIVITY_NOT_AVAILABLE"
                                return@launch
                            }

                            // Use stricter check matching BiometricPinStorage requirement (BIOMETRIC_STRONG only)
                            // BiometricUtils allows DEVICE_CREDENTIAL fallback which won't work with hardware-backed keys
                            if (!biometricStorage.canAuthenticate(activity)) {
                                isLinking = false
                                error = context.getString(R.string.error_biometric_not_available)
                                return@launch
                            }

                            // Use the captured PIN value, NOT the mutable pin state
                            biometricStorage.storePin(
                                activity = activity,
                                pin = pinToVerify,
                                title = context.getString(R.string.biometric_link_pin_title),
                                subtitle = context.getString(R.string.biometric_link_pin_subtitle),
                                onSuccess = {
                                    isLinking = false
                                    onSuccess()
                                },
                                onError = { err ->
                                    isLinking = false
                                    error = err
                                },
                            )
                        }
                    },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                    enabled = !isLinking,
                )
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
/**
 * Map SyncStatus enum to human-readable label.
 */
private fun syncStatusLabel(status: SyncStatus): String {
    return when (status) {
        SyncStatus.NotConfigured -> "NO_SYNC_KEY"
        SyncStatus.NeverSynced -> "NEVER_SYNCED"
        SyncStatus.UpToDate -> "UP_TO_DATE"
        SyncStatus.LocalAhead -> "LOCAL_AHEAD"
        SyncStatus.CloudAhead -> "CLOUD_AHEAD"
        SyncStatus.Conflict -> "CONFLICT"
        SyncStatus.Error -> "ERROR"
    }
}

@Composable
private fun ConfigSection(
    title: String,
    items: List<Pair<String, String>> = emptyList(),
    content: (@Composable () -> Unit)? = null,
) {
    Column {
        // Title row with underline extending to the right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(1.dp)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, colorScheme.outlineVariant.copy(alpha = 0.4f))
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
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        text = release.changelog,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val colorScheme = MaterialTheme.colorScheme
    var newName by remember { mutableStateOf(currentName) }
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(2.dp, colorScheme.outlineVariant.copy(alpha = 0.4f))
                .padding(24.dp),
        ) {
            Text(
                text = context.getString(R.string.github_token_title),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = context.getString(R.string.github_token_desc),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
