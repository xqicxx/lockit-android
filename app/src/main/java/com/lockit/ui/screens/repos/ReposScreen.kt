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
import androidx.compose.ui.graphics.Color
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
import com.lockit.domain.CodingPlanQuota
import com.lockit.domain.CodingPlanFetchers
import com.lockit.domain.CodingPlanPrefetchState
import com.lockit.domain.CodingPlanProviders
import com.lockit.data.vault.CodingPlanPrefs
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import com.lockit.ui.components.BackButtonRow
import com.lockit.ui.components.BrutalistEmptyState
import com.lockit.ui.components.BrutalistPinVerifyDialog
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.InfoTag
import com.lockit.ui.components.ScreenHero
import com.lockit.ui.components.TerminalFooter
import com.lockit.ui.components.findActivity
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.SurfaceHighest
import com.lockit.ui.theme.SurfaceLow
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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

    // Coding plan quota state - persisted in ViewModel across navigation
    private val _codingPlanQuota = MutableStateFlow<CodingPlanQuota?>(null)
    val codingPlanQuota: StateFlow<CodingPlanQuota?> = _codingPlanQuota.asStateFlow()

    private val _codingPlanQuotaError = MutableStateFlow<String?>(null)
    val codingPlanQuotaError: StateFlow<String?> = _codingPlanQuotaError.asStateFlow()

    private val _isQuotaLoading = MutableStateFlow(false)
    val isQuotaLoading: StateFlow<Boolean> = _isQuotaLoading.asStateFlow()

    // Selected provider for quota display (qwen_bailian, chatgpt, claude)
    private val _selectedProvider = MutableStateFlow<String>("qwen_bailian")
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()

    // Cache freshness threshold (5 minutes) - replaces permanent lock
    private val CACHE_FRESHNESS_MS = 5 * 60 * 1000L

    var lastAutoFetchTime = 0L  // Timestamp-based instead of permanent lock
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
    }

    fun selectService(service: String?) {
        if (_selectedService.value == "CODING_PLAN" && service != "CODING_PLAN") {
            lastAutoFetchTime = 0L  // Allow fresh fetch on next board entry
        }
        _selectedService.value = service
    }

    fun selectProvider(provider: String) {
        val normalized = CodingPlanProviders.normalize(provider)
        if (_selectedProvider.value == normalized) return
        _selectedProvider.value = normalized
        _codingPlanQuota.value = null
        _codingPlanQuotaError.value = null
        lastAutoFetchTime = 0L
        // Re-fetch quota for new provider
        fetchCodingPlanQuota(force = true)
    }

    fun syncCodingPlanProviders(credentials: List<Credential>) {
        val providers = availableCodingPlanProviders(credentials)
        if (providers.isEmpty()) {
            _codingPlanQuota.value = null
            _codingPlanQuotaError.value = null
            return
        }

        if (_selectedProvider.value !in providers) {
            _selectedProvider.value = providers.first()
            _codingPlanQuota.value = null
            _codingPlanQuotaError.value = null
            lastAutoFetchTime = 0L
        }
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

    fun fetchCodingPlanQuota(force: Boolean = false) {
        // Prevent concurrent requests - skip if already loading
        if (_isQuotaLoading.value) return

        // Cache freshness check - skip if data is fresh (within 5 min) unless forced
        if (!force) {
            val cacheAge = System.currentTimeMillis() - lastAutoFetchTime
            if (cacheAge < CACHE_FRESHNESS_MS && _codingPlanQuota.value != null) {
                return  // Fresh data exists, no need to refetch
            }
        }
        val codingPlanCreds = _credentials.value.filter { it.type == CredentialType.CodingPlan }
        if (codingPlanCreds.isEmpty()) {
            _codingPlanQuota.value = null
            return
        }
        lastAutoFetchTime = System.currentTimeMillis()  // Mark fetch time
        _isQuotaLoading.value = true
        CodingPlanPrefetchState.setLoading(true)
        _codingPlanQuotaError.value = null

        val targetProvider = _selectedProvider.value
        val providerCreds = codingPlanCreds.filter { CodingPlanProviders.fromCredential(it) == targetProvider }
        val effectiveProvider = if (providerCreds.isNotEmpty()) {
            targetProvider
        } else {
            CodingPlanProviders.fromCredential(codingPlanCreds.first())
        }
        val effectiveProviderCreds = if (providerCreds.isNotEmpty()) {
            providerCreds
        } else {
            codingPlanCreds.filter { CodingPlanProviders.fromCredential(it) == effectiveProvider }
        }
        if (_selectedProvider.value != effectiveProvider) {
            _selectedProvider.value = effectiveProvider
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val prefsMetadata = currentApp?.let {
                    CodingPlanPrefs.getProviderData(it, effectiveProvider) + ("provider" to effectiveProvider)
                } ?: emptyMap()
                val metadataCandidates = effectiveProviderCreds.map { cred ->
                    prefsMetadata + cred.metadata + ("provider" to effectiveProvider)
                } + listOf(prefsMetadata).filter { it.size > 1 }

                var quota: CodingPlanQuota? = null
                for (metadata in metadataCandidates) {
                    val fetcher = CodingPlanFetchers.forProvider(effectiveProvider) ?: continue
                    quota = fetcher.fetchQuota(metadata)
                    if (quota != null) break
                }
                quota
            }
            _codingPlanQuota.value = result
            CodingPlanPrefetchState.setQuota(result)
            if (result == null && codingPlanCreds.isNotEmpty()) {
                _codingPlanQuotaError.value = "NO_QUOTA_DATA"
                CodingPlanPrefetchState.setError("NO_QUOTA_DATA")
            } else {
                CodingPlanPrefetchState.setError(null)
                // Save to cache for next startup
                if (result != null) {
                    currentApp?.let { CodingPlanPrefs.saveQuotaCache(it, result, effectiveProvider) }
                }
            }
            _isQuotaLoading.value = false
            CodingPlanPrefetchState.setLoading(false)
        }
    }

    // Auto-fetch when credentials become available (call from UI once)
    fun autoFetchIfNeeded() {
        // Cache freshness check - skip if data is fresh (within 5 min)
        val cacheAge = System.currentTimeMillis() - lastAutoFetchTime
        if (cacheAge < CACHE_FRESHNESS_MS && _codingPlanQuota.value != null) {
            return  // Fresh data exists
        }

        // Check if prefetch is still running - will be observed via StateFlow
        if (CodingPlanPrefetchState.isLoading.value) {
            _isQuotaLoading.value = true
            lastAutoFetchTime = System.currentTimeMillis()
            // Show cached quota during background refresh (instant display)
            _codingPlanQuota.value = CodingPlanPrefetchState.quota.value
            return
        }

        // First check if we have prefetched quota from app startup
        val prefetched = CodingPlanPrefetchState.quota.value
        if (prefetched != null) {
            _codingPlanQuota.value = prefetched
            _codingPlanQuotaError.value = CodingPlanPrefetchState.error.value
            _isQuotaLoading.value = false
            lastAutoFetchTime = System.currentTimeMillis()
            return
        }

        // If no prefetched data and not loading, fetch from credentials
        if (_credentials.value.any { it.type == CredentialType.CodingPlan }) {
            fetchCodingPlanQuota(force = true)
        }
    }

    // Update quota when prefetch completes (called from StateFlow observer)
    fun updateQuotaFromPrefetch(quota: CodingPlanQuota?, error: String?, provider: String?) {
        val normalizedProvider = provider?.let { CodingPlanProviders.normalize(it) }
        if (normalizedProvider != null && normalizedProvider != _selectedProvider.value) {
            return
        }
        _codingPlanQuota.value = quota
        _codingPlanQuotaError.value = error
        _isQuotaLoading.value = false
    }

    private fun availableCodingPlanProviders(credentials: List<Credential>): List<String> {
        return credentials
            .filter { it.type == CredentialType.CodingPlan }
            .map { CodingPlanProviders.fromCredential(it) }
            .distinct()
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

    // Coding plan quota state from ViewModel - persists across navigation
    val codingPlanQuota by viewModel.codingPlanQuota.collectAsStateWithLifecycle()
    val isQuotaLoading by viewModel.isQuotaLoading.collectAsStateWithLifecycle()
    val quotaError by viewModel.codingPlanQuotaError.collectAsStateWithLifecycle()
    val selectedProvider by viewModel.selectedProvider.collectAsStateWithLifecycle()

    // Auto-fetch once when credentials become available
    LaunchedEffect(codingPlanCredentials) {
        viewModel.syncCodingPlanProviders(codingPlanCredentials)
        viewModel.autoFetchIfNeeded()
    }

    // Observe prefetch state changes via StateFlow (no polling loop needed)
    val prefetchLoading by CodingPlanPrefetchState.isLoading.collectAsStateWithLifecycle()
    val prefetchQuota by CodingPlanPrefetchState.quota.collectAsStateWithLifecycle()
    val prefetchError by CodingPlanPrefetchState.error.collectAsStateWithLifecycle()
    val prefetchCacheTimestamp by CodingPlanPrefetchState.cacheTimestamp.collectAsStateWithLifecycle()
    val prefetchProvider = remember(prefetchCacheTimestamp, prefetchQuota) {
        CodingPlanPrefs.getCachedProvider(app)
    }

    // Calculate cache age in minutes (how old is the cached data)
    val cacheAgeMinutes = remember(prefetchCacheTimestamp, prefetchProvider, selectedProvider) {
        if (prefetchCacheTimestamp > 0 && CodingPlanProviders.normalize(prefetchProvider) == selectedProvider) {
            ((System.currentTimeMillis() - prefetchCacheTimestamp) / 60000).toInt()
        } else 0
    }

    // When prefetch completes (success or failure), update ViewModel quota
    LaunchedEffect(prefetchLoading, prefetchQuota, prefetchError, prefetchProvider) {
        if (!prefetchLoading && (prefetchQuota != null || prefetchError != null)) {
            viewModel.updateQuotaFromPrefetch(prefetchQuota, prefetchError, prefetchProvider)
        }
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

    // Biometric reveal state for repos
    val revealedEmailPasswordMap = remember { mutableMapOf<String, String?>() }
    val revealedCredentialIds = remember { mutableStateListOf<String>() }
    var reposToastMessage by remember { mutableStateOf<String?>(null) }
    // LazyColumn scroll state for service detail view - preserve position on modal close
    val serviceDetailListState = rememberLazyListState()

    LaunchedEffect(selectedService) {
        viewModel.selectService(selectedService)
        searchQuery = ""
        // Reset scroll position when switching services
        serviceDetailListState.scrollToItem(0)
        // Force quota refresh when entering CODING_PLAN board
        if (selectedService == "CODING_PLAN") {
            viewModel.fetchCodingPlanQuota(force = true)
        }
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

                // Coding Plan Board — on main page, above REGISTERED_SERVICES
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
                    CodingPlanBoard(
                        quota = codingPlanQuota,
                        isLoading = isQuotaLoading,
                        error = quotaError,
                        selectedProvider = selectedProvider,
                        cacheAgeMinutes = cacheAgeMinutes,
                        onRefresh = { viewModel.fetchCodingPlanQuota(force = true) },
                    )
                    // Provider switcher cards - only show if 2+ providers exist
                    Spacer(modifier = Modifier.height(8.dp))
                    // Get unique providers from existing CodingPlan credentials
                    val existingProviders = codingPlanCredentials
                        .map { CodingPlanProviders.fromCredential(it) }
                        .distinct()
                    if (existingProviders.size >= 2) {
                        ProviderCardsRow(
                            selectedProvider = selectedProvider,
                            existingProviders = existingProviders,
                            onSelect = { viewModel.selectProvider(it) },
                        )
                    }
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
                    onNeedReveal = {
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
                                    },
                                    onError = { pendingRevealAction = {
                                        cardRevealed = true
                                        credToReveal?.let { app.vaultManager.logCredentialViewed(it.name) }
                                    } },
                                )
                            } else {
                                pendingRevealAction = {
                                    cardRevealed = true
                                    credToReveal?.let { app.vaultManager.logCredentialViewed(it.name) }
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
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                color = colorScheme.onSurface,
            )
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariant,
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
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isLocal) Icons.Default.Lock else Icons.Default.CloudUpload,
                contentDescription = null,
                tint = if (isLocal) IndustrialOrange else colorScheme.onSurfaceVariant,
                modifier = Modifier.height(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = colorScheme.onSurface,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.repos_keys, count),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(if (isLocal) colorScheme.surfaceContainerHighest else colorScheme.surfaceContainer.copy(alpha = 0.2f))
                    .border(1.dp, if (isLocal) colorScheme.outlineVariant.copy(alpha = 0.2f) else colorScheme.outlineVariant.copy(alpha = 0.2f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (isLocal) stringResource(R.string.repos_local) else stringResource(R.string.repos_remote),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLocal) colorScheme.onSurface else colorScheme.onSurfaceVariant,
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
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
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
                color = colorScheme.onSurface,
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
    onNeedReveal: () -> Unit = {},
    isRevealed: Boolean = false,
    onHide: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
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


/**
 * Coding Plan Board — shows full instance info + quota usage with a refresh button.
 */
@Composable
private fun CodingPlanBoard(
    quota: CodingPlanQuota?,
    isLoading: Boolean,
    error: String?,
    selectedProvider: String,
    cacheAgeMinutes: Int, // How old is the cached data (minutes)
    onRefresh: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (quota?.instanceName?.isNotBlank() == true) quota.instanceName.uppercase() else stringResource(R.string.repos_coding_plan),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = providerLabel(selectedProvider).uppercase(),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = IndustrialOrange,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isLoading) {
                Text(
                    text = stringResource(R.string.repos_fetching),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    modifier = Modifier
                        .background(TacticalRed)
                        .clickable(onClick = onRefresh)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.repos_refresh),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = White,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (quota != null) {
            CodingPlanSummaryGrid(
                quota = quota,
                selectedProvider = selectedProvider,
                cacheAgeMinutes = cacheAgeMinutes,
                isLoading = isLoading,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SurfaceLow),
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QuotaGauge(primaryWindowLabel(selectedProvider), quota.sessionUsed, quota.sessionTotal, Modifier.weight(1f))
                QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, Modifier.weight(1f))
                QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, Modifier.weight(1f))
            }

            if (quota.modelQuotas.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.repos_quota_models),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                quota.modelQuotas.values.forEach { modelQuota ->
                    ModelQuotaRow(modelQuota)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            if (quota.creditsRemaining > 0.0 || quota.extraUsageLimit > 0.0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (quota.creditsRemaining > 0.0) {
                        MetricTile(
                            label = stringResource(R.string.repos_quota_credits),
                            value = "${quota.creditsRemaining} ${quota.creditsCurrency}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (quota.extraUsageLimit > 0.0) {
                        MetricTile(
                            label = stringResource(R.string.repos_quota_extra_usage),
                            value = "${quota.extraUsageSpent} / ${quota.extraUsageLimit}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TacticalRed.copy(0.1f))
                    .border(1.dp, TacticalRed)
                    .padding(12.dp),
            ) {
                Column {
                    Text(
                        text = when (error) {
                            "NO_QUOTA_DATA" -> stringResource(R.string.repos_quota_fetch_failed)
                            "NOT_LOGIN" -> stringResource(R.string.repos_quota_cookie_expired)
                            null -> stringResource(R.string.repos_quota_no_data)
                            else -> error
                        },
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = TacticalRed,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.repos_quota_retry_hint),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodingPlanSummaryGrid(
    quota: CodingPlanQuota,
    selectedProvider: String,
    cacheAgeMinutes: Int,
    isLoading: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val statusText = quota.status.ifBlank { stringResource(R.string.repos_quota_status_valid) }
        val isValid = statusText.equals("VALID", ignoreCase = true) ||
            statusText.equals("ACTIVE", ignoreCase = true)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusTag(
                text = if (isValid) stringResource(R.string.repos_quota_status_valid) else statusText.uppercase(),
                color = if (isValid) IndustrialOrange else TacticalRed,
            )
            if (quota.autoRenewFlag) {
                StatusTag(stringResource(R.string.repos_quota_auto_renew), IndustrialOrange)
            }
            if (cacheAgeMinutes > 0 && !isLoading) {
                StatusTag("${cacheAgeMinutes}m cache", if (cacheAgeMinutes > 60) TacticalRed else IndustrialOrange)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.repos_quota_plan),
                value = listOf(quota.planName, quota.tier, quota.instanceType)
                    .firstOrNull { it.isNotBlank() }
                    ?: providerLabel(selectedProvider),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.repos_quota_account),
                value = quota.accountEmail.ifBlank { quota.loginMethod.ifBlank { "--" } },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                label = stringResource(R.string.repos_quota_remaining_label),
                value = if (quota.remainingDays > 0) stringResource(R.string.repos_quota_days_value, quota.remainingDays) else "--",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = stringResource(R.string.repos_quota_cost_label),
                value = if (quota.chargeAmount > 0.0) "¥${quota.chargeAmount}" else quota.chargeType.ifBlank { "--" },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(colorScheme.surfaceContainerHighest)
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
            .padding(8.dp),
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            color = colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuotaGauge(label: String, used: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        // Use Long to prevent integer overflow when used > 21.4M tokens
        val pct = if (total > 0) (used.toLong() * 100 / total).coerceIn(0, 100).toInt() else 0
        val barColor = when {
            pct >= 90 -> TacticalRed
            pct >= 70 -> IndustrialOrange
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        // Label row with percentage on the right (above progress bar)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$pct%",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                color = barColor,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        // Progress bar background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(SurfaceLow),
        ) {
            // Progress bar fill
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (total > 0) pct / 100f else 0f)
                    .height(8.dp)
                    .background(color = barColor),
            )
        }

        Spacer(modifier = Modifier.height(2.dp))
        // Usage row - just numbers, no percentage
        Text(
            text = "$used / $total",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelQuotaRow(modelQuota: com.lockit.domain.model.ModelQuota) {
    val colorScheme = MaterialTheme.colorScheme
    val pct = modelQuota.usedPercent.coerceIn(0.0, 100.0).toFloat()
    val barColor = when {
        pct >= 90f -> TacticalRed
        pct >= 70f -> IndustrialOrange
        else -> colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surfaceContainerHighest)
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = modelQuota.modelName.uppercase(),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${pct.toInt()}%",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = barColor,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(SurfaceLow),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct / 100f)
                    .height(7.dp)
                    .background(barColor),
            )
        }
        if (modelQuota.weekTotal > 0 || modelQuota.resetsAt != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildModelQuotaFooter(modelQuota),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun buildModelQuotaFooter(modelQuota: com.lockit.domain.model.ModelQuota): String {
    val usage = if (modelQuota.weekTotal > 0) {
        "${modelQuota.weekUsed} / ${modelQuota.weekTotal}"
    } else {
        ""
    }
    val reset = modelQuota.resetsAt?.toString()?.substringBefore("T")?.let { "RESET $it" } ?: ""
    return listOf(usage, reset).filter { it.isNotBlank() }.joinToString(" // ")
}

@Composable
private fun providerLabel(provider: String): String {
    return when (CodingPlanProviders.normalize(provider)) {
        CodingPlanProviders.QWEN_BAILIAN -> stringResource(R.string.provider_qwen)
        CodingPlanProviders.CHATGPT -> stringResource(R.string.provider_chatgpt)
        CodingPlanProviders.CLAUDE -> stringResource(R.string.provider_claude)
        else -> provider
    }
}

@Composable
private fun primaryWindowLabel(provider: String): String {
    return when (CodingPlanProviders.normalize(provider)) {
        CodingPlanProviders.CHATGPT -> stringResource(R.string.quota_day)
        else -> stringResource(R.string.quota_5h)
    }
}

/**
 * Provider cards row for switching between coding plan providers.
 * Shows 百炼, ChatGPT, Claude service cards.
 */
@Composable
private fun ProviderCardsRow(
    selectedProvider: String,
    existingProviders: List<String>,
    onSelect: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    // Provider label mapping
    val providerLabels = mapOf(
        "qwen_bailian" to stringResource(R.string.provider_qwen),
        "chatgpt" to stringResource(R.string.provider_chatgpt),
        "claude" to stringResource(R.string.provider_claude),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        existingProviders.forEach { provider ->
            val label = providerLabels[provider] ?: provider
            val isSelected = selectedProvider == provider
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) IndustrialOrange.copy(alpha = 0.15f) else colorScheme.surfaceContainerHighest)
                    .border(1.dp, if (isSelected) IndustrialOrange else colorScheme.outlineVariant.copy(alpha = 0.2f))
                    .clickable { onSelect(provider) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                Text(
                    text = label,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 10.sp,
                    color = if (isSelected) IndustrialOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
