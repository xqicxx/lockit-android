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
import org.json.JSONObject
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

    private var hasAutoFetchedQuota = false
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
        _selectedService.value = service
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
        if (!force && hasAutoFetchedQuota) return
        val codingPlanCreds = _credentials.value.filter { it.type == CredentialType.CodingPlan }
        if (codingPlanCreds.isEmpty()) {
            _codingPlanQuota.value = null
            return
        }
        hasAutoFetchedQuota = true
        _isQuotaLoading.value = true
        CodingPlanPrefetchState.isLoading = true
        _codingPlanQuotaError.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var quota: CodingPlanQuota? = null
                for (cred in codingPlanCreds) {
                    val metadata = cred.metadata.takeIf { it.isNotEmpty() } ?: continue
                    val provider = metadata["provider"] ?: continue
                    val fetcher = CodingPlanFetchers.forProvider(provider) ?: continue
                    quota = fetcher.fetchQuota(metadata)
                    if (quota != null) break
                }
                quota
            }
            _codingPlanQuota.value = result
            CodingPlanPrefetchState.quota = result
            if (result == null && codingPlanCreds.isNotEmpty()) {
                _codingPlanQuotaError.value = "NO_QUOTA_DATA"
                CodingPlanPrefetchState.error = "NO_QUOTA_DATA"
            } else {
                CodingPlanPrefetchState.error = null
            }
            _isQuotaLoading.value = false
            CodingPlanPrefetchState.isLoading = false
        }
    }

    // Auto-fetch when credentials become available (call from UI once)
    fun autoFetchIfNeeded() {
        // First check if we have prefetched quota from app startup
        if (!hasAutoFetchedQuota) {
            val prefetched = CodingPlanPrefetchState.quota
            if (prefetched != null) {
                _codingPlanQuota.value = prefetched
                _codingPlanQuotaError.value = CodingPlanPrefetchState.error
                _isQuotaLoading.value = CodingPlanPrefetchState.isLoading
                hasAutoFetchedQuota = true
                return
            }
        }
        // If no prefetched data, fetch from credentials
        if (!hasAutoFetchedQuota && _credentials.value.any { it.type == CredentialType.CodingPlan }) {
            fetchCodingPlanQuota(force = true)
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

    // Coding plan quota state from ViewModel - persists across navigation
    val codingPlanQuota by viewModel.codingPlanQuota.collectAsStateWithLifecycle()
    val isQuotaLoading by viewModel.isQuotaLoading.collectAsStateWithLifecycle()
    val quotaError by viewModel.codingPlanQuotaError.collectAsStateWithLifecycle()

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

    LaunchedEffect(selectedService) {
        viewModel.selectService(selectedService)
        searchQuery = ""
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
                        color = Primary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    CodingPlanBoard(
                        quota = codingPlanQuota,
                        isLoading = isQuotaLoading,
                        error = quotaError,
                        onRefresh = { viewModel.fetchCodingPlanQuota(force = true) },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = stringResource(R.string.repos_registered_services),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = Primary,
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
                        stringResource(R.string.repos_local_vault_active) to Color.Gray,
                        stringResource(R.string.repos_remote_sync_not_configured) to Color.Gray,
                        stringResource(R.string.repos_last_sync_never) to Color.Gray,
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

            // Handle Android back button: PIN dialog → modal → service detail
            BackHandler(enabled = pendingCredentialForPinVerify != null) {
                pendingCredentialForPinVerify = null  // Close PIN dialog first
            }
            BackHandler(enabled = selectedCredential != null && pendingCredentialForPinVerify == null) {
                selectedCredential = null  // Close modal
                revealedCredentialIds.clear()
                revealedEmailPasswordMap.clear()
            }
            BackHandler(enabled = selectedCredential == null && pendingCredentialForPinVerify == null) {
                onServiceSelected(null)  // Go back to service list
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

            if (selectedCredential != null) {
                CredentialCardModal(
                    credential = selectedCredential!!,
                    onDismiss = { selectedCredential = null },
                    onCopy = { action ->
                        val cred = selectedCredential!!
                        val fields = parseCredentialFields(cred.value)
                        val valueToCopy = when (action) {
                            CopyAction.VALUE -> extractSecretValue(cred.type, cred.value)
                            CopyAction.STRUCTURED -> buildJsonStructured(cred, fields)
                            CopyAction.EMAIL -> buildEmailAddress(fields)
                            CopyAction.PHONE -> fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: cred.service
                            CopyAction.API_KEY -> fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: extractSecretValue(cred.type, cred.value)
                            CopyAction.BASE_URL -> fields.getOrNull(5)?.takeIf { it.isNotBlank() } ?: extractSecretValue(cred.type, cred.value)
                        }
                        clipboardManager.setText(AnnotatedString(valueToCopy))
                        viewModel.logCredentialCopied(cred.name)
                    },
                    onDelete = {
                        val cred = selectedCredential!!
                        scope.launch {
                            app.vaultManager.deleteCredential(cred)
                        }
                        selectedCredential = null
                    },
                    onEdit = {
                        onCredentialEdit(selectedCredential!!.id)
                        selectedCredential = null
                    },
                    onNeedReveal = { },
                    isRevealed = true,
                    onHide = { },
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
                        revealedCredentialIds.clear()
                        revealedEmailPasswordMap.clear()
                    },
                )

                ScreenHero(
                    title = selectedService!!.uppercase(),
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
                        color = Color.Gray,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Compact credential rows
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 140.dp),
                ) {
                    items(list) { credential ->
                        CompactCredentialRow(
                            credential = credential,
                            onClick = {
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
                                    color = Color.Gray,
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        TerminalFooter(
                            lines = listOf(
                                stringResource(R.string.repos_status) to IndustrialOrange,
                                stringResource(R.string.repos_local_vault_active) to Color.Gray,
                                stringResource(R.string.repos_remote_sync_not_configured) to Color.Gray,
                                stringResource(R.string.repos_last_sync_never) to Color.Gray,
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
    Box(
        modifier = modifier
            .border(1.dp, Primary)
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                color = Primary,
            )
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = Color.Gray,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Primary)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isLocal) Icons.Default.Lock else Icons.Default.CloudUpload,
                contentDescription = null,
                tint = if (isLocal) IndustrialOrange else Color.Gray,
                modifier = Modifier.height(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.repos_keys, count),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = Color.Gray,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(if (isLocal) SurfaceHighest else Color.Gray.copy(0.2f))
                    .border(1.dp, if (isLocal) Primary else Color.Gray)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (isLocal) stringResource(R.string.repos_local) else stringResource(R.string.repos_remote),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isLocal) Primary else Color.Gray,
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
            .border(1.dp, Color.Black)
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
                color = Primary,
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
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = ">",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 14.sp,
            color = Color.Gray,
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
            .background(Color.Black.copy(0.5f))
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
                color = Color.Gray,
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
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Primary)
            .padding(12.dp),
    ) {
        // Single title row with REFRESH button on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Title: instance name or default
            Text(
                text = if (quota?.instanceName?.isNotBlank() == true) quota.instanceName.uppercase() else stringResource(R.string.repos_coding_plan),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Primary,
            )
            // REFRESH button - prominent color
            if (isLoading) {
                Text(
                    text = stringResource(R.string.repos_fetching),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = Color.Gray,
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
            // Details: use Column for better text handling on narrow screens
            Column(modifier = Modifier.fillMaxWidth()) {
                // Remaining days and cost
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.repos_quota_remaining, quota.remainingDays),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = stringResource(R.string.repos_quota_cost, quota.chargeAmount),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = Color.Gray,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // Status tags
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (quota.autoRenewFlag) {
                        Box(
                            modifier = Modifier
                                .border(1.dp, IndustrialOrange)
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.repos_quota_auto_renew),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 8.sp,
                                color = IndustrialOrange,
                            )
                        }
                    }
                    // Status tag
                    val isValid = quota.status == "VALID"
                    Box(
                        modifier = Modifier
                            .background(if (isValid) IndustrialOrange.copy(0.15f) else TacticalRed.copy(0.2f))
                            .border(1.dp, if (isValid) IndustrialOrange else TacticalRed)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = if (isValid) stringResource(R.string.repos_quota_status_valid) else stringResource(R.string.repos_quota_status_expired),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isValid) IndustrialOrange else TacticalRed,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Separator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SurfaceLow),
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Quota gauges
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QuotaGauge(stringResource(R.string.quota_5h), quota.fiveHourUsed, quota.fiveHourTotal, Modifier.weight(1f))
                QuotaGauge(stringResource(R.string.quota_week), quota.weekUsed, quota.weekTotal, Modifier.weight(1f))
                QuotaGauge(stringResource(R.string.quota_month), quota.monthUsed, quota.monthTotal, Modifier.weight(1f))
            }
        } else {
            // Error or empty state
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
                        color = Color.Gray,
                    )
                }
            }
        }
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
            else -> Color.Gray
        }

        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            letterSpacing = 1.sp,
            color = Color.Gray,
        )
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
        // Usage row - percentage right-aligned for consistency
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$used / $total",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                color = Primary,
            )
            Text(
                text = "($pct%)",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = barColor,
            )
        }
    }
}