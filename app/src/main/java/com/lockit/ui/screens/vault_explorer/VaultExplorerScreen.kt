package com.lockit.ui.screens.vault_explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lockit.LockitApp
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import com.lockit.ui.components.BrutalistPinVerifyDialog
import com.lockit.ui.components.BrutalistTextField
import com.lockit.ui.components.BrutalistToast
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.CredentialCard
import com.lockit.ui.components.InfoTag
import com.lockit.ui.components.ScreenHero
import com.lockit.ui.components.TerminalFooter
import com.lockit.ui.components.TopBarAddButton
import com.lockit.ui.components.buildEmailAddress
import com.lockit.ui.components.extractEmailPassword
import com.lockit.ui.components.extractSecretValue
import com.lockit.ui.components.findActivity
import com.lockit.ui.components.parseCredentialFields
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.White
import com.lockit.utils.BiometricUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class VaultExplorerViewModel(private val app: LockitApp) : ViewModel() {

    private val _credentials = MutableStateFlow<List<Credential>>(emptyList())
    val credentials: StateFlow<List<Credential>> = _credentials.asStateFlow()

    private val _searchFlow = MutableStateFlow<Job?>(null)
    private var lastQuery = ""

    var searchQuery: String = ""
        set(value) {
            if (value == lastQuery) return
            lastQuery = value
            field = value
            _searchFlow.value?.cancel()
            _searchFlow.value = if (value.isBlank()) {
                startObservingAllCredentials()
            } else {
                startObservingSearch(value)
            }
        }

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    init {
        startObservingAllCredentials()
    }

    private fun startObservingAllCredentials(): Job {
        return app.vaultManager.getAllCredentials()
            .catch { _credentials.value = emptyList() }
            .onEach { _credentials.value = it }
            .launchIn(viewModelScope)
    }

    private fun startObservingSearch(query: String): Job {
        return app.vaultManager.searchCredentials(query)
            .catch { _credentials.value = emptyList() }
            .onEach { _credentials.value = it }
            .launchIn(viewModelScope)
    }

    fun deleteCredential(credential: Credential) {
        viewModelScope.launch {
            try {
                app.vaultManager.deleteCredential(credential)
                _toastMessage.value = "CREDENTIAL_DELETED: ${credential.name}"
            } catch (e: Exception) {
                _toastMessage.value = "ERROR: ${e.message}"
            }
        }
    }

    fun showToast(message: String) {
        _toastMessage.value = message
    }

    fun dismissToast() {
        _toastMessage.value = null
    }
}

@Composable
fun VaultExplorerScreen(
    app: LockitApp,
    onNavigateToDetails: (String) -> Unit,
    onNavigateToAdd: () -> Unit,
    onNavigateToEdit: (String) -> Unit,
    onNavigateToConfig: () -> Unit,
    viewModel: VaultExplorerViewModel = viewModel(
        factory = VaultExplorerViewModelFactory(app),
    ),
) {
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf(viewModel.searchQuery) }
    var phoneRegionForToast by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val services by remember { derivedStateOf { credentials.map { it.service.uppercase() }.distinct() } }
    var revealedEmailPassword by remember { mutableStateOf<String?>(null) }
    val revealedCredentialIds = remember { mutableStateListOf<String>() }
    val view = LocalView.current
    fun getActivity() = view.findActivity()

    // PIN fallback for when biometric is unavailable
    var pendingBiometricAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun doCopy(credential: Credential) {
        when (credential.type) {
            CredentialType.Phone -> {
                clipboardManager.setText(AnnotatedString(credential.service))
                app.vaultManager.logCredentialCopied(credential.name)
                phoneRegionForToast = credential.name
                viewModel.showToast("PHONE_COPIED. Tap to copy country code.")
            }
            CredentialType.Email -> {
                val emailAddr = buildEmailAddress(parseCredentialFields(credential.value))
                clipboardManager.setText(AnnotatedString(emailAddr))
                app.vaultManager.logCredentialCopied(credential.name)
                viewModel.showToast("EMAIL_COPIED: $emailAddr")
            }
            CredentialType.CodingPlan -> {
                // CodingPlan: copy structured format (must be revealed first)
                val fields = parseCredentialFields(credential.value)
                val provider = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "未知"
                val apiKey = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
                val baseUrl = fields.getOrNull(5)?.takeIf { it.isNotBlank() }
                val lines = mutableListOf<String>()
                lines.add("PROVIDER: $provider")
                if (apiKey != null) lines.add("API_KEY: $apiKey")
                if (baseUrl != null) lines.add("BASE_URL: $baseUrl")
                clipboardManager.setText(AnnotatedString(lines.joinToString("\n")))
                app.vaultManager.logCredentialCopied(credential.name)
                viewModel.showToast("CODING_PLAN_COPIED")
            }
            else -> {
                clipboardManager.setText(AnnotatedString(extractSecretValue(credential.type, credential.value)))
                app.vaultManager.logCredentialCopied(credential.name)
                viewModel.showToast("CREDENTIAL_COPIED: ${credential.name}")
            }
        }
    }

    fun doCopyValue(credential: Credential) {
        // Copy single value from row
        clipboardManager.setText(AnnotatedString(extractSecretValue(credential.type, credential.value)))
        app.vaultManager.logCredentialCopied(credential.name)
        viewModel.showToast("VALUE_COPIED")
    }

    fun doCopyJsonStructured(credential: Credential) {
        // Build JSON from all credential fields
        val fields = parseCredentialFields(credential.value)
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(java.time.ZoneOffset.UTC)
        val updatedAtStr = dateFormatter.format(credential.updatedAt)
        val createdAtStr = dateFormatter.format(credential.createdAt)
        val json = buildString {
            append("{\n")
            append("  \"name\": \"${credential.name}\",\n")
            append("  \"type\": \"${credential.type.displayName}\",\n")
            if (credential.service.isNotBlank()) append("  \"service\": \"${credential.service}\",\n")
            if (credential.key.isNotBlank()) append("  \"key\": \"${credential.key}\",\n")
            // Add all parsed fields
            credential.type.fields.forEachIndexed { index, field ->
                val value = fields.getOrNull(index)?.takeIf { it.isNotBlank() }
                if (value != null) {
                    val fieldName = field.label.lowercase().replace(" ", "_")
                    append("  \"$fieldName\": \"$value\",\n")
                }
            }
            append("  \"created_at\": \"$createdAtStr\",\n")
            append("  \"updated_at\": \"$updatedAtStr\"\n")
            append("}")
        }
        clipboardManager.setText(AnnotatedString(json))
        app.vaultManager.logCredentialCopied(credential.name)
        viewModel.showToast("JSON_COPIED")
    }

    fun doCopyApiKey(credential: Credential) {
        val fields = parseCredentialFields(credential.value)
        val apiKey = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
        if (apiKey != null) {
            clipboardManager.setText(AnnotatedString(apiKey))
            app.vaultManager.logCredentialCopied(credential.name)
            viewModel.showToast("API_KEY_COPIED")
        }
    }

    fun doCopyBaseUrl(credential: Credential) {
        val fields = parseCredentialFields(credential.value)
        val baseUrl = fields.getOrNull(5)?.takeIf { it.isNotBlank() }
        if (baseUrl != null) {
            clipboardManager.setText(AnnotatedString(baseUrl))
            app.vaultManager.logCredentialCopied(credential.name)
            viewModel.showToast("BASE_URL_COPIED")
        }
    }

    fun doCopyProvider(credential: Credential) {
        doCopyJsonStructured(credential)
    }

    fun showNeedRevealToast() {
        viewModel.showToast("请先点击眼睛展示")
    }

    Column(modifier = Modifier.fillMaxSize().background(White)) {
        BrutalistTopBar(
            rightContent = {
                TopBarAddButton(onClick = onNavigateToAdd)
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 140.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                ScreenHero(
                    title = "Vault Explorer",
                    subtitle = "Unified Terminal View // Alpha-04 // [Read/Write]",
                )
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Primary)
                        .padding(16.dp),
                ) {
                    Column {
                        Text(
                            text = "> QUICK_START",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = IndustrialOrange,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To add your first credential, tap the + NEW button in the top bar. For CLI integration, run `lockit add --name <name> --service <service> --key <key> --value <secret>` in your terminal.",
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = Color.Gray,
                            lineHeight = 14.sp,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                BrutalistTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        viewModel.searchQuery = it
                    },
                    label = "SEARCH",
                    placeholder = "Search by name, service, type, or key...",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (searchQuery.isNotBlank()) {
                        Text(
                            text = "SEARCH_RESULTS: \"$searchQuery\"",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = "INVENTORY_MANIFEST",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "${credentials.size} entries",
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = Color.Gray,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(credentials) { credential ->
                CredentialCard(
                    credential = credential,
                    onClick = { onNavigateToDetails(credential.id) },
                    onRevealAndCopy = {
                        // 15-min session valid → copy directly
                        if (BiometricUtils.isSessionValid()) {
                            doCopy(credential)
                            return@CredentialCard
                        }
                        val activity = getActivity()
                        if (activity != null) {
                            if (BiometricUtils.canAuthenticate(activity)) {
                                BiometricUtils.requireBiometric(
                                    activity = activity,
                                    title = "Copy Credential",
                                    subtitle = "Biometric authentication required",
                                    onSuccess = { doCopy(credential) },
                                    onError = {
                                        pendingBiometricAction = { doCopy(credential) }
                                    },
                                )
                            } else {
                                pendingBiometricAction = { doCopy(credential) }
                            }
                        }
                    },
                    onDelete = { viewModel.deleteCredential(credential) },
                    onEdit = { onNavigateToEdit(credential.id) },
                    onEmailPasswordReveal = if (credential.type == CredentialType.Email) {
                        {
                            val activity = getActivity()
                            if (activity != null) {
                                if (BiometricUtils.canAuthenticate(activity)) {
                                    BiometricUtils.requireBiometric(
                                        activity = activity,
                                        title = "View Email Password",
                                        subtitle = "Biometric authentication required",
                                        onSuccess = { revealedEmailPassword = extractEmailPassword(credential.value) },
                                        onError = { viewModel.showToast("BIOMETRIC_FAILED: $it") },
                                    )
                                } else {
                                    revealedEmailPassword = extractEmailPassword(credential.value)
                                }
                            }
                        }
                    } else null,
                    revealedEmailPassword = if (credential.type == CredentialType.Email) revealedEmailPassword else null,
                    onHideEmailPassword = if (credential.type == CredentialType.Email) {
                        { revealedEmailPassword = null }
                    } else null,
                    isValueRevealed = revealedCredentialIds.contains(credential.id),
                    onHideValue = { id -> revealedCredentialIds.remove(id) },
                    onNeedReveal = if (credential.type == CredentialType.CodingPlan) {
                        { showNeedRevealToast() }
                    } else if (credential.type != CredentialType.Phone &&
                        credential.type != CredentialType.Email &&
                        credential.type != CredentialType.IdCard &&
                        credential.type != CredentialType.Note) {
                        { showNeedRevealToast() }
                    } else null,
                    onCopyStructured = { doCopyJsonStructured(credential) },
                    onCopyValue = { doCopyValue(credential) },
                    onCopyApiKey = if (credential.type == CredentialType.CodingPlan) {
                        { doCopyApiKey(credential) }
                    } else null,
                    onCopyBaseUrl = if (credential.type == CredentialType.CodingPlan) {
                        { doCopyBaseUrl(credential) }
                    } else null,
                    onCopyProvider = if (credential.type == CredentialType.CodingPlan) {
                        { doCopyProvider(credential) }
                    } else null,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (credentials.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "NO_CREDENTIALS_FOUND",
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
                        "> SYNCING_METADATA..." to IndustrialOrange,
                        "LOCKIT-DAEMON: LOCAL_STORAGE_ACTIVE" to Color.Gray,
                        "TOTAL_CREDENTIALS: ${credentials.size} [READY]" to Color.Gray,
                        if (services.isNotEmpty()) "SERVICES: ${services.joinToString(", ")}" to Color.Gray
                        else "NO_SERVICES_REGISTERED" to Color.Gray,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // PIN fallback when biometric unavailable
    pendingBiometricAction?.let { action ->
        BrutalistPinVerifyDialog(
            app = app,
            onVerified = {
                pendingBiometricAction = null
                action()
            },
            onDismiss = { pendingBiometricAction = null },
        )
    }

    toastMessage?.let { message ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val hasPhoneAction = message.startsWith("PHONE_COPIED")
            BrutalistToast(
                message = message,
                onDismiss = { viewModel.dismissToast() },
                onAction = if (hasPhoneAction) {
                    {
                        clipboardManager.setText(AnnotatedString(phoneRegionForToast))
                    }
                } else null,
            )
        }
    }
}

private class VaultExplorerViewModelFactory(
    private val app: LockitApp,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        VaultExplorerViewModel(app) as T
}
