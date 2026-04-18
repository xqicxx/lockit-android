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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.ClipboardManager
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
    const val FIELD_NOT_SET = "NOT_SET"
    const val PROVIDER_UNKNOWN = "UNKNOWN"
    const val MASK_PLACEHOLDER = "••••••••••••••••••••"
}

/**
 * Parse the combined credential value string into individual field values.
 */
fun parseCredentialFields(value: String): List<String> {
    return value.split(" // ").map { if (it == "-") "" else it }
}

/**
 * Extension function to get a non-blank field value from parsed fields.
 * Returns null if index out of bounds or value is blank.
 */
fun List<String>.getNotBlank(index: Int): String? = getOrNull(index)?.takeIf { it.isNotBlank() }

/**
 * Build human-readable email address from parsed credential fields.
 */
fun buildEmailAddress(fields: List<String>): String {
    val prefix = fields.getNotBlank(1) ?: CredentialDefaults.UNKNOWN
    val provider = fields.getNotBlank(0)?.trimStart('@')
    return if (provider != null) "$prefix@$provider" else prefix
}

/**
 * Extract password from combined Email credential value (field index 2).
 */
fun extractEmailPassword(value: String): String {
    val fields = parseCredentialFields(value)
    return fields.getNotBlank(2) ?: CredentialDefaults.NOT_SET
}

/**
 * Escape string for JSON serialization (handles quotes, backslashes, control characters).
 * RFC 8259 requires escaping U+0000 through U+001F.
 */
private fun escapeJsonString(value: String): String {
    return buildString {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (c.code in 0..31) {
                        append("\\u${c.code.toString(16).padStart(4, '0')}")
                    } else {
                        append(c)
                    }
                }
            }
        }
    }
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
        append("  \"name\": \"${escapeJsonString(credential.name)}\",\n")
        append("  \"type\": \"${escapeJsonString(credential.type.displayName)}\",\n")
        if (credential.service.isNotBlank()) append("  \"service\": \"${escapeJsonString(credential.service)}\",\n")
        if (credential.key.isNotBlank()) append("  \"key\": \"${escapeJsonString(credential.key)}\",\n")
        credential.type.fields.forEachIndexed { index, field ->
            val value = fields.getNotBlank(index)
            if (value != null) {
                val fieldName = field.label.lowercase().replace(" ", "_")
                append("  \"$fieldName\": \"${escapeJsonString(value)}\",\n")
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
            fields.getNotBlank(1) ?: fields.getNotBlank(2) ?: value
        }
        CredentialType.GitHub -> {
            val fields = parseCredentialFields(value)
            fields.getNotBlank(3) ?: value
        }
        else -> parseCredentialFields(value).getNotBlank(parseCredentialFields(value).lastIndex) ?: value
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
                    // Trigger biometric auth before revealing - don't bypass security
                    onNeedReveal()
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
            onHide = { localRevealed = false; currentOnHide.value() },
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
            // Reveal/hide button in header ONLY for types that don't have inline reveal in content
            // CodingPlan and GitHub have RevealableValueBox in content, so skip header icon
            if (!alwaysVisible && credential.type != CredentialType.CodingPlan && credential.type != CredentialType.GitHub) {
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
    onHide: () -> Unit,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
) {
    // All types now use unified display logic
    // Phone/Email/IdCard/Note: always visible, show all fields
    // CodingPlan: show API_KEY + BASE_URL with reveal toggle
    // Others: show single value with reveal toggle

    when (credential.type) {
        CredentialType.Phone -> {
            // Phone: show number, note, region
            val phoneNumber = fields.getNotBlank(1)
                ?: credential.service.takeIf { it.isNotBlank() }
                ?: CredentialDefaults.UNKNOWN
            val region = fields.getNotBlank(0) ?: ""
            val note = fields.getNotBlank(2)

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
            val password = fields.getNotBlank(2) ?: CredentialDefaults.NOT_SET

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
                    onClick = { if (!isRevealed) onNeedReveal() else onHide() },
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
            val apiKey = fields.getNotBlank(2) ?: CredentialDefaults.FIELD_NOT_SET
            val baseUrl = fields.getNotBlank(5) ?: CredentialDefaults.FIELD_NOT_SET

            // API_KEY - revealable
            RevealableValueBox(
                label = "API_KEY",
                value = apiKey,
                isRevealed = isRevealed,
                maskPlaceholder = maskPlaceholder,
                maxLinesRevealed = 3,
                onNeedReveal = onNeedReveal,
                onHide = onHide,
                onCopy = { onCopy(CopyAction.API_KEY) },
                clipboardManager = clipboardManager,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // BASE_URL - always visible
            FieldLabel("BASE_URL")
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceLow)
                    .border(1.dp, Primary)
                    .padding(12.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                if (baseUrl != CredentialDefaults.FIELD_NOT_SET) {
                                    clipboardManager.setText(AnnotatedString(baseUrl))
                                    onCopy(CopyAction.BASE_URL)
                                }
                            }
                        )
                    },
            ) {
                Text(
                    text = baseUrl,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 13.sp,
                    color = Primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        CredentialType.GitHub -> {
            // GitHub: show TOKEN_VALUE with reveal toggle, plus NAME, ACCOUNT, TYPE, SCOPE
            val name = fields.getNotBlank(0)
            val tokenType = fields.getNotBlank(1)
            val account = fields.getNotBlank(2)
            val tokenValue = fields.getNotBlank(3) ?: CredentialDefaults.FIELD_NOT_SET
            val scope = fields.getNotBlank(4)

            // TOKEN_VALUE - revealable
            RevealableValueBox(
                label = "TOKEN",
                value = tokenValue,
                isRevealed = isRevealed,
                maskPlaceholder = maskPlaceholder,
                maxLinesRevealed = 5,
                onNeedReveal = onNeedReveal,
                onHide = onHide,
                onCopy = { onCopy(CopyAction.VALUE) },
                clipboardManager = clipboardManager,
            )

            // Show optional fields with consistent spacing
            OptionalFieldRow("NAME", name, showIf = { it != credential.name })
            OptionalFieldRow("ACCOUNT", account)
            OptionalFieldRow("TYPE", tokenType)
            OptionalFieldRow("SCOPE", scope)
        }

        CredentialType.Account -> {
            // Account: show USERNAME + EMAIL + PASSWORD (password needs reveal)
            val username = fields.getNotBlank(0) ?: credential.name
            val email = fields.getNotBlank(2)
            val password = fields.getNotBlank(3) ?: CredentialDefaults.FIELD_NOT_SET

            FieldValueBox(label = "USERNAME", value = username)

            // EMAIL - optional, show if present
            if (email != null) {
                Spacer(modifier = Modifier.height(8.dp))
                FieldValueBox(label = "EMAIL", value = email)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // PASSWORD - revealable
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel("PASSWORD")
                IconButtonBox(
                    icon = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    description = if (isRevealed) "Hide" else "Reveal",
                    onClick = { if (!isRevealed) onNeedReveal() else onHide() },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            FieldValueBox(
                label = "",
                value = if (isRevealed) password else maskPlaceholder,
                maxLines = 3,
            )
        }

        else -> {
            // Default: single value with reveal toggle
            // Use pre-parsed fields instead of extractSecretValue to avoid redundant parsing
            val alwaysVisible = credential.type in listOf(CredentialType.IdCard, CredentialType.Note)
            val displayValue = if (alwaysVisible || isRevealed) {
                fields.lastOrNull()?.takeIf { it.isNotBlank() } ?: credential.value
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

/**
 * Helper composable for optional field rows with consistent spacing.
 * Only renders if value is non-null and passes showIf condition.
 */
@Composable
private fun OptionalFieldRow(
    label: String,
    value: String?,
    spacerHeight: Dp = 8.dp,
    showIf: (String) -> Boolean = { true },
) {
    if (value != null && showIf(value)) {
        Spacer(modifier = Modifier.height(spacerHeight))
        FieldLabelValueRow(label, value)
    }
}

@Composable
private fun CardFooter(credential: Credential) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tags row with weight to constrain width and prevent overlap
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

/**
 * Reusable revealable value box with label, reveal toggle, and long-press copy.
 * Used by CodingPlan (API_KEY) and GitHub (TOKEN) credential types.
 */
@Composable
private fun RevealableValueBox(
    label: String,
    value: String,
    isRevealed: Boolean,
    maskPlaceholder: String,
    maxLinesRevealed: Int = 3,
    onNeedReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    clipboardManager: ClipboardManager,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldLabel(label)
        IconButtonBox(
            icon = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            description = if (isRevealed) "Hide" else "Reveal",
            onClick = { if (!isRevealed) onNeedReveal() else onHide() },
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceLow)
            .border(1.dp, Primary)
            .padding(12.dp)
            .pointerInput(isRevealed) {
                detectTapGestures(
                    onLongPress = {
                        if (isRevealed && value != CredentialDefaults.FIELD_NOT_SET) {
                            clipboardManager.setText(AnnotatedString(value))
                            onCopy()
                        } else {
                            onNeedReveal()
                        }
                    }
                )
            },
    ) {
        Text(
            text = if (isRevealed) value else maskPlaceholder,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 13.sp,
            color = if (isRevealed) Primary else Color.Gray,
            maxLines = if (isRevealed) maxLinesRevealed else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
