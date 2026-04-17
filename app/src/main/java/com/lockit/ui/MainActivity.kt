package com.lockit.ui

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lockit.utils.BiometricUtils
import com.lockit.utils.LocaleHelper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lockit.LockitApp
import com.lockit.ui.components.BottomNavItem
import com.lockit.ui.components.BrutalistBottomNav
import com.lockit.ui.screens.add_credential.AddCredentialScreen
import com.lockit.ui.screens.config.ConfigScreen
import com.lockit.ui.screens.edit_credential.EditCredentialScreen
import com.lockit.ui.screens.logs.LogsScreen
import com.lockit.ui.screens.repos.ReposScreen
import com.lockit.ui.screens.secret_details.SecretDetailsScreen
import com.lockit.ui.screens.vault_explorer.VaultExplorerScreen
import com.lockit.ui.screens.vault_unlock.VaultUnlockScreen
import com.lockit.ui.theme.LockitTheme
import com.lockit.data.vault.CodingPlanPrefs
import com.lockit.domain.CodingPlanFetchers
import com.lockit.domain.CodingPlanPrefetchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as LockitApp

        // Invalidate biometric session and lock vault when app goes to background.
        (application as Application).registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                private var startedActivities = 0
                override fun onActivityStarted(activity: android.app.Activity) {
                    startedActivities++
                }
                override fun onActivityStopped(activity: android.app.Activity) {
                    startedActivities--
                    if (startedActivities <= 0) {
                        BiometricUtils.invalidateSession()
                        // Lock vault immediately when app goes to background
                        app.vaultManager.lockVault()
                    }
                }
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            }
        )

        setContent {
            LockitTheme {
                MainFlow(app = app)
            }
        }
    }
}

enum class AppScreen {
    Repos,
    VaultExplorer,
    SecretDetails,
    AddCredential,
    EditCredential,
    Logs,
    Config,
}

@androidx.compose.runtime.Composable
private fun MainFlow(app: LockitApp) {
    var isVaultUnlocked by remember { mutableStateOf(app.vaultManager.isUnlocked()) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe lifecycle to update vault state when app returns from background
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                // Check if vault was locked while app was in background
                isVaultUnlocked = app.vaultManager.isUnlocked()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Prefetch coding plan quota immediately on app startup using SharedPreferences data
    LaunchedEffect(Unit) {
        if (!CodingPlanPrefetchState.hasPrefetched && CodingPlanPrefs.hasData(app)) {
            CodingPlanPrefetchState.isLoading = true
            CodingPlanPrefetchState.hasPrefetched = true
            scope.launch {
                val metadata = CodingPlanPrefs.getMetadata(app)
                val provider = metadata["provider"] ?: return@launch
                val fetcher = CodingPlanFetchers.forProvider(provider)
                if (fetcher != null) {
                    val quota = withContext(Dispatchers.IO) {
                        fetcher.fetchQuota(metadata)
                    }
                    CodingPlanPrefetchState.quota = quota
                    CodingPlanPrefetchState.error = if (quota == null) "NO_QUOTA_DATA" else null
                }
                CodingPlanPrefetchState.isLoading = false
            }
        }
    }

    if (!isVaultUnlocked) {
        VaultUnlockScreen(
            onUnlocked = {
                isVaultUnlocked = true
                BiometricUtils.validateSession()
            },
        )
        return
    }

    // App-level session PIN overlay — shown when session expires (background/timeout)
    var showSessionPin by remember { mutableStateOf(!BiometricUtils.isSessionValid()) }
    if (showSessionPin) {
        com.lockit.ui.components.BrutalistPinVerifyDialog(
            app = app,
            onVerified = {
                showSessionPin = false
                BiometricUtils.validateSession()
            },
            onDismiss = {}, // Dismiss not allowed — must authenticate
        )
        return
    }

    var currentScreen by remember { mutableStateOf(AppScreen.Repos) }
    var selectedCredentialId by remember { mutableStateOf<String?>(null) }
    var editingCredentialId by remember { mutableStateOf<String?>(null) }
    var reposSelectedService by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentScreen) {
        if (currentScreen != AppScreen.SecretDetails) {
            selectedCredentialId = null
        }
        if (currentScreen != AppScreen.EditCredential) {
            editingCredentialId = null
        }
    }

    MainScaffold(
        app = app,
        currentScreen = currentScreen,
        selectedCredentialId = selectedCredentialId,
        editingCredentialId = editingCredentialId,
        reposSelectedService = reposSelectedService,
        onScreenChange = { currentScreen = it },
        onCredentialSelected = { id -> selectedCredentialId = id },
        onCredentialEdit = { id -> editingCredentialId = id; currentScreen = AppScreen.EditCredential },
        onReposServiceSelected = { reposSelectedService = it },
        onLockVault = {
            app.vaultManager.lockVault()
            isVaultUnlocked = false
            currentScreen = AppScreen.VaultExplorer
        },
    )
}

@androidx.compose.runtime.Composable
private fun MainScaffold(
    app: LockitApp,
    currentScreen: AppScreen,
    selectedCredentialId: String?,
    editingCredentialId: String?,
    reposSelectedService: String?,
    onScreenChange: (AppScreen) -> Unit,
    onCredentialSelected: (String) -> Unit,
    onCredentialEdit: (String) -> Unit,
    onReposServiceSelected: (String?) -> Unit,
    onLockVault: () -> Unit,
) {
    // Handle Android back button for secondary screens
    BackHandler(enabled = currentScreen == AppScreen.SecretDetails
        || currentScreen == AppScreen.AddCredential
        || currentScreen == AppScreen.EditCredential) {
        onScreenChange(AppScreen.VaultExplorer)
    }

    Scaffold(
        bottomBar = {
            if (currentScreen == AppScreen.VaultExplorer || currentScreen == AppScreen.Config
                || currentScreen == AppScreen.Repos || currentScreen == AppScreen.Logs) {
                BrutalistBottomNav(
                    selected = when (currentScreen) {
                        AppScreen.Config -> BottomNavItem.Config
                        AppScreen.Repos -> BottomNavItem.Repos
                        AppScreen.Logs -> BottomNavItem.Logs
                        else -> BottomNavItem.Keys
                    },
                    onItemSelected = { item ->
                        when (item) {
                            BottomNavItem.Keys -> onScreenChange(AppScreen.VaultExplorer)
                            BottomNavItem.Config -> onScreenChange(AppScreen.Config)
                            BottomNavItem.Repos -> onScreenChange(AppScreen.Repos)
                            BottomNavItem.Logs -> onScreenChange(AppScreen.Logs)
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (currentScreen) {
                AppScreen.Repos -> ReposScreen(
                    app = app,
                    onCredentialEdit = onCredentialEdit,
                    selectedService = reposSelectedService,
                    onServiceSelected = onReposServiceSelected,
                )

                AppScreen.VaultExplorer -> VaultExplorerScreen(
                    app = app,
                    onNavigateToDetails = onCredentialSelected,
                    onNavigateToAdd = { onScreenChange(AppScreen.AddCredential) },
                    onNavigateToEdit = onCredentialEdit,
                    onNavigateToConfig = { onScreenChange(AppScreen.Config) },
                )

                AppScreen.SecretDetails -> {
                    val credentialId = selectedCredentialId
                    if (credentialId != null) {
                        SecretDetailsScreen(
                            credentialId = credentialId,
                            app = app,
                            onBack = { onScreenChange(AppScreen.VaultExplorer) },
                            onDelete = { onScreenChange(AppScreen.VaultExplorer) },
                        )
                    } else {
                        onScreenChange(AppScreen.VaultExplorer)
                    }
                }

                AppScreen.AddCredential -> AddCredentialScreen(
                    app = app,
                    onBack = { onScreenChange(AppScreen.VaultExplorer) },
                    onSave = { onScreenChange(AppScreen.VaultExplorer) },
                )

                AppScreen.EditCredential -> {
                    val credentialId = editingCredentialId
                    if (credentialId != null) {
                        EditCredentialScreen(
                            credentialId = credentialId,
                            app = app,
                            onBack = { onScreenChange(AppScreen.VaultExplorer) },
                            onSave = { onScreenChange(AppScreen.VaultExplorer) },
                        )
                    } else {
                        onScreenChange(AppScreen.VaultExplorer)
                    }
                }

                AppScreen.Config -> ConfigScreen(
                    app = app,
                    onLockVault = onLockVault,
                )

                AppScreen.Logs -> LogsScreen()
            }
        }
    }
}
