package com.lockit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Fingerprint
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

/**
 * Parse the combined credential value string into individual field values.
 */
fun parseCredentialFields(value: String): List<String> {
    return value.split(" // ").map { if (it == "-") "" else it }
}

/**
 * Build human-readable email address from parsed credential fields.
 * Fields: SERVICE(0) → EMAIL_PREFIX(1) → PASSWORD(2) → REGION(3) → ...
 */
fun buildEmailAddress(fields: List<String>): String {
    val prefix = fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return "UNKNOWN"
    val provider = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return prefix
    val cleanProvider = provider.trimStart('@')
    return "$prefix@$cleanProvider"
}

/**
 * Extract password from combined Email credential value (field index 2).
 */
fun extractEmailPassword(value: String): String {
    val fields = parseCredentialFields(value)
    return fields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "—"
}

/**
 * Extract the secret/value field from a credential's combined value string.
 * For types with multi-field values, returns only the last (secret) field.
 * For single-field types (Phone, BankCard, IdCard, Note), returns the whole value.
 */
fun extractSecretValue(type: CredentialType, value: String): String {
    return when (type) {
        CredentialType.Phone, CredentialType.BankCard,
        CredentialType.IdCard, CredentialType.Note -> value
        CredentialType.CodingPlan -> {
            // CodingPlan fields: PROVIDER // RAW_CURL // API_KEY // COOKIE // SEC_TOKEN // BASE_URL
            val fields = parseCredentialFields(value)
            // Prefer RAW_CURL (index 1), fallback to API_KEY (index 2)
            val rawCurl = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
            val apiKey = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
            when {
                rawCurl != null -> rawCurl
                apiKey != null -> apiKey
                else -> value  // Show full value if both are empty
            }
        }
        else -> parseCredentialFields(value).lastOrNull()?.takeIf { it.isNotBlank() } ?: value
    }
}

@Composable
fun CredentialCard(
    credential: Credential,
    onClick: () -> Unit,
    /** Combined action: authenticate → reveal value (if hidden) AND copy to clipboard */
    onRevealAndCopy: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onEmailPasswordReveal: (() -> Unit)? = null,
    revealedEmailPassword: String? = null,
    onHideEmailPassword: (() -> Unit)? = null,
    isValueRevealed: Boolean = false,
    onHideValue: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** When false, always show values and hide the reveal/copy toggle button. */
    showRevealToggle: Boolean = true,
    /** CodingPlan: copy API_KEY */
    onCopyApiKey: (() -> Unit)? = null,
    /** CodingPlan: copy BASE_URL */
    onCopyBaseUrl: (() -> Unit)? = null,
    /** CodingPlan: copy PROVIDER */
    onCopyProvider: (() -> Unit)? = null,
    /** CodingPlan: need reveal first before copy */
    onNeedReveal: (() -> Unit)? = null,
    /** Card-level JSON structured copy */
    onCopyStructured: (() -> Unit)? = null,
    /** Copy single value from row */
    onCopyValue: (() -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    // When showRevealToggle is false, always treat as revealed.
    val effectiveRevealed = if (showRevealToggle) isValueRevealed else true
    val hasVisibilityToggle = showRevealToggle && credential.type != CredentialType.Phone &&
            credential.type != CredentialType.Email &&
            credential.type != CredentialType.IdCard &&
            credential.type != CredentialType.Note &&
            credential.type != CredentialType.CodingPlan
    var isRevealed by remember { mutableStateOf(effectiveRevealed) }

    // Sync with external reveal state and auto-hide when biometric cache expires.
    val currentOnHide = rememberUpdatedState(onHideValue)
    LaunchedEffect(isValueRevealed, showRevealToggle) {
        if (showRevealToggle) {
            if (isValueRevealed) {
                isRevealed = true
                if (!BiometricUtils.isBiometricCacheValid()) {
                    isRevealed = false
                    currentOnHide.value?.invoke(credential.id)
                }
            } else {
                isRevealed = false
            }
        } else {
            isRevealed = true
        }
    }

    // Auto-hide email password when biometric cache expires.
    LaunchedEffect(revealedEmailPassword) {
        if (revealedEmailPassword != null && !BiometricUtils.isBiometricCacheValid()) {
            onHideEmailPassword?.invoke()
        }
    }

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
            .clickable(onClick = {
                // CodingPlan: don't copy on card click, only long-press works
                if (credential.type != CredentialType.CodingPlan) {
                    onRevealAndCopy?.invoke()
                }
            })
            .pointerInput(isRevealed) {
                detectTapGestures(
                    onLongPress = {
                        // Long press card → copy JSON structured data
                        if (credential.type == CredentialType.CodingPlan) {
                            if (isRevealed) {
                                onCopyStructured?.invoke()
                            } else {
                                onNeedReveal?.invoke()
                            }
                        } else if (credential.type == CredentialType.Phone ||
                            credential.type == CredentialType.Email ||
                            credential.type == CredentialType.IdCard ||
                            credential.type == CredentialType.Note) {
                            // These types always show values, can copy directly
                            onCopyStructured?.invoke()
                        } else {
                            // Other types: need reveal for JSON copy
                            if (isRevealed) {
                                onCopyStructured?.invoke()
                            } else {
                                onNeedReveal?.invoke()
                            }
                        }
                    }
                )
            }
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick),
            ) {
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
                if (credential.type == CredentialType.Email) {
                    // Email: fingerprint for password reveal (separate from copy)
                    IconButtonBox(
                        icon = Icons.Default.Fingerprint,
                        description = "Reveal password with biometric",
                        onClick = { onEmailPasswordReveal?.invoke() },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // CodingPlan has its own per-row buttons, skip header icons
                if (credential.type != CredentialType.CodingPlan) {
                    // Reveal/hide button (fingerprint / eye)
                    val actionIcon = when {
                        credential.type == CredentialType.Email -> Icons.Default.ContentCopy
                        isRevealed -> Icons.Default.VisibilityOff
                        hasVisibilityToggle -> Icons.Default.Visibility
                        else -> Icons.Default.ContentCopy
                    }
                    IconButtonBox(
                        icon = actionIcon,
                        description = if (isRevealed) "Hide value" else "Reveal value",
                        onClick = {
                            if (isRevealed && hasVisibilityToggle) {
                                // Hide value
                                isRevealed = false
                                currentOnHide.value?.invoke(credential.id)
                            } else if (hasVisibilityToggle) {
                                // Only reveal, don't copy
                                isRevealed = true
                            } else {
                                // Types without visibility toggle: copy directly
                                onRevealAndCopy?.invoke()
                            }
                        },
                    )
                    // Show explicit copy button for types with visibility toggle
                    if (hasVisibilityToggle) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButtonBox(
                            icon = Icons.Default.ContentCopy,
                            description = "Copy value",
                            onClick = { onRevealAndCopy?.invoke() },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (credential.type == CredentialType.Phone) {
            // Phone: show number prominently, then structured fields
            val phoneFields = remember(credential.value) { parseCredentialFields(credential.value) }
            val phoneNumber = phoneFields.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: credential.service.takeIf { it.isNotBlank() }
                ?: "UNKNOWN"
            val region = phoneFields.getOrNull(0)?.takeIf { it.isNotBlank() }
                ?: credential.name.takeIf { it.isNotBlank() }
                ?: ""

            // Phone number row
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceLow)
                    .border(1.dp, Primary)
                    .padding(12.dp),
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
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Note row (if present)
            val note = phoneFields.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: credential.key.takeIf { it.isNotBlank() }
            if (note != null) {
                Spacer(modifier = Modifier.height(8.dp))
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceLow)
                            .border(1.dp, Primary)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = note,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            color = Primary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Region tag below phone number
            if (region.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "REGION",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        color = IndustrialOrange,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = region,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        color = Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else if (credential.type == CredentialType.Email) {
            // Email: show email address prominently
            val emailFields = remember(credential.value) { parseCredentialFields(credential.value) }
            val emailAddress = remember(emailFields) { buildEmailAddress(emailFields) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceLow)
                    .border(1.dp, Primary)
                    .padding(12.dp),
            ) {
                Text(
                    text = emailAddress,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 14.sp,
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Password row (hidden until biometric reveal)
            val passwordValue = emailFields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "—"
            val passwordRevealed = revealedEmailPassword != null
            val displayPassword = revealedEmailPassword ?: passwordValue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PASSWORD",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = IndustrialOrange,
                )
                if (passwordRevealed) {
                    IconButtonBox(
                        icon = Icons.Default.VisibilityOff,
                        description = "Hide password",
                        onClick = { onEmailPasswordReveal?.invoke() },
                    )
                } else {
                    IconButtonBox(
                        icon = Icons.Default.Fingerprint,
                        description = "Reveal with biometric",
                        onClick = { onEmailPasswordReveal?.invoke() },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceLow)
                    .border(1.dp, Primary)
                    .padding(12.dp),
            ) {
                Text(
                    text = if (passwordRevealed) displayPassword else "•".repeat(20),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 13.sp,
                    color = if (passwordRevealed) Primary else Color.Gray,
                    maxLines = if (passwordRevealed) 5 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Show other non-empty fields (region, address, etc.)
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                                color = IndustrialOrange,
                            )
                            Text(
                                text = value ?: "",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp,
                                color = Primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else if (credential.type == CredentialType.CodingPlan) {
            // CodingPlan: show API_KEY and BASE_URL only (provider is card title)
            val codingPlanFields = remember(credential.value) { parseCredentialFields(credential.value) }
            val apiKey = codingPlanFields.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "未设置"
            val baseUrl = codingPlanFields.getOrNull(5)?.takeIf { it.isNotBlank() } ?: "未设置"
            val provider = codingPlanFields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "未知"

            // Helper to build structured copy string
            fun buildStructuredCopy(): String {
                val lines = mutableListOf<String>()
                lines.add("PROVIDER: $provider")
                if (apiKey != "未设置") {
                    lines.add("API_KEY: $apiKey")
                }
                if (baseUrl != "未设置") {
                    lines.add("BASE_URL: $baseUrl")
                }
                return lines.joinToString("\n")
            }

            // API_KEY row - reveal/hide only
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "API_KEY",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = IndustrialOrange,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRevealed) {
                        IconButtonBox(
                            icon = Icons.Default.VisibilityOff,
                            description = "Hide API key",
                            onClick = {
                                isRevealed = false
                                onHideValue?.invoke(credential.id)
                            },
                        )
                    } else {
                        IconButtonBox(
                            icon = Icons.Default.Visibility,
                            description = "Reveal API key",
                            onClick = { isRevealed = true },
                        )
                    }
                }
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
                                // Copy only API_KEY when revealed
                                if (isRevealed && apiKey != "未设置") {
                                    clipboardManager.setText(AnnotatedString(apiKey))
                                    onCopyApiKey?.invoke()
                                } else {
                                    onNeedReveal?.invoke()
                                }
                            }
                        )
                    },
            ) {
                Text(
                    text = if (isRevealed) apiKey else "•".repeat(20),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 13.sp,
                    color = if (isRevealed) Primary else Color.Gray,
                    maxLines = if (isRevealed) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // BASE_URL row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "BASE_URL",
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = IndustrialOrange,
                )
            }
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
                                // Copy only BASE_URL
                                if (baseUrl != "未设置") {
                                    clipboardManager.setText(AnnotatedString(baseUrl))
                                    onCopyBaseUrl?.invoke()
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
        } else {
            // All other credential types
            val displayValue = when (credential.type) {
                CredentialType.IdCard, CredentialType.Note -> credential.value
                else -> if (isRevealed) extractSecretValue(credential.type, credential.value) else "\u2022".repeat(40)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceLow)
                    .border(1.dp, Primary)
                    .padding(12.dp)
                    .pointerInput(isRevealed) {
                        detectTapGestures(
                            onLongPress = {
                                // Copy single value when revealed (or always for IdCard/Note)
                                if (isRevealed || credential.type == CredentialType.IdCard || credential.type == CredentialType.Note) {
                                    onCopyValue?.invoke()
                                } else {
                                    onNeedReveal?.invoke()
                                }
                            }
                        )
                    },
            ) {
                Text(
                    text = displayValue,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 13.sp,
                    color = if (isRevealed) Primary else Color.Gray,
                    maxLines = if (isRevealed) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row {
                val emailParts = remember(credential.value) { parseCredentialFields(credential.value) }
                if (credential.type == CredentialType.Email) {
                    val provider = emailParts.getOrNull(0)?.trimStart('@')?.takeIf { it.isNotBlank() }
                    if (provider != null) {
                        InfoTag(text = provider.uppercase())
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    val region = emailParts.getOrNull(3)?.takeIf { it.isNotBlank() }
                    if (region != null) {
                        InfoTag(text = region.uppercase())
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                } else {
                    InfoTag(text = when (credential.type) {
                        CredentialType.Phone -> credential.name.uppercase()
                        CredentialType.Account -> credential.service.uppercase().takeIf { it.isNotBlank() } ?: "SERVICE"
                        else -> credential.service.uppercase()
                    })
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

        Spacer(modifier = Modifier.height(8.dp))

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
