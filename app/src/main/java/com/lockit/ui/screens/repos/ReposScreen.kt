package com.lockit.ui.screens.repos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lockit.ui.components.BrutalistTextField
import com.lockit.ui.components.CopyAction
import com.lockit.ui.components.CredentialCard
import com.lockit.ui.components.CredentialDefaults
import com.lockit.ui.components.buildEmailAddress
import com.lockit.ui.components.buildJsonStructured
import com.lockit.ui.components.extractEmailPassword
import com.lockit.ui.components.extractSecretValue
import com.lockit.ui.components.parseCredentialFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lockit.LockitApp
import com.lockit.R
import com.lockit.domain.CodingPlanProviders
import com.lockit.domain.CodingPlanQuota
import com.lockit.domain.CodingPlanFetchers
import com.lockit.domain.CodingPlanPrefetchState
import com.lockit.domain.CodingPlanRefreshPolicy
import com.lockit.data.vault.CodingPlanPrefs
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import com.lockit.ui.components.BackButtonRow
import com.lockit.ui.components.BrutalistEmptyState
import com.lockit.ui.components.BrutalistPinVerifyDialog
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.ScreenHero
import com.lockit.ui.components.TerminalFooter
import com.lockit.ui.components.findActivity
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.utils.BiometricUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class ProviderQuotaState(
    val quota: CodingPlanQuota? = null,
    val error: String? = null,
    val isLoading: Boolean = false,
    val cacheAgeMinutes: Int = 0,
)

class ReposViewModel(app: LockitApp) : ViewModel() {

    private val _credentials = MutableStateFlow<List<Credential>>(emptyList())
    val credentials: StateFlow<List<Credential>> = _credentials.asStateFlow()

    private val _selectedService = MutableStateFlow<String?>(null)
    val serviceCredentials: StateFlow<List<Credential>> = combine(
        _credentials, _selectedService
    ) { all, service ->
        if (service != null) {
            if (service == "EMAIL") {
                all.filter { it.type == CredentialType.Email }
            } else if (service == "CODING_PLAN") {
                all.filter { it.type == CredentialType.CodingPlan }
            } else if (service == "ACCOUNT") {
                all.filter { it.type == CredentialType.Account }
            } else if (service == "GITHUB") {
                all.filter { it.type == CredentialType.GitHub }
            } else {
                all.filter { it.service.uppercase() == service }
            }
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Per-provider quota state — each provider has independent slots (no cross-talk)
    private val _providerQuotas = MutableStateFlow<Map<String, ProviderQuotaState>>(emptyMap())
    val providerQuotas: StateFlow<Map<String, ProviderQuotaState>> = _providerQuotas.asStateFlow()

    // Expanded provider for detail view (null = all compact)
    private val _expandedProvider = MutableStateFlow<String?>(null)
    val expandedProvider: StateFlow<String?> = _expandedProvider.asStateFlow()

    // Per-provider last fetch timestamp (avoids redundant refetches)
    private val lastFetchTimes = mutableMapOf<String, Long>()

    private var currentApp: LockitApp? = null

    init {
        // Will be set when app is passed in
    }

    fun setApp(app: LockitApp) {
        currentApp = app
        app.vaultManager.getAllCredentials()
            .catch { _credentials.value = emptyList() }
            .onEach { _credentials.value = it }
            .launchIn(viewModelScope)
        CodingPlanFetchers.supportedProviders().forEach { provider ->
            combine(
                CodingPlanPrefetchState.isLoading(provider),
                CodingPlanPrefetchState.quota(provider),
                CodingPlanPrefetchState.error(provider),
                CodingPlanPrefetchState.cacheTimestamp(provider),
            ) { isLoading, quota, error, cacheTimestamp ->
                CodingPlanPrefetchState.Snapshot(isLoading, quota, error, cacheTimestamp)
            }.onEach { snapshot ->
                mergePrefetchSnapshot(provider, snapshot)
            }.launchIn(viewModelScope)
        }
    }

    fun selectService(service: String?) {
        _selectedService.value = service
    }

    fun toggleExpandProvider(provider: String) {
        val normalized = CodingPlanProviders.normalize(provider)
        _expandedProvider.value = if (_expandedProvider.value == normalized) null else normalized
    }

    suspend fun getCredentialById(id: String): Credential? {
        return currentApp?.vaultManager?.getCredentialById(id)
    }

    fun deleteCredential(credential: Credential) {
        viewModelScope.launch {
            currentApp?.vaultManager?.deleteCredential(credential)
        }
    }

    fun logCredentialCopied(name: String) {
        currentApp?.vaultManager?.logCredentialCopied(name)
    }

    fun refreshQuotaForProvider(provider: String, force: Boolean = false) {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        val currentState = _providerQuotas.value[normalizedProvider]

        // Prevent concurrent requests
        if (currentState?.isLoading == true) return

        val now = System.currentTimeMillis()
        val lastFetch = latestKnownFetchTimestamp(normalizedProvider)
        if (!CodingPlanRefreshPolicy.shouldFetch(lastFetch, now, force)) return

        val codingPlanCreds = _credentials.value.filter { it.type == CredentialType.CodingPlan }
        if (codingPlanCreds.isEmpty()) return

        lastFetchTimes[normalizedProvider] = now
        updateProviderState(normalizedProvider, isLoading = true)
        CodingPlanPrefetchState.setLoading(normalizedProvider, true)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val providerCreds = codingPlanCreds.filter {
                    CodingPlanProviders.normalize(
                        it.metadata["provider"]?.ifBlank { it.service } ?: it.service
                    ) == normalizedProvider
                }
                var quota: CodingPlanQuota? = null
                for (cred in providerCreds) {
                    val metadata = cred.metadata.takeIf { it.isNotEmpty() } ?: continue
                    val fetcher = CodingPlanFetchers.forProvider(normalizedProvider) ?: continue
                    quota = fetcher.fetchQuota(metadata)
                    if (quota != null) break
                }
                if (quota == null) {
                    val prefsMetadata = currentApp?.let { CodingPlanPrefs.getMetadata(it) }.orEmpty()
                    val prefsProvider = CodingPlanProviders.normalize(prefsMetadata["provider"])
                    if (prefsProvider == normalizedProvider) {
                        quota = CodingPlanFetchers.forProvider(normalizedProvider)?.fetchQuota(prefsMetadata)
                    }
                }
                quota
            }

            if (result != null) {
                currentApp?.let { CodingPlanPrefs.saveQuotaCache(it, result, normalizedProvider) }
                CodingPlanPrefetchState.setQuota(normalizedProvider, result)
                CodingPlanPrefetchState.setError(normalizedProvider, null)
                CodingPlanPrefetchState.setCacheTimestamp(normalizedProvider, System.currentTimeMillis())
                updateProviderState(normalizedProvider, quota = result, error = null, isLoading = false)
            } else {
                val isExpired = normalizedProvider == "qwen_bailian" &&
                    com.lockit.domain.qwen.QwenCodingPlan.lastHttpStatus in listOf(302, 401, 403)
                val error = if (isExpired) "COOKIE_EXPIRED" else "NO_QUOTA_DATA"
                CodingPlanPrefetchState.setQuota(normalizedProvider, null)
                CodingPlanPrefetchState.setError(normalizedProvider, error)
                updateProviderState(normalizedProvider, error = error, isLoading = false)
            }
            CodingPlanPrefetchState.setLoading(normalizedProvider, false)
        }
    }

    fun fetchQuotaForProvider(provider: String, force: Boolean = false) {
        refreshQuotaForProvider(provider, force)
    }

    fun fetchAllQuotas(force: Boolean = false) {
        val providers = codingPlanProviders()

        // Remove stale providers that no longer have credentials
        val current = _providerQuotas.value.toMutableMap()
        current.keys.filter { it !in providers }.forEach { current.remove(it) }
        _providerQuotas.value = current

        // Load cached data instantly for each provider (no network wait)
        providers.forEach { provider ->
            val cached = currentApp?.let { CodingPlanPrefs.loadQuotaCache(it, provider) }
            if (cached != null) {
                updateProviderState(
                    provider,
                    quota = cached,
                    cacheAgeMinutes = currentApp?.let {
                        CodingPlanRefreshPolicy.cacheAgeMinutes(CodingPlanPrefs.getCacheTimestamp(it, provider))
                    } ?: 0,
                )
            }
            // Also check if MainActivity prefetch already has data
            val snapshot = CodingPlanPrefetchState.getSnapshot(provider)
            if (snapshot.quota != null) {
                // Don't propagate snapshot.isLoading — it's about PrefetchState's own
                // background prefetch, not the ViewModel's fetch lifecycle. Copying it
                // would block user-initiated refresh (guard sees isLoading=true and skips).
                updateProviderState(provider, quota = snapshot.quota, error = snapshot.error)
                if (snapshot.cacheTimestamp > 0) {
                    updateProviderState(provider, cacheAgeMinutes = CodingPlanRefreshPolicy.cacheAgeMinutes(snapshot.cacheTimestamp))
                }
            }
        }

        // Fetch fresh data for all providers in parallel
        providers.forEach { provider -> refreshQuotaForProvider(provider, force) }
    }

    fun refreshAllQuotas(force: Boolean = false) {
        fetchAllQuotas(force)
    }

    private fun updateProviderState(
        provider: String,
        quota: CodingPlanQuota? = null,
        error: String? = null,
        isLoading: Boolean? = null,
        cacheAgeMinutes: Int? = null,
    ) {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        val current = _providerQuotas.value.toMutableMap()
        val existing = current[normalizedProvider] ?: ProviderQuotaState()
        current[normalizedProvider] = existing.copy(
            quota = quota ?: existing.quota,
            error = error ?: existing.error,
            isLoading = isLoading ?: existing.isLoading,
            cacheAgeMinutes = cacheAgeMinutes ?: existing.cacheAgeMinutes,
        )
        _providerQuotas.value = current
    }

    // Load cached data on screen entry — no network, instant display.
    // Fresh data only via user-initiated REFRESH or vault-unlock prefetch.
    fun loadCachedQuotas() {
        val providers = codingPlanProviders()

        // Remove stale providers
        val current = _providerQuotas.value.toMutableMap()
        current.keys.filter { it !in providers }.forEach { current.remove(it) }
        _providerQuotas.value = current

        // Load from CodingPlanPrefs cache and PrefetchState (no network)
        providers.forEach { provider ->
            val cached = currentApp?.let { CodingPlanPrefs.loadQuotaCache(it, provider) }
            if (cached != null) {
                updateProviderState(provider, quota = cached,
                    cacheAgeMinutes = currentApp?.let {
                        CodingPlanRefreshPolicy.cacheAgeMinutes(CodingPlanPrefs.getCacheTimestamp(it, provider))
                    } ?: 0)
            }
            val snapshot = CodingPlanPrefetchState.getSnapshot(provider)
            if (snapshot.quota != null) {
                // Don't propagate snapshot.isLoading — same reason as fetchAllQuotas.
                updateProviderState(provider, quota = snapshot.quota, error = snapshot.error)
                if (snapshot.cacheTimestamp > 0)
                    updateProviderState(provider, cacheAgeMinutes = CodingPlanRefreshPolicy.cacheAgeMinutes(snapshot.cacheTimestamp))
            }
        }
    }

    private fun latestKnownFetchTimestamp(provider: String): Long {
        val inMemory = lastFetchTimes[provider] ?: 0L
        val prefetch = CodingPlanPrefetchState.getSnapshot(provider).cacheTimestamp
        val persisted = currentApp?.let { CodingPlanPrefs.getCacheTimestamp(it, provider) } ?: 0L
        return maxOf(inMemory, prefetch, persisted)
    }

    private fun codingPlanProviders(): List<String> {
        return _credentials.value
            .filter { it.type == CredentialType.CodingPlan }
            .mapNotNull {
                CodingPlanProviders.normalize(
                    it.metadata["provider"]?.ifBlank { it.service } ?: it.service
                ).takeIf { p -> p.isNotBlank() }
            }
            .distinct()
    }

    private fun mergePrefetchSnapshot(
        provider: String,
        snapshot: CodingPlanPrefetchState.Snapshot,
    ) {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        if (snapshot.quota == null && snapshot.error == null && !snapshot.isLoading) return
        val current = _providerQuotas.value.toMutableMap()
        val existing = current[normalizedProvider] ?: return
        current[normalizedProvider] = existing.copy(
            quota = snapshot.quota ?: existing.quota,
            error = snapshot.error,
            isLoading = snapshot.isLoading,
            cacheAgeMinutes = CodingPlanRefreshPolicy.cacheAgeMinutes(snapshot.cacheTimestamp),
        )
        _providerQuotas.value = current
    }
}

private class ReposViewModelFactory(
    private val app: LockitApp,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val vm = ReposViewModel(app)
        vm.setApp(app)
        return vm as T
    }
}

@Composable
fun ReposScreen(
    app: LockitApp,
    modifier: Modifier = Modifier,
    onCredentialEdit: (String) -> Unit = {},
    selectedService: String? = null,
    onServiceSelected: (String?) -> Unit = {},
    viewModel: ReposViewModel = viewModel(factory = ReposViewModelFactory(app)),
) {
    val credentialList by viewModel.credentials.collectAsStateWithLifecycle()
    val serviceCredentials by viewModel.serviceCredentials.collectAsStateWithLifecycle()
    val servicesByGroup = remember(credentialList) {
        credentialList
            .filter { it.type != CredentialType.Phone }
            .groupBy {
                if (it.type == CredentialType.Email) "EMAIL"
                else if (it.type == CredentialType.CodingPlan) "CODING_PLAN"
                else if (it.type == CredentialType.Account) "ACCOUNT"
                else if (it.type == CredentialType.GitHub) "GITHUB"
                else it.service.uppercase()
            }
            .mapValues { it.value.size }
    }
    val serviceCount = servicesByGroup.size
    val credentialCount = credentialList.size

    var searchQuery by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current
    fun getActivity() = view.findActivity()
    val scope = rememberCoroutineScope()

    // Pre-compute string resources for use in non-composable callbacks
    val biometricViewTitle = stringResource(R.string.biometric_view_title)
    val biometricViewSubtitle = stringResource(R.string.biometric_view_subtitle)

    val credsByType = remember(credentialList) {
        credentialList.groupBy { it.type }
    }
    val phoneCredentials = credsByType[CredentialType.Phone] ?: emptyList()
    val codingPlanCredentials = credsByType[CredentialType.CodingPlan] ?: emptyList()
    val accountCredentials = credsByType[CredentialType.Account] ?: emptyList()

    // Coding plan quota state from ViewModel — per-provider map (no cross-talk)
    val providerQuotas by viewModel.providerQuotas.collectAsStateWithLifecycle()
    val expandedProvider by viewModel.expandedProvider.collectAsStateWithLifecycle()

    // Load cached/prefetched data on screen entry; this never starts a network request.
    LaunchedEffect(credentialList) {
        viewModel.loadCachedQuotas()
    }

    val displayedServiceCredentials = remember(serviceCredentials, searchQuery, selectedService) {
        if (selectedService == null || selectedService == "PHONE") {
            emptyList()
        } else if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            serviceCredentials.filter {
                it.name.lowercase().contains(query) ||
                it.type.displayName.lowercase().contains(query) ||
                it.key.lowercase().contains(query)
            }
        } else {
            serviceCredentials
        }
    }

    val displayedPhoneCredentials = remember(phoneCredentials, searchQuery, selectedService) {
        if (selectedService != "PHONE") {
            emptyList()
        } else if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            phoneCredentials.filter {
                it.name.lowercase().contains(query) ||
                it.service.lowercase().contains(query)
            }
        } else {
            phoneCredentials
        }
    }

    val displayedCodingPlanCredentials = remember(codingPlanCredentials, searchQuery, selectedService) {
        if (selectedService != "CODING_PLAN") {
            emptyList()
        } else if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            codingPlanCredentials.filter {
                it.name.lowercase().contains(query) ||
                it.service.lowercase().contains(query)
            }
        } else {
            codingPlanCredentials
        }
    }

    val displayedAccountCredentials = remember(accountCredentials, searchQuery, selectedService) {
        if (selectedService != "ACCOUNT") {
            emptyList()
        } else if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            accountCredentials.filter {
                it.name.lowercase().contains(query) ||
                it.service.lowercase().contains(query)
            }
        } else {
            accountCredentials
        }
    }

    val isCodingPlanBoardVisible = selectedService == null && codingPlanCredentials.isNotEmpty()
    LaunchedEffect(isCodingPlanBoardVisible, codingPlanCredentials.size) {
        if (!isCodingPlanBoardVisible) return@LaunchedEffect
        while (true) {
            delay(CodingPlanRefreshPolicy.AUTO_REFRESH_INTERVAL_MS)
            viewModel.refreshAllQuotas(force = false)
        }
    }

    // Biometric reveal state for repos
    val revealedEmailPasswordMap = remember { mutableMapOf<String, String?>() }
    val revealedCredentialIds = remember { mutableStateListOf<String>() }
    var reposToastMessage by remember { mutableStateOf<String?>(null) }
    // LazyColumn scroll state for service detail view - preserve position on modal close
    val serviceDetailListState = rememberLazyListState()

    LaunchedEffect(selectedService) {
        viewModel.selectService(selectedService)
        searchQuery = ""
        serviceDetailListState.scrollToItem(0)
    }

    fun onServiceRowClick(serviceName: String) {
        onServiceSelected(serviceName)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        BrutalistTopBar()

        if (selectedService == null) {
            // Service list view (no search needed)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // ... (keep service list as-is)
                ScreenHero(
                    title = stringResource(R.string.repos_title),
                    subtitle = stringResource(R.string.repos_subtitle),
                )
                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(stringResource(R.string.repos_services), servicesByGroup.size.toString(), Modifier.weight(1f))
                    StatCard(stringResource(R.string.repos_credentials), credentialCount.toString(), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(24.dp))

                // Coding Plan Board — visible when coding plan credentials exist
                if (codingPlanCredentials.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.repos_coding_plan_board),
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    MultiProviderBoard(
                        providerQuotas = providerQuotas,
                        expandedProvider = expandedProvider,
                        onToggleExpand = { viewModel.toggleExpandProvider(it) },
                        onRefreshAll = { viewModel.refreshAllQuotas(force = true) },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = stringResource(R.string.repos_registered_services),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                if (phoneCredentials.isNotEmpty()) {
                    ServiceRow(
                        name = "PHONE",
                        count = phoneCredentials.size,
                        isLocal = true,
                        onClick = { onServiceRowClick("PHONE") },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (servicesByGroup.isEmpty() && phoneCredentials.isEmpty()) {
                    BrutalistEmptyState(stringResource(R.string.repos_no_services_registered))
                } else {
                    servicesByGroup.entries.sortedBy { it.key }.forEach { (serviceName, count) ->
                        ServiceRow(
                            name = serviceName,
                            count = count,
                            isLocal = true,
                            onClick = { onServiceRowClick(serviceName) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                TerminalFooter(
                    lines = listOf(
                        stringResource(R.string.repos_status) to IndustrialOrange,
                        stringResource(R.string.repos_local_vault_active) to MaterialTheme.colorScheme.onSurfaceVariant,
                        stringResource(R.string.repos_remote_sync_not_configured) to MaterialTheme.colorScheme.onSurfaceVariant,
                        stringResource(R.string.repos_last_sync_never) to MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        } else {
            // Service detail view — compact rows + modal for details
            val isPhone = selectedService == "PHONE"
            val isCodingPlan = selectedService == "CODING_PLAN"
            val isAccount = selectedService == "ACCOUNT"
            val list = when {
                isPhone -> displayedPhoneCredentials
                isCodingPlan -> displayedCodingPlanCredentials
                isAccount -> displayedAccountCredentials
                else -> displayedServiceCredentials
            }

            var selectedCredential by remember { mutableStateOf<Credential?>(null) }
            var pendingCredentialForPinVerify by remember { mutableStateOf<Credential?>(null) }
            var cardRevealed by remember { mutableStateOf(false) }
            var pendingRevealAction by remember { mutableStateOf<(() -> Unit)?>(null) }

            // Handle Android back button: PIN dialog → modal → service detail
            BackHandler(enabled = pendingCredentialForPinVerify != null || pendingRevealAction != null) {
                pendingCredentialForPinVerify = null  // Close PIN dialog first
                pendingRevealAction = null  // Close reveal PIN dialog
            }
            BackHandler(enabled = selectedCredential != null && pendingCredentialForPinVerify == null && pendingRevealAction == null) {
                selectedCredential = null  // Close modal
                cardRevealed = false
                revealedCredentialIds.clear()
                revealedEmailPasswordMap.clear()
            }
            BackHandler(enabled = selectedCredential == null && pendingCredentialForPinVerify == null && pendingRevealAction == null) {
                onServiceSelected(null)  // Go back to service list
                cardRevealed = false
                revealedCredentialIds.clear()
                revealedEmailPasswordMap.clear()
            }

            // PIN verification dialog
            pendingCredentialForPinVerify?.let { credential ->
                BrutalistPinVerifyDialog(
                    app = app,
                    onVerified = {
                        pendingCredentialForPinVerify = null
                        selectedCredential = credential
                    },
                    onDismiss = { pendingCredentialForPinVerify = null },
                )
            }

            selectedCredential?.let { cred ->
                CredentialCardModal(
                    credential = cred,
                    onDismiss = {
                        selectedCredential = null
                        cardRevealed = false
                        pendingRevealAction = null
                    },
                    onCopy = { action ->
                        val fields = parseCredentialFields(cred.value)
                        val valueToCopy = when (action) {
                            CopyAction.VALUE -> extractSecretValue(cred.type, cred.value)
                            CopyAction.STRUCTURED -> buildJsonStructured(cred, fields)
                            CopyAction.EMAIL -> buildEmailAddress(fields)
                            CopyAction.PHONE -> fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: cred.service
                            CopyAction.API_KEY -> fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: extractSecretValue(cred.type, cred.value)
                            CopyAction.BASE_URL -> fields.getOrNull(4)?.takeIf { it.isNotBlank() } ?: extractSecretValue(cred.type, cred.value)
                        }
                        clipboardManager.setText(AnnotatedString(valueToCopy))
                        viewModel.logCredentialCopied(cred.name)
                    },
                    onDelete = {
                        scope.launch {
                            app.vaultManager.deleteCredential(cred)
                        }
                        selectedCredential = null
                        cardRevealed = false
                        pendingRevealAction = null
                    },
                    onEdit = {
                        onCredentialEdit(cred.id)
                        selectedCredential = null
                        cardRevealed = false
                        pendingRevealAction = null
                    },
                    onNeedReveal = { postReveal: (() -> Unit)? ->
                        val activity = getActivity()
                        val credToReveal = cred  // Capture credential for audit logging
                        if (activity != null) {
                            if (BiometricUtils.canAuthenticate(activity)) {
                                BiometricUtils.requireBiometric(
                                    activity = activity,
                                    title = biometricViewTitle,
                                    subtitle = biometricViewSubtitle,
                                    onSuccess = {
                                        cardRevealed = true
                                        credToReveal?.let { app.vaultManager.logCredentialViewed(it.name) }
                                        postReveal?.invoke()
                                    },
                                    onError = { pendingRevealAction = {
                                        cardRevealed = true
                                        credToReveal?.let { app.vaultManager.logCredentialViewed(it.name) }
                                        postReveal?.invoke()
                                    } },
                                )
                            } else {
                                pendingRevealAction = {
                                    cardRevealed = true
                                    credToReveal?.let { app.vaultManager.logCredentialViewed(it.name) }
                                    postReveal?.invoke()
                                }
                            }
                        }
                    },
                    isRevealed = cardRevealed,
                    onHide = { cardRevealed = false },
                )
            }

            // PIN fallback for reveal when biometric unavailable
            pendingRevealAction?.let { action ->
                BrutalistPinVerifyDialog(
                    app = app,
                    onVerified = {
                        pendingRevealAction = null
                        action()
                    },
                    onDismiss = { pendingRevealAction = null },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                BackButtonRow(
                    onBack = {
                        onServiceSelected(null)
                        cardRevealed = false
                        pendingRevealAction = null
                        revealedCredentialIds.clear()
                        revealedEmailPasswordMap.clear()
                    },
                )

                ScreenHero(
                    title = selectedService?.uppercase() ?: "UNKNOWN",
                    subtitle = when {
                        isPhone -> stringResource(R.string.repos_phone_subtitle)
                        isCodingPlan -> stringResource(R.string.repos_coding_plan_subtitle)
                        isAccount -> stringResource(R.string.repos_account_subtitle)
                        else -> stringResource(R.string.repos_service_subtitle)
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))

                BrutalistTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = stringResource(R.string.repos_search),
                    placeholder = stringResource(R.string.repos_search_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (searchQuery.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.repos_search_results) + " \"$searchQuery\"",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.repos_manifest),
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.repos_entries, list.size),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Compact credential rows - scroll state preserved via stable parent scope
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = serviceDetailListState,
                    contentPadding = PaddingValues(bottom = 140.dp),
                ) {
                    items(list) { credential ->
                        CompactCredentialRow(
                            credential = credential,
                            onClick = {
                                cardRevealed = false  // Reset reveal state for new credential
                                // 15-min session valid → proceed directly
                                if (BiometricUtils.isSessionValid()) {
                                    selectedCredential = credential
                                    return@CompactCredentialRow
                                }
                                val activity = view.findActivity()
                                if (activity != null) {
                                    if (BiometricUtils.canAuthenticate(activity)) {
                                        BiometricUtils.requireBiometric(
                                            activity = activity,
                                            title = biometricViewTitle,
                                            subtitle = biometricViewSubtitle,
                                            onSuccess = { selectedCredential = credential },
                                            onError = {
                                                pendingCredentialForPinVerify = credential
                                            },
                                        )
                                    } else {
                                        pendingCredentialForPinVerify = credential
                                    }
                                }
                            },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    if (list.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) stringResource(R.string.repos_no_matching_credentials) else stringResource(R.string.explorer_no_credentials),
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        TerminalFooter(
                            lines = listOf(
                                stringResource(R.string.repos_status) to IndustrialOrange,
                                stringResource(R.string.repos_local_vault_active) to MaterialTheme.colorScheme.onSurfaceVariant,
                                stringResource(R.string.repos_remote_sync_not_configured) to MaterialTheme.colorScheme.onSurfaceVariant,
                                stringResource(R.string.repos_last_sync_never) to MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // Toast notification
    reposToastMessage?.let { message ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            com.lockit.ui.components.BrutalistToast(
                message = message,
                onDismiss = { reposToastMessage = null },
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .border(1.dp, colorScheme.outline)
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServiceRow(
    name: String,
    count: Int,
    isLocal: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outline)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isLocal) Icons.Default.Lock else Icons.Default.CloudUpload,
                contentDescription = null,
                tint = if (isLocal) IndustrialOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.repos_keys, count),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(if (isLocal) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f))
                    .border(1.dp, if (isLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (isLocal) stringResource(R.string.repos_local) else stringResource(R.string.repos_remote),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Compact credential row for repos service detail view.
 * Click to open full credential card modal.
 */
@Composable
internal fun CompactCredentialRow(
    credential: Credential,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Title: display name based on type
            val displayName = when (credential.type) {
                CredentialType.Phone -> credential.displayTitle()
                CredentialType.Email -> "EMAIL"
                CredentialType.BankCard -> "CARD ****${credential.name.takeLast(4)}"
                CredentialType.IdCard -> stringResource(R.string.type_id_card)
                else -> credential.displayTitle()
            }
            Text(
                text = displayName.uppercase(),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Subtitle: type-specific info
            val subtitle = when (credential.type) {
                CredentialType.Phone -> {
                    val phoneFields = parseCredentialFields(credential.value)
                    phoneFields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: credential.service
                }
                CredentialType.Email -> {
                    val emailFields = parseCredentialFields(credential.value)
                    buildEmailAddress(emailFields)
                }
                CredentialType.CodingPlan -> {
                    val fields = parseCredentialFields(credential.value)
                    fields.getOrNull(2)?.takeIf { it.isNotBlank() }?.let {
                        val masked = if (it.length > 8) "${it.take(4)}****${it.takeLast(4)}" else "****"
                        "API_KEY: $masked"
                    } ?: credential.service.uppercase()
                }
                else -> credential.service.uppercase()
            }

            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = ">",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Full-screen modal showing a credential card with copy/edit/delete actions.
 */
@Composable
internal fun CredentialCardModal(
    credential: Credential,
    onDismiss: () -> Unit,
    onCopy: (CopyAction) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onNeedReveal: ((() -> Unit)?) -> Unit = {},
    isRevealed: Boolean = false,
    onHide: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = false) {},
        ) {
            CredentialCard(
                credential = credential,
                onClick = {},
                onCopy = onCopy,
                onDelete = onDelete,
                onEdit = onEdit,
                onNeedReveal = onNeedReveal,
                isRevealed = isRevealed,
                onHide = onHide,
                modifier = Modifier.padding(20.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.repos_modal_close_hint),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
