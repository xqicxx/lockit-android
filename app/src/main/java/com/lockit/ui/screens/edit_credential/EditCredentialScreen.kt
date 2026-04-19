package com.lockit.ui.screens.edit_credential

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lockit.LockitApp
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import com.lockit.domain.model.requiredFieldIndices
import com.lockit.ui.components.BackButtonRow
import com.lockit.ui.components.BrutalistButton
import com.lockit.ui.components.BrutalistTextField
import com.lockit.ui.components.BrutalistToast
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.ButtonVariant
import com.lockit.ui.components.CredentialTypeDropdown
import com.lockit.ui.components.DropdownWithCustomInput
import com.lockit.ui.components.ScreenHero
import com.lockit.ui.components.ChipGroup
import com.lockit.ui.components.parseCredentialFields
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun EditCredentialScreen(
    credentialId: String,
    app: LockitApp,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    var credential by remember { mutableStateOf<Credential?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(credentialId) {
        try {
            credential = app.vaultManager.getCredentialById(credentialId)
        } catch (_: Exception) {
        } finally {
            isLoading = false
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

    val cred = credential ?: run {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CREDENTIAL_NOT_FOUND",
                fontFamily = JetBrainsMonoFamily,
                fontSize = 14.sp,
                color = TacticalRed,
            )
        }
        return
    }

    EditCredentialForm(
        credential = cred,
        app = app,
        onBack = onBack,
        onSave = onSave,
    )
}

@Composable
private fun EditCredentialForm(
    credential: Credential,
    app: LockitApp,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(credential.type) }
    var typeExpanded by remember { mutableStateOf(false) }

    val fieldCount = credential.type.fields.size

    // For CodingPlan: parse combined value into individual fields, overlay metadata
    val fieldValues = remember {
        if (credential.type == CredentialType.CodingPlan) {
            val fields = parseCredentialFields(credential.value)
            // Overlay metadata values if available
            val meta = runCatching { JSONObject(credential.metadata) }.getOrNull()
            val cookie = meta?.optString("cookie")
            val rawCurl = meta?.optString("rawCurl")
            val secToken = meta?.optString("secToken")
            val baseUrl = meta?.optString("baseUrl")

            mutableStateListOf(
                credential.name,                                          // 0: PROVIDER
                if (rawCurl?.isNotBlank() == true) rawCurl else fields.getOrElse(1) { "" },  // 1: RAW_CURL
                fields.getOrElse(2) { "" },                               // 2: API_KEY
                if (cookie?.isNotBlank() == true) cookie else fields.getOrElse(3) { "" },    // 3: COOKIE
                if (secToken?.isNotBlank() == true) secToken else fields.getOrElse(4) { "" }, // 4: SEC_TOKEN
                if (baseUrl?.isNotBlank() == true) baseUrl else fields.getOrElse(5) { "" },  // 5: BASE_URL
            )
        } else {
            // For non-CodingPlan types: parse combined value into individual fields
            val fields = parseCredentialFields(credential.value)
            mutableStateListOf(
                credential.name,                                           // 0: NAME (stored separately)
                credential.service.takeIf { it.isNotBlank() } ?: fields.getOrElse(1) { "" }, // 1: service or field[1]
                credential.key.takeIf { it.isNotBlank() } ?: fields.getOrElse(2) { "" },     // 2: key or field[2]
                fields.getOrElse(3) { "" },                                // 3: field[3]
                fields.getOrElse(4) { "" },                                // 4: field[4]
                fields.getOrElse(5) { "" },                                // 5: field[5] (for types with 6+ fields)
                fields.getOrElse(6) { "" },                                // 6: additional fields
                fields.getOrElse(7) { "" },                                // 7: additional fields
            )
        }
    }
    val fieldErrors = remember {
        mutableStateListOf<String?>(*Array(fieldCount) { null })
    }

    // Track whether the user manually edited cookie to avoid overwriting
    var userEditedCookie by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }
    var saveError: String? by remember { mutableStateOf(null) }
    var nameWarning by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val fields by remember(selectedType) {
        derivedStateOf { selectedType.fields }
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

    /** Extract cookie from curl command */
    fun extractCookieFromCurl(curl: String): String {
        val cookieRegex = Regex("""['"](cookie|Cookie|COOKIE)[^=]*=['"]?([^"';]+)""", RegexOption.IGNORE_CASE)
        return cookieRegex.find(curl)?.groupValues?.getOrNull(2)?.trim() ?: ""
    }

    /** Extract sec_token from curl command */
    fun extractSecTokenFromCurl(curl: String): String {
        val secTokenRegex = Regex("""sec_token[=]+([^&\s'"]+)""")
        return secTokenRegex.find(curl)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    /** Auto-extract cookie from RAW_CURL and fill COOKIE field for CodingPlan */
    fun handleRawCurlChange(value: String) {
        fieldValues[1] = value
        if (selectedType == CredentialType.CodingPlan) {
            val extractedCookie = extractCookieFromCurl(value)
            if (!userEditedCookie && extractedCookie.isNotBlank()) {
                fieldValues[3] = extractedCookie
            }
            // Extract sec_token (index 4)
            val extractedSecToken = extractSecTokenFromCurl(value)
            if (extractedSecToken.isNotBlank()) {
                fieldValues[4] = extractedSecToken
            }
        }
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
                title = "Edit Credential",
                subtitle = "Modify existing secret entry",
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Type dropdown
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

            // Dynamic fields
            fields.forEachIndexed { index, field ->
                val isSmallPreset = field.isDropdown && field.presets.size <= 6
                val useChipGroup = field.showAsChips || isSmallPreset

                if (useChipGroup) {
                    ChipGroup(
                        label = field.label,
                        options = field.presets,
                        selectedValue = getField(index),
                        onSelect = { fieldValues[index] = it },
                        placeholder = field.placeholder,
                        error = fieldErrors[index],
                        showCustomInput = field.showAsChips,
                    )
                } else if (field.isDropdown) {
                    DropdownWithCustomInput(
                        label = field.label,
                        presets = field.presets,
                        selectedValue = getField(index),
                        onValueChange = { fieldValues[index] = it },
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
                text = if (isSaving) "UPDATING..." else "UPDATE_CREDENTIAL",
                onClick = {
                    if (isSaving) return@BrutalistButton
                    if (validate()) {
                        isSaving = true
                        saveError = null
                        nameWarning = null
                        scope.launch {
                            try {
                                // Check for duplicate name (excluding current credential)
                                val existingNames = app.vaultManager.getAllCredentials()
                                    .first()
                                    .filter { it.id != credential.id }
                                    .map { it.name }
                                    .toSet()
                                val baseName = getField(0)
                                var finalName = baseName
                                if (baseName in existingNames) {
                                    var suffix = 1
                                    do {
                                        finalName = "${baseName}_${suffix}"
                                        suffix++
                                    } while (finalName in existingNames)
                                    nameWarning = "NAME_TAKEN. Auto-renamed to: $finalName"
                                }
                                app.vaultManager.updateCredential(
                                    id = credential.id,
                                    name = finalName,
                                    type = selectedType,
                                    service = if (selectedType == CredentialType.CodingPlan) {
                                        getField(0).takeIf { it.isNotBlank() } ?: selectedType.displayName
                                    } else {
                                        getField(1)
                                    },
                                    key = if (selectedType == CredentialType.CodingPlan) {
                                        ""
                                    } else {
                                        getField(2)
                                    },
                                    value = fieldValues.joinToString(" // ") { it.ifBlank { "-" } },
                                    metadata = if (selectedType == CredentialType.CodingPlan) {
                                        val cookie = getField(3).takeIf { it.isNotBlank() }
                                            ?: extractCookieFromCurl(getField(1))
                                        JSONObject().apply {
                                            put("provider", getField(0))
                                            put("rawCurl", getField(1))
                                            put("apiKey", getField(2))
                                            put("cookie", cookie)
                                            put("secToken", getField(4))
                                            put("baseUrl", getField(5))
                                        }.toString()
                                    } else {
                                        null
                                    },
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
