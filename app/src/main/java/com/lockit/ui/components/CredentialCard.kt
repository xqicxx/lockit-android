package com.lockit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalClipboardManager
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.SurfaceLow
import com.lockit.utils.BiometricUtils

// Default display values for empty/missing fields
object CredentialDefaults {
    const val NOT_SET = "—"
    const val UNKNOWN = "UNKNOWN"
    const val FIELD_NOT_SET = "未设置"
    const val PROVIDER_UNKNOWN = "未知"
    const val MASK_PLACEHOLDER = "••••••••••••••••••••"
}

/**
 * Parse the combined credential value string into individual field values.
 */
fun parseCredentialFields(value: String): List<String> {
    return value.split(" // ").map { if (it == "-") "" else it }
}

/**
 * Build human-readable email address from parsed credential fields.
 */
fun buildEmailAddress(fields: List<String>): String {
    val prefix = fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: CredentialDefaults.UNKNOWN
    val provider = fields.getOrNull(0)?.takeIf { it.isNotBlank() }?.trimStart('@')
    return if (provider != null) "$prefix@$provider" else prefix
}

/**
 * Extract password from combined Email credential value (field index 2).
 */
fun extractEmailPassword(value: String): String {
    val fields = parseCredentialFields(value)
    return fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: CredentialDefaults.NOT_SET
}

/**
 * Build JSON structured string from credential and parsed fields.
 */
fun buildJsonStructured(credential: Credential, fields: List<String>): String {
    val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(java.time.ZoneOffset.UTC)
    val updatedAtStr = dateFormatter.format(credential.updatedAt)
    val createdAtStr = dateFormatter.format(credential.createdAt)
    return buildString {
        append("{\n")
        append("  \"name\": \"${credential.name}\",\n")
        append("  \"type\": \"${credential.type.displayName}\",\n")
        if (credential.service.isNotBlank()) append("  \"service\": \"${credential.service}\",\n")
        if (credential.key.isNotBlank()) append("  \"key\": \"${credential.key}\",\n")
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
}

/**
 * Extract the secret/value field from a credential's combined value string.
 */
fun extractSecretValue(type: CredentialType, value: String): String {
    return when (type) {
        CredentialType.Phone, CredentialType.BankCard,
        CredentialType.IdCard, CredentialType.Note -> value
        CredentialType.CodingPlan -> {
            val fields = parseCredentialFields(value)
            val rawCurl = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
            val apiKey = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
            rawCurl ?: apiKey ?: value
        }
        else -> parseCredentialFields(value).lastOrNull()?.takeIf { it.isNotBlank() } ?: value
    }
}

/**
 * Unified credential card with simplified callbacks.
 *
 * Copy actions are unified into onCopy callback with CopyAction enum:
 * - VALUE: Copy single value field
 * - STRUCTURED: Copy full JSON structure
 * - API_KEY: CodingPlan specific - copy API key
 * - BASE_URL: CodingPlan specific - copy base URL
 * - EMAIL: Email specific - copy email address
 * - PHONE: Phone specific - copy phone number
 */
enum class CopyAction {
    VALUE, STRUCTURED, API_KEY, BASE_URL, EMAIL, PHONE
}

@Composable
fun CredentialCard(
    credential: Credential,
    onClick: () -> Unit,
    /** Unified copy callback - receives CopyAction to determine what to copy */
    onCopy: (CopyAction) -> Unit,
    /** Request biometric reveal before sensitive operations */
    onNeedReveal: () -> Unit = {},
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    /** External reveal state management */
    isRevealed: Boolean = false,
    onHide: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var localRevealed by remember { mutableStateOf(isRevealed) }

    // Parse fields once at the top - used throughout the component
    val fields = remember(credential.value) { parseCredentialFields(credential.value) }

    // Sync with external reveal state and auto-hide when biometric cache expires
    val currentOnHide = rememberUpdatedState(onHide)
    LaunchedEffect(isRevealed) {
        localRevealed = isRevealed
        if (isRevealed && !BiometricUtils.isBiometricCacheValid()) {
            localRevealed = false
            currentOnHide.value()
        }
    }

    // Types that always show values (no reveal needed)
    val alwaysVisible = credential.type in listOf(
        CredentialType.Phone, CredentialType.Email,
        CredentialType.IdCard, CredentialType.Note
    )

    // Masked placeholder - computed once
    val maskPlaceholder = remember { CredentialDefaults.MASK_PLACEHOLDER }

    if (showDeleteDialog) {
        BrutalistConfirmDialog(
            title = "DELETE CREDENTIAL",
            message = "Permanently delete \"${credential.name}\"? This action cannot be undone.",
            confirmText = "DELETE",
            onConfirm = { showDeleteDialog = false; onDelete() },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Column(
        modifier = modifier
            .border(1.dp, Color.Black)
            .clickable(onClick = onClick)
            .pointerInput(localRevealed) {
                detectTapGestures(
                    onLongPress = {
                        // Long press card → copy structured JSON
                        if (localRevealed || alwaysVisible) {
                            onCopy(CopyAction.STRUCTURED)
                        } else {
                            onNeedReveal()
                        }
                    }
                )
            }
            .padding(16.dp),
    ) {
        // Header row with title and action buttons
        CardHeader(
            credential = credential,
            isRevealed = localRevealed,
            alwaysVisible = alwaysVisible,
            onRevealToggle = {
                if (localRevealed) {
                    localRevealed = false
                    currentOnHide.value()
                } else {
                    localRevealed = true
                }
            },
            onCopy = { onCopy(CopyAction.VALUE) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Type-specific content area
        CredentialContent(
            credential = credential,
            fields = fields,
            isRevealed = localRevealed,
            maskPlaceholder = maskPlaceholder,
            onCopy = onCopy,
            onNeedReveal = onNeedReveal,
            clipboardManager = clipboardManager,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Footer with tags and timestamp
        CardFooter(credential = credential)

        Spacer(modifier = Modifier.height(8.dp))

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrutalistButton(
                text = "DELETE",
                onClick = { showDeleteDialog = true },
                variant = ButtonVariant.Danger,
                modifier = Modifier.weight(1f).height(32.dp),
                useMonoFont = true,
            )
            BrutalistButton(
                text = "EDIT",
                onClick = onEdit,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.weight(1f).height(32.dp),
                useMonoFont = true,
            )
        }
    }
}

@Composable
fun IconButtonBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .border(1.dp, Primary)
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CardHeader(
    credential: Credential,
    isRevealed: Boolean,
    alwaysVisible: Boolean,
    onRevealToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = credential.displayTitle().uppercase(),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = credential.type.displayName +
                    if (credential.service.isNotBlank()) " // ${credential.service.uppercase()}" else "",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row {
            // Reveal/hide button for types that need it
            if (!alwaysVisible && credential.type != CredentialType.CodingPlan) {
                IconButtonBox(
                    icon = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    description = if (isRevealed) "Hide value" else "Reveal value",
                    onClick = onRevealToggle,
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButtonBox(
                    icon = Icons.Default.ContentCopy,
                    description = "Copy value",
                    onClick = onCopy,
                )
            }
        }
    }
}

@Composable
private fun CredentialContent(
    credential: Credential,
    fields: List<String>,
    isRevealed: Boolean,
    maskPlaceholder: String,
    onCopy: (CopyAction) -> Unit,
    onNeedReveal: () -> Unit,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
) {
    // All types now use unified display logic
    // Phone/Email/IdCard/Note: always visible, show all fields
    // CodingPlan: show API_KEY + BASE_URL with reveal toggle
    // Others: show single value with reveal toggle

    when (credential.type) {
        CredentialType.Phone -> {
            // Phone: show number, note, region
            val phoneNumber = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: credential.service.takeIf { it.isNotBlank() }
                ?: CredentialDefaults.UNKNOWN
            val region = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ""
            val note = fields.getOrNull(2)?.takeIf { it.isNotBlank() }

            FieldValueBox(label = "PHONE NUMBER", value = phoneNumber)
            if (note != null) {
                Spacer(modifier = Modifier.height(8.dp))
                FieldValueBox(label = "NOTE", value = note, maxLines = 3)
            }
            if (region.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                FieldLabelValueRow("REGION", region)
            }
        }

        CredentialType.Email -> {
            // Email: show address + password (password needs reveal)
            val emailAddress = buildEmailAddress(fields)
            val password = fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: CredentialDefaults.NOT_SET

            FieldValueBox(label = "EMAIL", value = emailAddress)
            Spacer(modifier = Modifier.height(8.dp))

            // Password row with reveal toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel("PASSWORD")
                IconButtonBox(
                    icon = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    description = if (isRevealed) "Hide" else "Reveal",
                    onClick = { if (isRevealed) onNeedReveal() else {} },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            FieldValueBox(
                label = "",
                value = if (isRevealed) password else maskPlaceholder,
                maxLines = 5,
            )

            // Address fields
            fields.drop(3).forEachIndexed { idx, value ->
                if (value.isNotBlank() && value != "-") {
                    Spacer(modifier = Modifier.height(6.dp))
                    FieldLabelValueRow(
                        listOf("REGION", "STREET", "CITY", "STATE/ZIP").getOrNull(idx) ?: "FIELD",
                        value
                    )
                }
            }
        }

        CredentialType.CodingPlan -> {
            // CodingPlan: API_KEY + BASE_URL
            val apiKey = fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: CredentialDefaults.FIELD_NOT_SET
            val baseUrl = fields.getOrNull(5)?.takeIf { it.isNotBlank() } ?: CredentialDefaults.FIELD_NOT_SET

            // API_KEY row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel("API_KEY")
                IconButtonBox(
                    icon = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    description = if (isRevealed) "Hide" else "Reveal",
                    onClick = { if (isRevealed) onNeedReveal() else {} },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            FieldValueBox(
                label = "",
                value = if (isRevealed) apiKey else maskPlaceholder,
                maxLines = 3,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // BASE_URL row (always visible)
            FieldLabel("BASE_URL")
            Spacer(modifier = Modifier.height(4.dp))
            FieldValueBox(label = "", value = baseUrl, maxLines = 2)
        }

        else -> {
            // Default: single value with reveal toggle
            val alwaysVisible = credential.type in listOf(CredentialType.IdCard, CredentialType.Note)
            val displayValue = if (alwaysVisible || isRevealed) {
                extractSecretValue(credential.type, credential.value)
            } else maskPlaceholder

            FieldValueBox(
                label = "VALUE",
                value = displayValue,
                maxLines = if (alwaysVisible || isRevealed) Int.MAX_VALUE else 1,
            )
        }
    }
}

@Composable
private fun FieldLabelValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldLabel(label)
        FieldValue(value)
    }
}

@Composable
private fun CardFooter(credential: Credential) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row {
            val tagText = when (credential.type) {
                CredentialType.Phone -> credential.name.uppercase()
                CredentialType.Account -> credential.service.uppercase().takeIf { it.isNotBlank() } ?: "SERVICE"
                else -> credential.service.uppercase()
            }
            if (tagText.isNotBlank()) {
                InfoTag(text = tagText)
                Spacer(modifier = Modifier.width(6.dp))
            }
            InfoTag(text = credential.type.displayName)
        }
        Text(
            text = "Secure // ${credential.formatUpdatedAt()}",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = IndustrialOrange,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FieldValueBox(
    label: String,
    value: String,
    maxLines: Int = 1,
) {
    Column {
        if (label.isNotBlank()) {
            FieldLabel(label)
            Spacer(modifier = Modifier.height(4.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceLow)
                .border(1.dp, Primary)
                .padding(12.dp),
        ) {
            Text(
                text = value,
                fontFamily = JetBrainsMonoFamily,
                fontSize = if (maxLines == 1) 14.sp else 11.sp,
                fontWeight = if (maxLines == 1) FontWeight.Bold else FontWeight.Normal,
                color = Primary,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        color = IndustrialOrange,
    )
}

@Composable
private fun FieldValue(text: String, maxLines: Int = 1) {
    Text(
        text = text,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 11.sp,
        color = Primary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
