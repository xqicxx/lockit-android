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
import com.lockit.domain.CodingPlanProviders
import com.lockit.domain.CodingPlanQuota
import com.lockit.domain.CodingPlanFetchers
import com.lockit.domain.CodingPlanPrefetchState
import com.lockit.data.vault.CodingPlanPrefs
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import com.lockit.domain.model.ModelQuota
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
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


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

    // Cache freshness threshold (5 minutes)
    private val CACHE_FRESHNESS_MS = 5 * 60 * 1000L

    // Per-provider last fetch timestamp (avoids redundant refetches)
    private val lastFetchTimes = mutableMapOf<String, Long>()

    var lastAutoFetchTime = 0L
    private var currentApp: LockitApp? = null
    private var previousService: String? = null

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
        // Reset fetch timestamps when leaving CODING_PLAN board
        if (previousService == "CODING_PLAN" && service != "CODING_PLAN") {
            lastAutoFetchTime = 0L
            lastFetchTimes.clear()
        }
        previousService = _selectedService.value
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

    fun fetchQuotaForProvider(provider: String, force: Boolean = false) {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        val currentState = _providerQuotas.value[normalizedProvider]

        // Prevent concurrent requests
        if (currentState?.isLoading == true) return

        // Cache freshness check
        if (!force) {
            val lastFetch = lastFetchTimes[normalizedProvider] ?: 0L
            val cacheAge = System.currentTimeMillis() - lastFetch
            if (cacheAge < CACHE_FRESHNESS_MS && currentState?.quota != null) return
        }

        val codingPlanCreds = _credentials.value.filter { it.type == CredentialType.CodingPlan }
        if (codingPlanCreds.isEmpty()) return

        lastFetchTimes[normalizedProvider] = System.currentTimeMillis()
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

    fun fetchAllQuotas(force: Boolean = false) {
        val providers = _credentials.value
            .filter { it.type == CredentialType.CodingPlan }
            .mapNotNull {
                CodingPlanProviders.normalize(
                    it.metadata["provider"]?.ifBlank { it.service } ?: it.service
                ).takeIf { p -> p.isNotBlank() }
            }.distinct()

        // Load cached data instantly for each provider (no network wait)
        providers.forEach { provider ->
            val cached = currentApp?.let { CodingPlanPrefs.loadQuotaCache(it, provider) }
            if (cached != null) {
                updateProviderState(
                    provider,
                    quota = cached,
                    cacheAgeMinutes = currentApp?.let {
                        ((System.currentTimeMillis() - CodingPlanPrefs.getCacheTimestamp(it, provider)) / 60000).toInt()
                    } ?: 0,
                )
            }
            // Also check if MainActivity prefetch already has data
            val snapshot = CodingPlanPrefetchState.getSnapshot(provider)
            if (snapshot.quota != null) {
                updateProviderState(provider, quota = snapshot.quota, error = snapshot.error, isLoading = snapshot.isLoading)
                if (snapshot.cacheTimestamp > 0) {
                    updateProviderState(provider, cacheAgeMinutes = ((System.currentTimeMillis() - snapshot.cacheTimestamp) / 60000).toInt())
                }
            }
        }

        // Fetch fresh data for all providers in parallel
        providers.forEach { provider -> fetchQuotaForProvider(provider, force) }
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

    fun autoFetchIfNeeded() {
        if (_credentials.value.any { it.type == CredentialType.CodingPlan }) {
            fetchAllQuotas()
        }
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

    // Auto-fetch once when credentials become available
    LaunchedEffect(credentialList) {
        viewModel.autoFetchIfNeeded()
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

                // Coding Plan Board — compact multi-provider dashboard
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
                        onRefreshAll = { viewModel.fetchAllQuotas(force = true) },
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
                    .background(if (isLocal) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.2f))
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


/**
 * Multi-provider compact dashboard — all providers visible at once, no switching needed.
 * Each provider shows key metrics in a compact row. Click to expand full detail inline.
 */
@Composable
private fun MultiProviderBoard(
    providerQuotas: Map<String, ProviderQuotaState>,
    expandedProvider: String?,
    onToggleExpand: (String) -> Unit,
    onRefreshAll: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val providerLabels = mapOf(
        "qwen_bailian" to stringResource(R.string.provider_qwen),
        "chatgpt" to stringResource(R.string.provider_chatgpt),
        "claude" to stringResource(R.string.provider_claude),
    )
    val anyLoading = providerQuotas.values.any { it.isLoading }
    val sortedProviders = providerQuotas.keys.sortedBy { it }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outline)
            .padding(12.dp),
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.repos_coding_plan),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = colorScheme.onSurface,
            )
            if (anyLoading) {
                Text(
                    text = stringResource(R.string.repos_fetching),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    modifier = Modifier
                        .background(TacticalRed)
                        .clickable(onClick = onRefreshAll)
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

        if (sortedProviders.isEmpty()) {
            // No cached data yet — show placeholder
            Text(
                text = stringResource(R.string.repos_quota_no_data),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            sortedProviders.forEachIndexed { index, provider ->
                if (index > 0) BoardDivider()
                val state = providerQuotas[provider] ?: return@forEachIndexed
                val label = providerLabels[provider] ?: provider
                val isExpanded = expandedProvider == provider

                CompactProviderRow(
                    provider = provider,
                    label = label,
                    state = state,
                    isExpanded = isExpanded,
                    onClick = { onToggleExpand(provider) },
                )

                // Expanded detail
                if (isExpanded && state.quota != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    when (provider) {
                        CodingPlanProviders.CHATGPT -> ChatGptCodingPlanContent(state.quota)
                        CodingPlanProviders.QWEN_BAILIAN -> QwenCodingPlanContent(state.quota)
                        else -> GenericCodingPlanContent(state.quota)
                    }
                }

                // Error state (shown in-row, below compact)
                if (state.quota == null && !state.isLoading && state.error != null) {
                    Text(
                        text = when (state.error) {
                            "COOKIE_EXPIRED" -> stringResource(R.string.repos_quota_cookie_expired)
                            else -> stringResource(R.string.repos_quota_fetch_failed)
                        },
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 8.sp,
                        color = TacticalRed,
                        modifier = Modifier.padding(start = 64.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactProviderRow(
    provider: String,
    label: String,
    state: ProviderQuotaState,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val quota = state.quota

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        // Main row: label | gauges | badge | arrow
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Provider name label
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isExpanded) IndustrialOrange else colorScheme.onSurface,
                modifier = Modifier.width(64.dp),
            )

            if (state.isLoading) {
                Text(
                    text = "...",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else if (quota != null) {
                CompactGauge("5h", quota.sessionUsed, quota.sessionTotal, Modifier.weight(1f), showNumbers = true)
                CompactGauge("Wk", quota.weekUsed, quota.weekTotal, Modifier.weight(1f))
                if (quota.monthTotal > 0) {
                    CompactGauge("Mo", quota.monthUsed, quota.monthTotal, Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                // tier 优先于 status，确保订阅类型清晰可见
                val badge = quota.tier.takeIf { it.isNotBlank() }
                    ?: quota.status.takeIf { it.isNotBlank() }
                    ?: quota.planName.takeIf { it.isNotBlank() }
                    ?: "—"
                StatusChip(
                    text = badge.uppercase().take(8),
                    color = when {
                        quota.status.equals("VALID", true) || quota.status.equals("ACTIVE", true) -> IndustrialOrange
                        quota.status.equals("EXPIRED", true) || quota.status.equals("EXHAUSTED", true) -> TacticalRed
                        else -> colorScheme.onSurfaceVariant
                    },
                )
            } else {
                Text(
                    text = "—",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            // Expand/collapse arrow
            Text(
                text = if (isExpanded) "▼" else "▶",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // Sub row: cache age + reset time + identity (below the gauges)
        if (quota != null && !state.isLoading) {
            val metaParts = mutableListOf<String>()
            if (state.cacheAgeMinutes > 0) metaParts.add("${state.cacheAgeMinutes}m ago")
            quota.sessionResetsAt?.let { metaParts.add("reset ${formatResetTime(it)}") }
            if (quota.accountEmail.isNotBlank()) metaParts.add(quota.accountEmail.take(20))
            if (metaParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    metaParts.forEach { part ->
                        Text(
                            text = part,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 7.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactGauge(label: String, used: Int, total: Int, modifier: Modifier, showNumbers: Boolean = false) {
    val pct = if (total > 0) (used.toLong() * 100 / total).coerceIn(0, 100).toInt() else 0
    val barColor = when {
        pct >= 90 -> TacticalRed
        pct >= 70 -> IndustrialOrange
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier.padding(horizontal = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = "$pct%",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = barColor,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (total > 0) pct / 100f else 0f)
                    .height(3.dp)
                    .background(barColor),
            )
        }
        if (showNumbers) {
            Text(
                text = "$used/$total",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatGptCodingPlanContent(quota: CodingPlanQuota) {
    val tier = quota.tier.ifBlank { quota.instanceType.ifBlank { "--" } }
    val planName = quota.planName.ifBlank { "ChatGPT" }
    val remainingDays = quota.remainingDays.takeIf { it > 0 }?.let {
        stringResource(R.string.repos_quota_days_value, it)
    } ?: "--"
    val renewText = when {
        quota.autoRenewFlag -> stringResource(R.string.repos_quota_auto_renew)
        quota.chargeType.equals("subscription", ignoreCase = true) -> stringResource(R.string.repos_quota_manual_renew)
        quota.chargeType.isNotBlank() -> quota.chargeType.uppercase()
        else -> "--"
    }

    // All metadata fields — dynamic, only shows what has data
    val infoItems = buildList {
        add(stringResource(R.string.repos_quota_plan) to tier)
        if (quota.instanceName.isNotBlank()) add("INSTANCE" to quota.instanceName)
        if (quota.instanceType.isNotBlank() && quota.instanceType != tier)
            add("TYPE" to quota.instanceType)
        add(stringResource(R.string.repos_quota_account) to quota.accountEmail.ifBlank { "--" })
        add(stringResource(R.string.repos_quota_remaining_label) to remainingDays)
        if (quota.loginMethod.isNotBlank())
            add(stringResource(R.string.repos_quota_login_method) to quota.loginMethod)
        add(stringResource(R.string.repos_quota_status_label) to quota.status.ifBlank { "--" })
        add(stringResource(R.string.repos_quota_renew_label) to renewText)
        if (quota.chargeAmount > 0.0)
            add(stringResource(R.string.repos_quota_cost_label) to "$${quota.chargeAmount}")
        if (quota.chargeType.isNotBlank())
            add("CHARGE" to quota.chargeType.uppercase())
        if (quota.creditsRemaining > 0.0)
            add(stringResource(R.string.repos_quota_credits) to "${quota.creditsRemaining} ${quota.creditsCurrency}")
        if (quota.extraUsageSpent > 0.0 || quota.extraUsageLimit > 0.0)
            add(stringResource(R.string.repos_quota_extra_usage) to "${quota.extraUsageSpent}/${quota.extraUsageLimit}")
    }

    InfoGrid(items = infoItems)

    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusChip(text = planName.uppercase(), color = IndustrialOrange)
        if (tier != "--") StatusChip(text = tier.uppercase(), color = IndustrialOrange)
        if (quota.status.isNotBlank()) {
            val active = quota.status.equals("VALID", true) || quota.status.equals("ACTIVE", true)
            StatusChip(text = quota.status.uppercase(), color = if (active) IndustrialOrange else TacticalRed, filled = active)
        }
    }

    ModelQuotasSection(quota.modelQuotas)

    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, quota.sessionResetsAt, false, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, quota.weekResetsAt, false, true, Modifier.weight(1f))
        if (quota.monthTotal > 0)
            QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, quota.monthResetsAt, false, true, Modifier.weight(1f))
    }
}

@Composable
private fun QwenCodingPlanContent(quota: CodingPlanQuota) {
    val infoItems = buildList {
        add(stringResource(R.string.repos_quota_plan) to quota.planName.ifBlank { "--" })
        if (quota.instanceName.isNotBlank()) add("INSTANCE" to quota.instanceName)
        if (quota.instanceType.isNotBlank()) add("TYPE" to quota.instanceType)
        add(stringResource(R.string.repos_quota_remaining_label) to quota.remainingDays.takeIf { it > 0 }?.let {
            stringResource(R.string.repos_quota_days_value, it)
        }.orEmpty().ifBlank { "--" })
        add(stringResource(R.string.repos_quota_cost_label) to if (quota.chargeAmount > 0.0) "¥${quota.chargeAmount}" else "--")
        if (quota.chargeType.isNotBlank()) add("CHARGE" to quota.chargeType.uppercase())
        add(stringResource(R.string.repos_quota_status_label) to quota.status.ifBlank { "--" })
        if (quota.accountEmail.isNotBlank()) add(stringResource(R.string.repos_quota_account) to quota.accountEmail)
        if (quota.loginMethod.isNotBlank()) add(stringResource(R.string.repos_quota_login_method) to quota.loginMethod)
        if (quota.creditsRemaining > 0.0)
            add(stringResource(R.string.repos_quota_credits) to "${quota.creditsRemaining} ${quota.creditsCurrency}")
    }

    InfoGrid(items = infoItems)

    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (quota.autoRenewFlag) StatusChip(text = stringResource(R.string.repos_quota_auto_renew), color = IndustrialOrange)
        if (quota.chargeType.isNotBlank()) StatusChip(text = quota.chargeType.uppercase(), color = IndustrialOrange)
        if (quota.status.isNotBlank()) {
            val active = quota.status.equals("VALID", true) || quota.status.equals("ACTIVE", true)
            StatusChip(text = quota.status.uppercase(), color = if (active) IndustrialOrange else TacticalRed, filled = active)
        }
    }

    ModelQuotasSection(quota.modelQuotas)

    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, null, true, false, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, null, true, false, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, null, true, false, Modifier.weight(1f))
    }
}

@Composable
private fun GenericCodingPlanContent(quota: CodingPlanQuota) {
    val infoItems = buildList {
        add(stringResource(R.string.repos_quota_plan) to quota.tier.ifBlank { quota.planName.ifBlank { quota.instanceName.ifBlank { "--" } } })
        if (quota.instanceName.isNotBlank()) add("INSTANCE" to quota.instanceName)
        if (quota.instanceType.isNotBlank()) add("TYPE" to quota.instanceType)
        if (quota.accountEmail.isNotBlank()) add(stringResource(R.string.repos_quota_account) to quota.accountEmail)
        if (quota.loginMethod.isNotBlank()) add(stringResource(R.string.repos_quota_login_method) to quota.loginMethod)
        if (quota.remainingDays > 0) add(stringResource(R.string.repos_quota_remaining_label) to stringResource(R.string.repos_quota_days_value, quota.remainingDays))
        if (quota.status.isNotBlank()) add(stringResource(R.string.repos_quota_status_label) to quota.status)
        if (quota.chargeAmount > 0.0) add(stringResource(R.string.repos_quota_cost_label) to "$${quota.chargeAmount}")
        if (quota.chargeType.isNotBlank()) add("CHARGE" to quota.chargeType.uppercase())
        if (quota.autoRenewFlag) add(stringResource(R.string.repos_quota_renew_label) to stringResource(R.string.repos_quota_auto_renew))
        if (quota.creditsRemaining > 0.0) add(stringResource(R.string.repos_quota_credits) to "${quota.creditsRemaining} ${quota.creditsCurrency}")
        if (quota.extraUsageSpent > 0.0 || quota.extraUsageLimit > 0.0)
            add(stringResource(R.string.repos_quota_extra_usage) to "${quota.extraUsageSpent}/${quota.extraUsageLimit}")
    }

    InfoGrid(items = infoItems)

    ModelQuotasSection(quota.modelQuotas)

    BoardDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        QuotaGauge(stringResource(R.string.quota_5h), quota.sessionUsed, quota.sessionTotal, quota.sessionResetsAt, true, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, quota.weekResetsAt, true, true, Modifier.weight(1f))
        QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, quota.monthResetsAt, true, true, Modifier.weight(1f))
    }
}

@Composable
private fun ModelQuotasSection(modelQuotas: Map<String, ModelQuota>) {
    if (modelQuotas.isEmpty()) return
    BoardDivider()
    Text(
        text = stringResource(R.string.repos_quota_models),
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    modelQuotas.values.forEach { mq ->
        val pct = mq.usedPercent.toInt().coerceIn(0, 100)
        val barColor = when {
            pct >= 90 -> TacticalRed
            pct >= 70 -> IndustrialOrange
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mq.modelName, fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("$pct%", fontFamily = JetBrainsMonoFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = barColor)
            if (mq.weekTotal > 0) {
                Text("  ${mq.weekUsed}/${mq.weekTotal}", fontFamily = JetBrainsMonoFamily, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Box(Modifier.fillMaxWidth(pct / 100f).height(3.dp).background(barColor))
        }
    }
}

@Composable
private fun InfoGrid(items: List<Pair<String, String>>) {
    val rows = items.chunked(2)
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { (label, value) ->
                    QuotaInfoCell(label = label, value = value, modifier = Modifier.weight(1f))
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (index != rows.lastIndex) {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun QuotaInfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BoardDivider() {
    Spacer(modifier = Modifier.height(8.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun StatusChip(text: String, color: Color, filled: Boolean = false) {
    Box(
        modifier = Modifier
            .background(if (filled) color.copy(alpha = 0.15f) else Color.Transparent)
            .border(1.dp, color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

private val quotaResetFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatResetTime(resetAt: Instant?): String {
    return resetAt
        ?.atZone(ZoneId.systemDefault())
        ?.format(quotaResetFormatter)
        ?: "--"
}

@Composable
private fun QuotaGauge(
    label: String,
    used: Int,
    total: Int,
    resetAt: Instant?,
    showUsageNumbers: Boolean,
    showReset: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
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
        Text(
            text = if (showUsageNumbers && total > 0) "$used / $total" else stringResource(R.string.repos_quota_usage_hidden),
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showReset) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.repos_quota_reset_at, formatResetTime(resetAt)),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 8.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

