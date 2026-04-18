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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.lockit.R
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import com.lockit.ui.components.BrutalistPinVerifyDialog
import com.lockit.ui.components.BrutalistTextField
import com.lockit.ui.components.BrutalistToast
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.CopyAction
import com.lockit.ui.components.CredentialCard
import com.lockit.ui.components.CredentialDefaults
import com.lockit.ui.components.InfoTag
import com.lockit.ui.components.ScreenHero
import com.lockit.ui.components.TerminalFooter
import com.lockit.ui.components.TopBarAddButton
import com.lockit.ui.components.buildEmailAddress
import com.lockit.ui.components.buildJsonStructured
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

// Toast data class for localization-safe toast messages
data class ToastData(
    val resourceId: Int? = null,
    val formatArgs: List<Any> = emptyList(),
    val rawMessage: String? = null,
)

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

    private val _toastData = MutableStateFlow<ToastData?>(null)
    val toastData: StateFlow<ToastData?> = _toastData.asStateFlow()

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
                _toastData.value = ToastData(
                    resourceId = R.string.toast_credential_deleted,
                    formatArgs = listOf(credential.name)
                )
            } catch (e: Exception) {
                _toastData.value = ToastData(rawMessage = "ERROR: ${e.message}")
            }
        }
    }

    fun showToast(message: String) {
        _toastData.value = ToastData(rawMessage = message)
    }

    fun showCopyToast(actionName: String) {
        val resourceId = when (actionName) {
            "VALUE" -> R.string.toast_value_copied
            "STRUCTURED" -> R.string.toast_structured_copied
            "API_KEY" -> R.string.toast_api_key_copied
            "BASE_URL" -> R.string.toast_base_url_copied
            "EMAIL" -> R.string.toast_email_copied
            "PHONE" -> R.string.toast_phone_copied
            else -> null
        }
        if (resourceId != null) {
            _toastData.value = ToastData(resourceId = resourceId)
        } else {
            _toastData.value = ToastData(rawMessage = "${actionName}_COPIED")
        }
    }

    fun dismissToast() {
        _toastData.value = null
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
    val toastData by viewModel.toastData.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf(viewModel.searchQuery) }
    var phoneRegionForToast by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val services by remember { derivedStateOf { credentials.map { it.service.uppercase() }.distinct() } }
    val revealedCredentialIds = remember { mutableStateListOf<String>() }
    val view = LocalView.current

    fun getActivity() = view.findActivity()

    var pendingBiometricAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Pre-compute string resources for use in non-composable callbacks
    val biometricRevealTitle = stringResource(R.string.biometric_reveal_title)
    val biometricRevealSubtitle = stringResource(R.string.biometric_reveal_subtitle)
    val biometricCopyTitle = stringResource(R.string.biometric_copy_title)
    val biometricCopySubtitle = stringResource(R.string.biometric_copy_subtitle)

    fun handleCopy(credential: Credential, action: CopyAction) {
        val fields = parseCredentialFields(credential.value)
        val valueToCopy = when (action) {
            CopyAction.VALUE -> extractSecretValue(credential.type, credential.value)
            CopyAction.STRUCTURED -> buildJsonStructured(credential, fields)
            CopyAction.API_KEY -> fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return
            CopyAction.BASE_URL -> fields.getOrNull(5)?.takeIf { it.isNotBlank() } ?: return
            CopyAction.EMAIL -> buildEmailAddress(fields)
            CopyAction.PHONE -> fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: credential.service
        }
        clipboardManager.setText(AnnotatedString(valueToCopy))
        app.vaultManager.logCredentialCopied(credential.name)
        viewModel.showCopyToast(action.name)
        if (action == CopyAction.PHONE) {
            phoneRegionForToast = credential.name
        }
    }

    fun handleReveal(credential: Credential) {
        if (BiometricUtils.isSessionValid()) {
            revealedCredentialIds.add(credential.id)
            return
        }
        val activity = getActivity()
        if (activity != null && BiometricUtils.canAuthenticate(activity)) {
            BiometricUtils.requireBiometric(
                activity = activity,
                title = biometricRevealTitle,
                subtitle = biometricRevealSubtitle,
                onSuccess = { revealedCredentialIds.add(credential.id) },
                onError = { pendingBiometricAction = { revealedCredentialIds.add(credential.id) } },
            )
        } else {
            pendingBiometricAction = { revealedCredentialIds.add(credential.id) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        BrutalistTopBar(
            rightContent = {
                TopBarAddButton(onClick = onNavigateToAdd)
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 140.dp),
        ) {
            item {
                ScreenHero(
                    title = stringResource(R.string.explorer_title),
                    subtitle = stringResource(R.string.explorer_subtitle),
                )
                Spacer(modifier = Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Primary)
                        .padding(16.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.explorer_quick_start),
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = IndustrialOrange,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.explorer_quick_start_desc),
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
                    label = stringResource(R.string.explorer_search),
                    placeholder = stringResource(R.string.explorer_search_placeholder),
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
                            text = stringResource(R.string.explorer_search_results) + " \"$searchQuery\"",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.explorer_manifest),
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "${credentials.size} " + stringResource(R.string.explorer_entries),
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
                    onCopy = { action ->
                        if (BiometricUtils.isSessionValid()) {
                            handleCopy(credential, action)
                            return@CredentialCard
                        }
                        val activity = getActivity()
                        if (activity != null) {
                            if (BiometricUtils.canAuthenticate(activity)) {
                                BiometricUtils.requireBiometric(
                                    activity = activity,
                                    title = biometricCopyTitle,
                                    subtitle = biometricCopySubtitle,
                                    onSuccess = { handleCopy(credential, action) },
                                    onError = {
                                        pendingBiometricAction = { handleCopy(credential, action) }
                                    },
                                )
                            } else {
                                pendingBiometricAction = { handleCopy(credential, action) }
                            }
                        }
                    },
                    onNeedReveal = { handleReveal(credential) },
                    onDelete = { viewModel.deleteCredential(credential) },
                    onEdit = { onNavigateToEdit(credential.id) },
                    isRevealed = revealedCredentialIds.contains(credential.id),
                    onHide = { revealedCredentialIds.remove(credential.id) },
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
                            text = stringResource(R.string.explorer_no_credentials),
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
                        stringResource(R.string.explorer_daemon) to Color.Gray,
                        stringResource(R.string.explorer_total_credentials) + " ${credentials.size}" to Color.Gray,
                        if (services.isNotEmpty()) stringResource(R.string.explorer_services) + " ${services.take(3).joinToString(", ")}${if (services.size > 3) "..." else ""}" to Color.Gray
                        else stringResource(R.string.explorer_no_services) to Color.Gray,
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

    toastData?.let { data ->
        // Resolve the localized string from ToastData
        val message = when {
            data.resourceId != null -> {
                if (data.formatArgs.isNotEmpty()) {
                    stringResource(data.resourceId, *data.formatArgs.toTypedArray())
                } else {
                    stringResource(data.resourceId)
                }
            }
            data.rawMessage != null -> data.rawMessage
            else -> ""
        }

        val hasPhoneAction = data.resourceId == R.string.toast_phone_copied ||
            data.rawMessage?.startsWith("PHONE_COPIED") == true
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
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
