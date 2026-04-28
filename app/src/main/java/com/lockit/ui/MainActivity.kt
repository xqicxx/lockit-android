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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.lockit.ui.theme.ThemePreference
import com.lockit.data.vault.CodingPlanPrefs
import com.lockit.domain.CodingPlanFetchers
import com.lockit.domain.CodingPlanProviders
import com.lockit.domain.CodingPlanPrefetchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class MainActivity : FragmentActivity() {

    companion object {
        // Flag to ensure lifecycle callbacks are registered only once
        private var lifecycleCallbacksRegistered = false
        // SharedPreferences key for background timestamp
        const val PREFS_NAME = "lockit_background_prefs"
        const val KEY_BACKGROUND_TIMESTAMP = "background_timestamp"
        // 5 minutes in milliseconds
        const val BACKGROUND_LOCK_DELAY_MS = 5 * 60 * 1000L
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as LockitApp

        // On cold start: if there's a background timestamp, lock immediately
        // (Process was killed while in background → require immediate authentication)
        val bgPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bgTimestamp = bgPrefs.getLong(KEY_BACKGROUND_TIMESTAMP, 0)
        if (bgTimestamp > 0) {
            // Clear timestamp immediately to prevent repeated locks
            bgPrefs.edit().remove(KEY_BACKGROUND_TIMESTAMP).apply()
            // Lock vault and invalidate session - process was killed while in background
            app.vaultManager.lockVault()
            BiometricUtils.invalidateSession()
        }

        // Record background timestamp when app goes to background.
        // On resume: if > 5 minutes passed, lock vault and invalidate session.
        // On cold start: if timestamp exists, lock immediately (process was killed).
        // Note: isChangingConfigurations is true during recreate (e.g., language change),
        // we should NOT record timestamp in that case.
        if (!lifecycleCallbacksRegistered) {
            lifecycleCallbacksRegistered = true
            (application as Application).registerActivityLifecycleCallbacks(
                object : android.app.Application.ActivityLifecycleCallbacks {
                    private var startedActivities = 0
                    override fun onActivityStarted(activity: android.app.Activity) {
                        startedActivities++
                    }
                    override fun onActivityStopped(activity: android.app.Activity) {
                        startedActivities--
                        // Skip if activity is recreating (language/theme change)
                        if (startedActivities <= 0 && !activity.isChangingConfigurations) {
                            // Record background timestamp instead of immediate lock
                            // On cold start after process kill, will lock immediately
                            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            // Use commit() for synchronous write - security-critical
                            prefs.edit().putLong(KEY_BACKGROUND_TIMESTAMP, System.currentTimeMillis()).commit()
                        }
                    }
                    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityResumed(activity: android.app.Activity) {}
                    override fun onActivityPaused(activity: android.app.Activity) {}
                    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: android.app.Activity) {}
                }
            )
        }

        // Prefetch ALL coding plan providers on app startup — while user is typing password.
        // Strategy: fire network requests immediately, cache loads as fallback.
        CodingPlanFetchers.supportedProviders().forEach { provider ->
            // Load cached data instantly (shown while network request is in flight)
            val cachedQuota = CodingPlanPrefs.loadQuotaCache(app, provider)
            if (cachedQuota != null) {
                CodingPlanPrefetchState.setQuota(provider, cachedQuota)
                CodingPlanPrefetchState.setCacheTimestamp(provider, CodingPlanPrefs.getCacheTimestamp(app, provider))
            }

            // Fire fresh network fetch for every provider with stored credentials
            val metadata = CodingPlanPrefs.getProviderData(app, provider) + ("provider" to provider)
            if (metadata.size > 1) {
                CodingPlanPrefetchState.setLoading(provider, true)
                lifecycleScope.launch {
                    try {
                        val fetcher = CodingPlanFetchers.forProvider(provider) ?: return@launch
                        val quota = withContext(Dispatchers.IO) { fetcher.fetchQuota(metadata) }
                        CodingPlanPrefetchState.setQuota(provider, quota)
                        CodingPlanPrefetchState.setError(provider, if (quota == null) "NO_QUOTA_DATA" else null)
                        CodingPlanPrefetchState.setCacheTimestamp(provider, System.currentTimeMillis())
                        if (quota != null) CodingPlanPrefs.saveQuotaCache(app, quota, provider)
                    } catch (e: Exception) {
                        android.util.Log.e("LockitPrefetch", "$provider failed: ${e.message}")
                        CodingPlanPrefetchState.setError(provider, "FETCH_ERROR")
                    } finally {
                        CodingPlanPrefetchState.setLoading(provider, false)
                    }
                }
            }
        }

        setContent {
            val themeMode = ThemePreference.getThemeMode(this)
            LockitTheme(themeMode = themeMode) {
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
    // Navigation state - must be declared BEFORE vault unlock check
    // so they survive the vault lock/unlock cycle
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Repos) }
    var selectedCredentialId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingCredentialId by rememberSaveable { mutableStateOf<String?>(null) }
    var reposSelectedService by rememberSaveable { mutableStateOf<String?>(null) }
    var previousScreen by rememberSaveable { mutableStateOf(AppScreen.VaultExplorer) }

    var isVaultUnlocked by remember { mutableStateOf(app.vaultManager.isUnlocked()) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Observe lifecycle to update vault state when app returns from background
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                // Check background timestamp: lock if > 5 minutes passed
                val bgPrefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val bgTimestamp = bgPrefs.getLong(MainActivity.KEY_BACKGROUND_TIMESTAMP, 0)
                if (bgTimestamp > 0) {
                    val elapsed = System.currentTimeMillis() - bgTimestamp
                    if (elapsed > MainActivity.BACKGROUND_LOCK_DELAY_MS) {
                        // More than 5 minutes: lock vault and invalidate session
                        app.vaultManager.lockVault()
                        BiometricUtils.invalidateSession()
                    }
                    // Clear timestamp (app is now in foreground)
                    bgPrefs.edit().remove(MainActivity.KEY_BACKGROUND_TIMESTAMP).apply()
                }
                // Check if vault was locked while app was in background
                isVaultUnlocked = app.vaultManager.isUnlocked()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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

    // Navigation state already declared before vault check
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
        previousScreen = previousScreen,
        selectedCredentialId = selectedCredentialId,
        editingCredentialId = editingCredentialId,
        reposSelectedService = reposSelectedService,
        onScreenChange = {
            // Track previous screen when navigating to secondary screens
            if (currentScreen == AppScreen.SecretDetails || currentScreen == AppScreen.EditCredential || currentScreen == AppScreen.AddCredential) {
                // Keep previousScreen unchanged when navigating between secondary screens
            } else {
                previousScreen = currentScreen
            }
            currentScreen = it
        },
        onCredentialSelected = { id -> selectedCredentialId = id },
        onCredentialEdit = { id ->
            previousScreen = currentScreen  // Remember where we came from
            editingCredentialId = id
            currentScreen = AppScreen.EditCredential
        },
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
    previousScreen: AppScreen,
    selectedCredentialId: String?,
    editingCredentialId: String?,
    reposSelectedService: String?,
    onScreenChange: (AppScreen) -> Unit,
    onCredentialSelected: (String) -> Unit,
    onCredentialEdit: (String) -> Unit,
    onReposServiceSelected: (String?) -> Unit,
    onLockVault: () -> Unit,
) {
    // Handle Android back button for secondary screens - return to previous screen
    BackHandler(enabled = currentScreen == AppScreen.SecretDetails
        || currentScreen == AppScreen.AddCredential
        || currentScreen == AppScreen.EditCredential) {
        onScreenChange(previousScreen)
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
                            BottomNavItem.Repos -> {
                                // Clear selected service when user explicitly navigates to Repos home
                                onReposServiceSelected(null)
                                onScreenChange(AppScreen.Repos)
                            }
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
