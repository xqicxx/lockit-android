package com.lockit.ui.screens.secret_details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.data.audit.AuditSeverity
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import com.lockit.ui.components.BrutalistButton
import com.lockit.ui.components.BrutalistCard
import com.lockit.ui.components.BrutalistConfirmDialog
import com.lockit.ui.components.BrutalistToast
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.ButtonVariant
import com.lockit.ui.components.IconPosition
import com.lockit.ui.components.InfoTag
import com.lockit.ui.components.buildEmailAddress
import com.lockit.ui.components.extractEmailPassword
import com.lockit.ui.components.extractSecretValue
import com.lockit.ui.components.findActivity
import com.lockit.ui.components.formatTime
import com.lockit.ui.components.parseCredentialFields
import com.lockit.ui.screens.auth.WebViewAuthActivity
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import com.lockit.utils.BiometricUtils
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

@Composable
fun SecretDetailsScreen(
    credentialId: String,
    app: LockitApp,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var credential by remember { mutableStateOf<Credential?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isRevealed by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var revealedEmailPassword by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    fun getActivity() = view.findActivity()

    // WebView auth launcher for refreshing CodingPlan credentials
    val webViewAuthLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == WebViewAuthActivity.RESULT_SUCCESS) {
            val credentialData = result.data?.getStringExtra(WebViewAuthActivity.EXTRA_CREDENTIAL_DATA)
            if (credentialData != null && credential != null) {
                val json = JSONObject(credentialData)
                val newMetadata = mutableMapOf<String, String>()
                json.keys().forEach { key ->
                    newMetadata[key] = json.optString(key, "")
                }
                // Update credential with new auth data
                scope.launch {
                    val currentCred = credential
                    if (currentCred != null) {
                        // Serialize metadata as proper JSON (not Map.toString())
                        val metadataJson = JSONObject(newMetadata as Map<*, *>).toString()
                        // Reconstruct value string with all 5 CodingPlan fields
                        // Format: PROVIDER // RAW_CURL // API_KEY // COOKIE // BASE_URL
                        val provider = newMetadata["provider"] ?: ""
                        val rawCurl = newMetadata["rawCurl"] ?: ""
                        val apiKey = newMetadata["apiKey"] ?: ""
                        val cookie = newMetadata["cookie"] ?: ""
                        val baseUrl = newMetadata["baseUrl"] ?: ""
                        val newValue = "$provider // $rawCurl // $apiKey // $cookie // $baseUrl"
                        app.vaultManager.updateCredential(
                            id = currentCred.id,
                            name = currentCred.name,
                            type = currentCred.type,
                            service = currentCred.service,
                            key = currentCred.key,
                            value = newValue,
                            metadata = metadataJson,
                        )
                        // Reload credential
                        credential = app.vaultManager.getCredentialById(credentialId)
                        toastMessage = "AUTH_REFRESHED: ${credential?.name ?: "UNKNOWN"}"
                    }
                }
            }
        } else if (result.resultCode == WebViewAuthActivity.RESULT_FAILED) {
            toastMessage = "AUTH_REFRESH_FAILED"
        }
    }

    LaunchedEffect(credentialId) {
        isLoading = true
        try {
            credential = app.vaultManager.getCredentialById(credentialId)
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    if (showDeleteDialog) {
        credential?.let { credToDelete ->
            BrutalistConfirmDialog(
                title = "DELETE CREDENTIAL",
                message = "Permanently delete \"${credToDelete.name}\"? This action cannot be undone.",
                confirmText = "DELETE",
                onConfirm = {
                    showDeleteDialog = false
                    scope.launch {
                        app.vaultManager.deleteCredential(credToDelete)
                        toastMessage = "CREDENTIAL_DELETED: ${credToDelete.name}"
                        kotlinx.coroutines.delay(500)
                        onDelete()
                    }
                },
                onDismiss = { showDeleteDialog = false },
            )
        }
    }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "LOADING...",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val cred = credential
    if (cred == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error ?: "CREDENTIAL_NOT_FOUND",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 14.sp,
                    color = TacticalRed,
                )
                Spacer(modifier = Modifier.height(16.dp))
                BrutalistButton(
                    text = "GO_BACK",
                    onClick = onBack,
                    variant = ButtonVariant.Secondary,
                    useMonoFont = true,
                )
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        BrutalistTopBar(showBackButton = true, onBackClick = onBack)

        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            item {
                Spacer(modifier = Modifier.height(16.dp))

                // Title + type tags
                Text(
                    text = cred.displayTitle().uppercase(),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoTag(text = cred.type.displayName)
                    if (cred.service.isNotBlank()) {
                        InfoTag(text = cred.service.uppercase())
                    }
                    if (cred.key.isNotBlank()) {
                        InfoTag(text = cred.key)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ID: ${cred.refId()} // Created: ${cred.formatCreatedAt()}",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Credential value section - type-aware
            item {
                SecretValueSection(
                    credential = cred,
                    isRevealed = isRevealed,
                    onReveal = { isRevealed = !isRevealed },
                    onCopy = {
                        val activity = getActivity()
                        if (activity != null) {
                            BiometricUtils.requireBiometric(
                                activity = activity,
                                title = "Copy Credential",
                                subtitle = "Biometric authentication required",
                                onSuccess = {
                                    if (cred.type == CredentialType.Phone) {
                                        clipboardManager.setText(AnnotatedString(cred.service))
                                    } else if (cred.type == CredentialType.Email) {
                                        val emailAddr = buildEmailAddress(parseCredentialFields(cred.value))
                                        clipboardManager.setText(AnnotatedString(emailAddr))
                                    } else {
                                        clipboardManager.setText(AnnotatedString(extractSecretValue(cred.type, cred.value)))
                                    }
                                    app.vaultManager.logCredentialCopied(cred.name)
                                    toastMessage = "CREDENTIAL_COPIED: ${cred.name}"
                                },
                                onError = { toastMessage = "BIOMETRIC_FAILED: $it" },
                            )
                        }
                    },
                    onEmailPasswordReveal = if (cred.type == CredentialType.Email) {
                        {
                            val activity = getActivity()
                            if (activity != null) {
                                BiometricUtils.requireBiometric(
                                    activity = activity,
                                    title = "View Email Password",
                                    subtitle = "Biometric authentication required",
                                    onSuccess = {
                                        revealedEmailPassword = extractEmailPassword(cred.value)
                                    },
                                    onError = { toastMessage = "BIOMETRIC_FAILED: $it" },
                                )
                            }
                        }
                    } else null,
                    revealedEmailPassword = revealedEmailPassword,
                    onHideEmailPassword = if (cred.type == CredentialType.Email) {
                        { revealedEmailPassword = null }
                    } else null,
                    onRefreshAuth = if (cred.type == CredentialType.CodingPlan) {
                        {
                            val provider = try {
                                org.json.JSONObject(cred.metadata).optString("provider", "qwen_bailian")
                            } catch (e: Exception) { "qwen_bailian" }
                            val intent = WebViewAuthActivity.createIntent(context, provider)
                            webViewAuthLauncher.launch(intent)
                        }
                    } else null,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                MetadataGrid(credential = cred)
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                AuditLogSection(app = app, credential = cred)
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                DangerZone(
                    onDelete = { showDeleteDialog = true },
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Toast notification
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
private fun PillBadge(text: String, bg: Color? = null, fg: Color? = null) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(bg ?: colorScheme.surfaceContainerHighest)
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg ?: colorScheme.onSurface,
        )
    }
}

@Composable
private fun SecretValueSection(
    credential: Credential,
    isRevealed: Boolean,
    onReveal: () -> Unit,
    onCopy: () -> Unit,
    onEmailPasswordReveal: (() -> Unit)? = null,
    revealedEmailPassword: String? = null,
    onHideEmailPassword: (() -> Unit)? = null,
    onRefreshAuth: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    BrutalistCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CREDENTIAL_DATA",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Show structured data based on credential type
            when (credential.type) {
                CredentialType.CodingPlan -> {
                    val metadata = parseCredentialFields(credential.value)
                    val provider = metadata.getOrNull(0) ?: credential.metadata.let { meta ->
                        try {
                            org.json.JSONObject(meta).optString("provider", "")
                        } catch (e: Exception) { "" }
                    }.takeIf { it.isNotBlank() } ?: "qwen_bailian"

                    // Provider info
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                    ) {
                        Column {
                            Text(
                                text = "PROVIDER",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                                color = IndustrialOrange,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = provider.uppercase(),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    // API Key (if available)
                    val apiKey = metadata.getOrNull(2)?.takeIf { it.isNotBlank() }
                        ?: credential.metadata.let { meta ->
                            try { org.json.JSONObject(meta).optString("apiKey", "") } catch (e: Exception) { "" }
                        }.takeIf { it.isNotBlank() }

                    if (apiKey != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                        ) {
                            Column {
                                Text(
                                    text = "API_KEY",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp,
                                    color = IndustrialOrange,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isRevealed) apiKey else "•".repeat(20),
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    color = if (isRevealed) colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (isRevealed) 3 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Base URL (if available)
                    val baseUrl = metadata.getOrNull(4)?.takeIf { it.isNotBlank() }
                        ?: credential.metadata.let { meta ->
                            try { org.json.JSONObject(meta).optString("baseUrl", "") } catch (e: Exception) { "" }
                        }.takeIf { it.isNotBlank() }

                    if (baseUrl != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                        ) {
                            Column {
                                Text(
                                    text = "BASE_URL",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp,
                                    color = IndustrialOrange,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = baseUrl,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                CredentialType.Phone -> {
                    val phoneFields = parseCredentialFields(credential.value)
                    val phoneNumber = phoneFields.getOrNull(1)?.takeIf { it.isNotBlank() }
                        ?: credential.service.takeIf { it.isNotBlank() } ?: "UNKNOWN"
                    val region = phoneFields.getOrNull(0)?.takeIf { it.isNotBlank() }
                        ?: credential.name.takeIf { it.isNotBlank() } ?: ""
                    val note = phoneFields.getOrNull(2)?.takeIf { it.isNotBlank() }
                        ?: credential.key.takeIf { it.isNotBlank() }

                    // Phone number
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                    ) {
                        Column {
                            Text(
                                text = "PHONE NUMBER",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                                color = IndustrialOrange,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = phoneNumber,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (region.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                        ) {
                            Column {
                                Text(
                                    text = "REGION",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp,
                                    color = IndustrialOrange,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = region,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    if (note != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                        ) {
                            Column {
                                Text(
                                    text = "NOTE",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp,
                                    color = IndustrialOrange,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = note,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                CredentialType.Email -> {
                    val emailFields = parseCredentialFields(credential.value)
                    val emailAddress = buildEmailAddress(emailFields)

                    // Email address
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                    ) {
                        Column {
                            Text(
                                text = "EMAIL",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                                color = IndustrialOrange,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = emailAddress,
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    // Password row
                    Spacer(modifier = Modifier.height(8.dp))
                    val passwordValue = emailFields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "—"
                    val passwordRevealed = revealedEmailPassword != null
                    val displayPassword = revealedEmailPassword ?: passwordValue
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "PASSWORD",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.sp,
                                    color = IndustrialOrange,
                                )
                                if (passwordRevealed) {
                                    com.lockit.ui.components.IconButtonBox(
                                        icon = Icons.Default.VisibilityOff,
                                        description = "Hide password",
                                        onClick = { onHideEmailPassword?.invoke() },
                                    )
                                } else {
                                    com.lockit.ui.components.IconButtonBox(
                                        icon = Icons.Default.Fingerprint,
                                        description = "Reveal with biometric",
                                        onClick = { onEmailPasswordReveal?.invoke() },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (passwordRevealed) displayPassword else "\u2022".repeat(20),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 13.sp,
                                color = if (passwordRevealed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (passwordRevealed) 5 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Other fields
                    val displayFields = listOf(
                        "REGION" to emailFields.getOrNull(3),
                        "STREET" to emailFields.getOrNull(4),
                        "CITY" to emailFields.getOrNull(5),
                        "STATE/ZIP" to emailFields.getOrNull(6),
                    ).filter { (_, v) -> v?.isNotBlank() == true && v != "-" }

                    if (displayFields.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            displayFields.forEach { (label, value) ->
                                Box(
                                    modifier = Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(12.dp),
                                ) {
                                    Column {
                                        Text(
                                            text = label,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            letterSpacing = 1.sp,
                                            color = IndustrialOrange,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = value ?: "",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Generic credential type
                    val displayValue = when (credential.type) {
                        CredentialType.IdCard, CredentialType.Note -> credential.value
                        else -> if (isRevealed) extractSecretValue(credential.type, credential.value) else "\u2022".repeat(40)
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)).padding(16.dp),
                    ) {
                        Text(
                            text = displayValue,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 14.sp,
                            color = if (isRevealed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (credential.type != CredentialType.Email && credential.type != CredentialType.Phone
                    && credential.type != CredentialType.IdCard && credential.type != CredentialType.Note
                    && credential.type != CredentialType.CodingPlan) {
                    BrutalistButton(
                        text = if (isRevealed) "HIDE" else "REVEAL",
                        onClick = onReveal,
                        variant = ButtonVariant.Primary,
                        modifier = Modifier.weight(1f),
                        useMonoFont = true,
                        icon = Icons.Default.Visibility,
                        iconPosition = IconPosition.Start,
                    )
                }
                if (credential.type == CredentialType.CodingPlan && onRefreshAuth != null) {
                    BrutalistButton(
                        text = "REFRESH_AUTH",
                        onClick = onRefreshAuth,
                        variant = ButtonVariant.Primary,
                        modifier = Modifier.weight(1f),
                        useMonoFont = true,
                        icon = Icons.Default.Refresh,
                        iconPosition = IconPosition.Start,
                    )
                }
                BrutalistButton(
                    text = "COPY",
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    useMonoFont = true,
                    icon = Icons.Default.Fingerprint,
                    iconPosition = IconPosition.Start,
                )
            }
        }
    }
}

@Composable
private fun MetadataGrid(credential: Credential) {
    Row(modifier = Modifier.fillMaxWidth()) {
        MetaCard("CREATED", credential.formatCreatedAt(), Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        MetaCard("UPDATED", credential.formatUpdatedAt(), Modifier.weight(1f))
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(modifier = Modifier.fillMaxWidth()) {
        MetaCard("SERVICE", credential.service.uppercase(), Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        MetaCard("KEY", credential.key, Modifier.weight(1f))
    }
}

@Composable
private fun MetaCard(label: String, value: String, modifier: Modifier = Modifier) {
    BrutalistCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AuditLogSection(app: LockitApp, credential: Credential) {
    val colorScheme = MaterialTheme.colorScheme
    val logs = remember(credential.id) {
        app.auditLogger.getRecentEntries(days = 30)
            .filter { it.action.contains("CREDENTIAL") }
            .take(10)
    }

    BrutalistCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.onSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AUDIT_LOG_STREAM",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = White,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = "${logs.size} events",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = White.copy(0.7f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (logs.isEmpty()) {
                Text(
                    text = "NO_AUDIT_EVENTS",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            } else {
                logs.forEachIndexed { index, entry ->
                    val icon = when (entry.action) {
                        "CREDENTIAL_CREATED" -> "+"
                        "CREDENTIAL_UPDATED" -> "~"
                        "CREDENTIAL_DELETED" -> "-"
                        "CREDENTIAL_VIEWED" -> "EYE"
                        "CREDENTIAL_COPIED" -> "COPY"
                        else -> "INFO"
                    }
                    val color = when (entry.severity) {
                        AuditSeverity.Info -> colorScheme.onSurface
                        AuditSeverity.Warning -> IndustrialOrange
                        AuditSeverity.Danger -> TacticalRed
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(color.copy(0.15f))
                                    .border(1.dp, color.copy(0.3f))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = icon,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = color,
                                )
                            }
                            Column {
                                Text(
                                    text = entry.action,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = color,
                                )
                                Text(
                                    text = entry.detail,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = formatTime(Instant.ofEpochMilli(entry.timestamp)),
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    if (index < logs.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), thickness = 1.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DangerZone(onDelete: () -> Unit) {
    BrutalistCard(modifier = Modifier.border(2.dp, TacticalRed)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DANGER_ZONE_RESTRICTED",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TacticalRed,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            BrutalistButton(
                text = "REVOKE_ALL_ACCESS",
                onClick = { /* Not implemented in MVP - no active sessions to revoke */ },
                variant = ButtonVariant.Revoke,
                modifier = Modifier.fillMaxWidth(),
                useMonoFont = true,
                icon = Icons.Default.Block,
                iconPosition = IconPosition.End,
            )

            Spacer(modifier = Modifier.height(8.dp))

            BrutalistButton(
                text = "DELETE_PERMANENTLY",
                onClick = onDelete,
                variant = ButtonVariant.Danger,
                modifier = Modifier.fillMaxWidth(),
                useMonoFont = true,
                icon = Icons.Default.Delete,
                iconPosition = IconPosition.End,
            )
        }
    }
}


