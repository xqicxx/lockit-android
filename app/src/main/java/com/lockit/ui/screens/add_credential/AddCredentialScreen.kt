package com.lockit.ui.screens.add_credential

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.domain.model.CredentialType
import com.lockit.domain.model.requiredFieldIndices
import com.lockit.ui.components.BackButtonRow
import com.lockit.ui.components.BrutalistButton
import com.lockit.ui.components.BrutalistTextField
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.BrutalistToast
import com.lockit.ui.components.ButtonVariant
import com.lockit.ui.components.ChipGroup
import com.lockit.ui.components.CredentialTypeDropdown
import com.lockit.ui.components.DropdownWithCustomInput
import com.lockit.ui.components.ScreenHero
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import com.lockit.utils.CodingPlanParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Extract cookie from curl command.
 * Supports both `-b 'cookie_string'` and `-H 'cookie: value'` formats.
 */
private fun extractCookieFromCurl(curl: String): String {
    // Match -b 'cookie_string' format (most common)
    val bFlagRegex = Regex("""-b\s+'([^']+)'""")
    val bMatch = bFlagRegex.find(curl)
    if (bMatch != null) {
        return bMatch.groupValues[1].trim()
    }
    // Also try -b "cookie_string" format
    val bFlagDoubleRegex = Regex("""-b\s+"([^"]+)"""")
    val bDoubleMatch = bFlagDoubleRegex.find(curl)
    if (bDoubleMatch != null) {
        return bDoubleMatch.groupValues[1].trim()
    }
    // Fallback: match -H 'cookie: value' format
    val cookieRegex = Regex("""['"](cookie|Cookie|COOKIE)[^=]*=['"]?([^"';]+)""", RegexOption.IGNORE_CASE)
    return cookieRegex.find(curl)?.groupValues?.getOrNull(2)?.trim() ?: ""
}

/**
 * Extract sec_token from curl command.
 */
private fun extractSecTokenFromCurl(curl: String): String {
    val secTokenRegex = Regex("""sec_token=([^&'\s]+)""")
    return secTokenRegex.find(curl)?.groupValues?.getOrNull(1)?.trim() ?: ""
}

/**
 * Extract API key from curl command (Authorization header).
 */
private fun extractApiKeyFromCurl(curl: String): String {
    // Match Authorization: Bearer xxx or x-api-key: xxx
    val bearerRegex = Regex("""Authorization['"]?\s*:\s*['"]?Bearer\s+([^'"\s]+)""", RegexOption.IGNORE_CASE)
    val bearerMatch = bearerRegex.find(curl)
    if (bearerMatch != null) {
        return bearerMatch.groupValues[1].trim()
    }
    // Match x-api-key header
    val apiKeyRegex = Regex("""x-api-key['"]?\s*:\s*['"]?([^'"\s]+)""", RegexOption.IGNORE_CASE)
    return apiKeyRegex.find(curl)?.groupValues?.getOrNull(1)?.trim() ?: ""
}

/**
 * Extract base URL from curl command.
 */
private fun extractBaseUrlFromCurl(curl: String): String {
    // Match URL in curl command: curl 'https://...' or curl "https://..."
    val urlRegex = Regex("""curl\s+['"]?(https?://[^'"\s]+)""")
    val match = urlRegex.find(curl)
    if (match != null) {
        val fullUrl = match.groupValues[1].trim()
        // Extract base URL (domain + path up to last /)
        val baseUrlRegex = Regex("""(https?://[^/]+/[^/]*)""")
        return baseUrlRegex.find(fullUrl)?.groupValues?.getOrNull(1)?.trim() ?: fullUrl
    }
    return ""
}

/**
 * Provider-specific base URL presets.
 */
private val PROVIDER_BASE_URLS = mapOf(
    "openai" to listOf("https://api.openai.com/v1", "https://api.openai.com"),
    "anthropic" to listOf("https://api.anthropic.com/v1", "https://api.anthropic.com"),
    "google" to listOf("https://generativelanguage.googleapis.com/v1"),
    "deepseek" to listOf("https://api.deepseek.com/v1", "https://api.deepseek.com"),
    "moonshot" to listOf("https://api.moonshot.cn/v1"),
    "minimax" to listOf("https://api.minimax.chat/v1"),
    "glm" to listOf("https://open.bigmodel.cn/api/paas/v4"),
    "qwen" to listOf("https://dashscope.aliyuncs.com/api/v1"),
    "qwen_bailian" to listOf("https://bailian.console.aliyun.com"),
)

/**
 * Maps NAME presets → SERVICE values for auto-linkage.
 */
private val NAME_TO_SERVICE = mapOf(
    "OPENAI_API_KEY" to "openai",
    "ANTHROPIC_API_KEY" to "anthropic",
    "CLAUDE_API_KEY" to "anthropic",
    "GEMINI_API_KEY" to "google",
    "MINIMAX_API_KEY" to "minimax",
    "DEEPSEEK_API_KEY" to "deepseek",
    "QWEN_API_KEY" to "qwen",
    "KIMI_API_KEY" to "kimi",
    "XIAOMI_API_KEY" to "xiaomi",
    "GITHUB_TOKEN" to "github",
    "GLM_API_KEY" to "glm",
)

/**
 * Maps SERVICE presets → NAME values for auto-linkage.
 */
private val SERVICE_TO_NAME = mapOf(
    "openai" to "OPENAI_API_KEY",
    "anthropic" to "ANTHROPIC_API_KEY",
    "google" to "GEMINI_API_KEY",
    "minimax" to "MINIMAX_API_KEY",
    "deepseek" to "DEEPSEEK_API_KEY",
    "qwen" to "QWEN_API_KEY",
    "kimi" to "KIMI_API_KEY",
    "xiaomi" to "XIAOMI_API_KEY",
    "github" to "GITHUB_TOKEN",
    "glm" to "GLM_API_KEY",
)

/**
 * Returns the field indices for name and service if the type supports auto-linkage.
 */
private fun CredentialType.getLinkageIndices(): Pair<Int, Int>? {
    return when (this) {
        CredentialType.ApiKey -> 0 to 1  // NAME(0) ↔ SERVICE(1)
        CredentialType.Token -> 0 to 1    // NAME(0) ↔ SERVICE(1)
        else -> null
    }
}

@Composable
fun AddCredentialScreen(
    app: LockitApp,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(CredentialType.ApiKey) }
    var typeExpanded by remember { mutableStateOf(false) }

    // Initialize with correct number of fields for current type
    val fieldValues = remember {
        mutableStateListOf(*Array(CredentialType.ApiKey.fields.size) { "" })
    }
    val fieldErrors = remember {
        mutableStateListOf<String?>(*Array(CredentialType.ApiKey.fields.size) { null })
    }

    // Track whether the user manually edited name/service to avoid overwriting
    var userEditedName by remember { mutableStateOf(false) }
    var userEditedService by remember { mutableStateOf(false) }
    var userEditedCookie by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }
    var saveError: String? by remember { mutableStateOf(null) }
    var nameWarning by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val fields by remember(selectedType) {
        derivedStateOf { selectedType.fields }
    }

    // Detect if this type supports name↔service auto-linkage
    val linkageIndices by remember(selectedType) {
        derivedStateOf { selectedType.getLinkageIndices() }
    }

    fun resizeFields(count: Int) {
        while (fieldValues.size > count) {
            fieldValues.removeAt(fieldValues.lastIndex)
            fieldErrors.removeAt(fieldErrors.lastIndex)
        }
        while (fieldValues.size < count) {
            fieldValues.add("")
            fieldErrors.add(null)
        }
    }

    fun resetFields() {
        userEditedName = false
        userEditedService = false
        userEditedCookie = false
        for (i in fieldValues.indices) {
            fieldValues[i] = ""
            fieldErrors[i] = null
        }
    }

    fun validate(): Boolean {
        var valid = true
        val required = selectedType.requiredFieldIndices
        for (i in required) {
            if (fieldValues.getOrElse(i) { "" }.isBlank()) {
                fieldErrors[i] = "REQUIRED"
                valid = false
            } else {
                fieldErrors[i] = null
            }
        }
        for (i in fieldErrors.indices) {
            if (i !in required) {
                fieldErrors[i] = null
            }
        }
        return valid
    }

    fun getField(index: Int) = fieldValues.getOrElse(index) { "" }

    /** Auto-link name ↔ service for types that support it */
    fun handleNameChange(index: Int, value: String) {
        fieldValues[index] = value
        userEditedName = true
        val linkage = linkageIndices ?: return
        val (_, serviceIdx) = linkage
        if (!userEditedService && value.isNotBlank()) {
            SERVICE_TO_NAME.entries.find { it.value == value }?.let {
                fieldValues[serviceIdx] = it.key
            }
        }
    }

    fun handleServiceChange(index: Int, value: String) {
        fieldValues[index] = value
        userEditedService = true
        val linkage = linkageIndices ?: return
        val (nameIdx, _) = linkage
        if (!userEditedName && value.isNotBlank()) {
            NAME_TO_SERVICE[value]?.let {
                fieldValues[nameIdx] = it
            }
        }
    }

    /** Auto-extract all fields from RAW_CURL for CodingPlan */
    fun handleRawCurlChange(value: String) {
        fieldValues[1] = value
        if (selectedType == CredentialType.CodingPlan) {
            // Extract cookie (index 3)
            val extractedCookie = extractCookieFromCurl(value)
            if (!userEditedCookie && extractedCookie.isNotBlank()) {
                fieldValues[3] = extractedCookie
            }
            // Extract sec_token (index 4)
            val extractedSecToken = extractSecTokenFromCurl(value)
            if (extractedSecToken.isNotBlank()) {
                fieldValues[4] = extractedSecToken
            }
            // Extract API key (index 2)
            val extractedApiKey = extractApiKeyFromCurl(value)
            if (extractedApiKey.isNotBlank()) {
                fieldValues[2] = extractedApiKey
            }
            // Extract base URL (index 5)
            val extractedBaseUrl = extractBaseUrlFromCurl(value)
            if (extractedBaseUrl.isNotBlank()) {
                fieldValues[5] = extractedBaseUrl
            }
        }
    }

    // Generate a unique credential name if the name already exists.
    fun generateUniqueName(baseName: String, existingNames: Set<String>): Pair<String, Boolean> {
        if (baseName !in existingNames) return baseName to false
        var suffix = 1
        var newName: String
        do {
            newName = "${baseName}_${suffix}"
            suffix++
        } while (newName in existingNames)
        return newName to true
    }

    // Build combined credential value from all fields
    fun buildCombinedValue(): String {
        return fieldValues.joinToString(" // ") { it.ifBlank { "-" } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        BrutalistTopBar(showBackButton = false)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            BackButtonRow(onBack = onBack)

            ScreenHero(
                title = "Add Credential",
                subtitle = "Initialize a new secret entry in the vault",
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Type selector
            CredentialTypeDropdown(
                selectedType = selectedType,
                expanded = typeExpanded,
                onExpandedChange = { typeExpanded = it },
                onTypeSelected = {
                    selectedType = it
                    typeExpanded = false
                    resizeFields(it.fields.size)
                    resetFields()
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Fields for selected type
            fields.forEachIndexed { index, field ->
                val isNameField = linkageIndices?.first == index
                val isServiceField = linkageIndices?.second == index
                val isSmallPreset = field.isDropdown && field.presets.size <= 6
                val useChipGroup = field.showAsChips || isSmallPreset

                if (useChipGroup) {
                    // Use chip group for small option sets or showAsChips fields
                    ChipGroup(
                        label = field.label,
                        options = field.presets,
                        selectedValue = getField(index),
                        onSelect = { value ->
                            when {
                                isNameField -> handleNameChange(index, value)
                                isServiceField -> handleServiceChange(index, value)
                                else -> fieldValues[index] = value
                            }
                        },
                        placeholder = field.placeholder,
                        error = fieldErrors[index],
                        showCustomInput = field.showAsChips, // showAsChips allows custom input via "+" button
                    )
                } else if (field.isDropdown) {
                    DropdownWithCustomInput(
                        label = field.label,
                        presets = field.presets,
                        selectedValue = getField(index),
                        onValueChange = { value ->
                            when {
                                isNameField -> handleNameChange(index, value)
                                isServiceField -> handleServiceChange(index, value)
                                else -> fieldValues[index] = value
                            }
                        },
                        placeholder = field.placeholder,
                        error = fieldErrors[index],
                        editable = field.editable,
                    )
                } else {
                    val isRawCurlField = selectedType == CredentialType.CodingPlan && index == 1
                    val isCookieField = selectedType == CredentialType.CodingPlan && index == 3
                    val isMultiline = isRawCurlField || isCookieField
                    BrutalistTextField(
                        value = getField(index),
                        onValueChange = { value ->
                            when {
                                isRawCurlField -> handleRawCurlChange(value)
                                isCookieField -> {
                                    fieldValues[index] = value
                                    userEditedCookie = true
                                }
                                else -> fieldValues[index] = value
                            }
                        },
                        label = field.label,
                        placeholder = field.placeholder,
                        error = fieldErrors[index],
                        maxLines = if (isMultiline) 10 else 1,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Save error
            saveError?.let {
                Text(
                    text = it,
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = TacticalRed,
                    modifier = Modifier
                        .border(1.dp, TacticalRed)
                        .padding(8.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Save button
            BrutalistButton(
                text = if (isSaving) "SAVING..." else "SAVE_CREDENTIAL",
                onClick = {
                    if (isSaving) return@BrutalistButton
                    if (validate()) {
                        isSaving = true
                        saveError = null
                        nameWarning = null
                        scope.launch {
                            try {
                                // Check for duplicate name
                                val existingNames = app.vaultManager.getAllCredentials()
                                    .first()
                                    .map { it.name }
                                    .toSet()
                                val (finalName, wasRenamed) = generateUniqueName(getField(0), existingNames)
                                if (wasRenamed) {
                                    nameWarning = "NAME_TAKEN. Auto-renamed to: $finalName"
                                }

                                // For CodingPlan: store all fields as metadata
                                val metadata = if (selectedType == CredentialType.CodingPlan) {
                                    val provider = getField(0)
                                    val rawCurl = getField(1)
                                    val apiKey = getField(2).takeIf { it.isNotBlank() }
                                        ?: extractApiKeyFromCurl(rawCurl)
                                    // Sanitize cookie: remove newlines and whitespace
                                    val rawCookie = getField(3).takeIf { it.isNotBlank() }
                                        ?: extractCookieFromCurl(rawCurl)
                                    val cookie = rawCookie.replace("\n", "").replace("\r", "").trim()
                                    // secToken: manual input or extract from curl
                                    val rawSecToken = getField(4).takeIf { it.isNotBlank() }
                                        ?: extractSecTokenFromCurl(rawCurl)
                                    val baseUrl = getField(5).takeIf { it.isNotBlank() }
                                        ?: extractBaseUrlFromCurl(rawCurl)

                                    // Save to SharedPreferences for immediate prefetch on app startup
                                    com.lockit.data.vault.CodingPlanPrefs.save(
                                        context = app,
                                        provider = provider,
                                        cookie = cookie,
                                        secToken = rawSecToken,
                                        apiKey = apiKey ?: "",
                                    )

                                    JSONObject().apply {
                                        put("provider", provider)
                                        put("rawCurl", rawCurl)
                                        put("apiKey", apiKey)
                                        put("cookie", cookie)
                                        put("secToken", rawSecToken)
                                        put("baseUrl", baseUrl)
                                    }.toString()
                                } else {
                                    "{}"
                                }

                                // CodingPlan: use provider name as service, apiKey as key
                                val (serviceVal, keyVal) = if (selectedType == CredentialType.CodingPlan) {
                                    (getField(0).takeIf { it.isNotBlank() }
                                        ?: selectedType.displayName) to getField(2)
                                } else {
                                    getField(1) to getField(2)
                                }

                                app.vaultManager.addCredential(
                                    name = finalName,
                                    type = selectedType,
                                    service = serviceVal,
                                    key = keyVal,
                                    value = buildCombinedValue(),
                                    metadata = metadata,
                                )
                                onSave()
                            } catch (e: Exception) {
                                saveError = "ERROR: ${e.message?.uppercase()}"
                                isSaving = false
                            }
                        }
                    }
                },
                variant = ButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
                useMonoFont = true,
                enabled = !isSaving,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Name warning toast
    nameWarning?.let { message ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            BrutalistToast(
                message = message,
                onDismiss = { nameWarning = null },
            )
        }
    }
}
