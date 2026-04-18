package com.lockit.ui.screens.vault_unlock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lockit.LockitApp
import com.lockit.R
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.components.findActivity
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultUnlockViewModel(private val app: LockitApp) : ViewModel() {
    private val biometricStorage = com.lockit.data.biometric.BiometricPinStorage(
        app.getSharedPreferences("lockit_biometric_prefs", android.content.Context.MODE_PRIVATE)
    )

    private val _uiState = MutableStateFlow(VaultUnlockUiState())
    val uiState: StateFlow<VaultUnlockUiState> = _uiState.asStateFlow()

    var pin by mutableStateOf("")
    var confirmPin by mutableStateOf("")
    var isConfirmStep by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var isProcessing by mutableStateOf(false)
    var isInitialized by mutableStateOf(false)
    var showBiometricSetup by mutableStateOf(false)
    var showBiometricButton by mutableStateOf(false)
        private set
    var isBiometricLinked by mutableStateOf(false)
        private set

    fun onPinDigit(digit: String) {
        if (isConfirmStep) {
            if (confirmPin.length < 4) {
                confirmPin += digit
            }
        } else {
            if (pin.length < 4) {
                pin += digit
            }
        }
    }

    fun onBackspace() {
        if (isConfirmStep) {
            if (confirmPin.isNotEmpty()) {
                confirmPin = confirmPin.dropLast(1)
            }
        } else {
            if (pin.isNotEmpty()) {
                pin = pin.dropLast(1)
            }
        }
    }

    fun onSubmit() {
        if (isProcessing) return
        if (isInitialized) {
            if (pin.length < 4) {
                errorMessage = "PIN_TOO_SHORT"
                return
            }
            isProcessing = true
            viewModelScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        app.vaultManager.unlockVault(pin)
                    }
                    if (result.isSuccess) {
                        _uiState.value = VaultUnlockUiState(navigated = true)
                    } else {
                        errorMessage = "WRONG_PIN"
                        pin = ""
                    }
                } catch (e: Exception) {
                    errorMessage = e.message?.uppercase() ?: "WRONG_PIN"
                    pin = ""
                } finally {
                    isProcessing = false
                }
            }
        } else {
            if (!isConfirmStep) {
                if (pin.length < 4) {
                    errorMessage = "PIN_TOO_SHORT"
                    return
                }
                isConfirmStep = true
                confirmPin = ""
                errorMessage = null
            } else {
                if (confirmPin.length < 4) {
                    errorMessage = "CONFIRM_PIN_TOO_SHORT"
                    return
                }
                if (pin != confirmPin) {
                    errorMessage = "PIN_MISMATCH"
                    confirmPin = ""
                    return
                }
                isProcessing = true
                viewModelScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.vaultManager.initVault(pin)
                        }
                        isProcessing = false
                        showBiometricSetup = true
                    } catch (e: Exception) {
                        isProcessing = false
                        errorMessage = e.message?.uppercase() ?: "INIT_FAILED"
                        pin = ""
                        confirmPin = ""
                        isConfirmStep = false
                    }
                }
            }
        }
    }

    fun cancelConfirm() {
        isConfirmStep = false
        confirmPin = ""
        errorMessage = null
    }

    fun navigateToMain() {
        _uiState.value = VaultUnlockUiState(navigated = true)
    }

    fun resetNavigated() {
        _uiState.value = VaultUnlockUiState(navigated = false)
    }

    fun clearPin() {
        pin = ""
        confirmPin = ""
        isConfirmStep = false
        errorMessage = null
    }

    fun setAppState(initialized: Boolean) {
        this.isInitialized = initialized
        this.isBiometricLinked = initialized && biometricStorage.isBiometricLinked()
        showBiometricButton = initialized
    }

    fun linkBiometric(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (pin.isEmpty()) {
            onError("NO_PIN_TO_LINK")
            return
        }
        biometricStorage.storePin(activity, pin, title, subtitle, onSuccess = {
            showBiometricButton = true
            onSuccess()
        }, onError = onError)
    }

    fun authenticateWithBiometric(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        biometricStorage.decryptPin(activity, title, subtitle, onSuccess = { decryptedPin ->
            viewModelScope.launch {
                pin = decryptedPin
                val result = withContext(Dispatchers.IO) {
                    app.vaultManager.unlockVault(decryptedPin)
                }
                if (result.isSuccess) {
                    _uiState.value = VaultUnlockUiState(navigated = true)
                    onSuccess()
                } else {
                    onError("WRONG_PIN")
                    pin = ""
                }
            }
        }, onError = onError)
    }
}

data class VaultUnlockUiState(
    val navigated: Boolean = false,
)

@Composable
fun VaultUnlockScreen(
    onUnlocked: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LockitApp
    val viewModel: VaultUnlockViewModel = viewModel(
        factory = VaultUnlockViewModelFactory(app),
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = MaterialTheme.colorScheme

    // Clear PIN when screen is shown (e.g., after manual lock from Config)
    LaunchedEffect(Unit) {
        viewModel.setAppState(app.vaultManager.isInitialized())
        viewModel.clearPin()  // Ensure PIN is empty when lock screen is shown
    }

    LaunchedEffect(uiState.navigated) {
        if (uiState.navigated) {
            onUnlocked()
            viewModel.resetNavigated()
        }
    }

    Scaffold(
        topBar = { Box(Modifier.statusBarsPadding()) { BrutalistTopBar() } },
    ) { paddingValues ->
        val colorScheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(colorScheme.background),
        ) {
            // Main Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
            // Background grid pattern
            BrutalistGridBackground()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Logo + Title
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.vault_title),
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 36.sp,
                        color = Primary,
                        letterSpacing = (-2).sp,
                        lineHeight = 36.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.vault_subtitle),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 8.sp,
                        color = Color.Black.copy(0.6f),
                        letterSpacing = 2.sp,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Auth Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colorScheme.primary)
                        .background(colorScheme.surface),
                ) {
                    // PIN Input Display
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                drawLine(colorScheme.primary, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                            }
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Status bar text
                            Text(
                                text = if (viewModel.isProcessing) {
                                    stringResource(R.string.vault_state_processing)
                                } else if (viewModel.isInitialized) {
                                    stringResource(R.string.vault_state_awaiting)
                                } else if (viewModel.isConfirmStep) {
                                    stringResource(R.string.vault_state_confirm)
                                } else {
                                    stringResource(R.string.vault_state_init)
                                },
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 8.sp,
                                color = if (viewModel.isProcessing) IndustrialOrange
                                    else if (viewModel.isConfirmStep) IndustrialOrange
                                    else Color.Black.copy(0.5f),
                                letterSpacing = 1.sp,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(4) { index ->
                                    val isFilled = if (viewModel.isConfirmStep) {
                                        index < viewModel.confirmPin.length
                                    } else {
                                        index < viewModel.pin.length
                                    }
                                    Box(
                                        modifier = Modifier
                                            .requiredSize(8.dp)
                                            .border(1.dp, Primary)
                                            .background(if (isFilled) Primary else White),
                                    )
                                }
                            }
                        }

                        // Keypad
                        Keypad(
                            onDigit = { viewModel.onPinDigit(it) },
                            onBackspace = { viewModel.onBackspace() },
                            onSubmit = { viewModel.onSubmit() },
                            isProcessing = viewModel.isProcessing,
                        )

                        // Quick Actions
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawLine(colorScheme.primary, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                                }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (viewModel.isConfirmStep) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BrutalistSmallButton(
                                        text = stringResource(R.string.vault_cancel),
                                        onClick = { viewModel.cancelConfirm() },
                                        modifier = Modifier.weight(1f),
                                    )
                                    BrutalistSmallButton(
                                        text = stringResource(R.string.vault_reenter_pin),
                                        onClick = { viewModel.cancelConfirm() },
                                        modifier = Modifier.weight(1f),
                                        isDanger = true,
                                    )
                                }
                            } else {
                                if (viewModel.isInitialized) {
                                    val bioText = if (viewModel.isBiometricLinked)
                                        stringResource(R.string.vault_unlock_biometric)
                                    else
                                        stringResource(R.string.vault_biometric_link)
                                    val linkTitle = stringResource(R.string.biometric_link_pin_title)
                                    val linkSubtitle = stringResource(R.string.biometric_link_pin_subtitle)
                                    val unlockTitle = stringResource(R.string.biometric_unlock_title)
                                    val unlockSubtitle = stringResource(R.string.biometric_unlock_subtitle)
                                    BrutalistActionButton(
                                        text = bioText,
                                        onClick = {
                                            val activity = view.findActivity()
                                            if (activity != null) {
                                                if (viewModel.isBiometricLinked) {
                                                    viewModel.authenticateWithBiometric(
                                                        activity = activity,
                                                        title = unlockTitle,
                                                        subtitle = unlockSubtitle,
                                                        onSuccess = { },
                                                        onError = { viewModel.errorMessage = "BIOMETRIC_FAILED: $it" },
                                                    )
                                                } else {
                                                    if (viewModel.pin.length >= 4) {
                                                        viewModel.linkBiometric(
                                                            activity = activity,
                                                            title = linkTitle,
                                                            subtitle = linkSubtitle,
                                                            onSuccess = { /* linked */ },
                                                            onError = { viewModel.errorMessage = "BIOMETRIC_LINK_FAILED: $it" },
                                                        )
                                                    } else {
                                                        viewModel.errorMessage = "ENTER_PIN_FIRST_TO_LINK"
                                                    }
                                                }
                                            }
                                        },
                                        icon = Icons.Default.Fingerprint,
                                        iconColor = White,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BrutalistSmallButton(
                                        text = stringResource(R.string.vault_recover_id),
                                        onClick = { /* Post-MVP: account recovery flow */ },
                                        modifier = Modifier.weight(1f),
                                    )
                                    BrutalistSmallButton(
                                        text = stringResource(R.string.vault_emergency_sos),
                                        onClick = { /* Post-MVP: emergency access protocol */ },
                                        modifier = Modifier.weight(1f),
                                        isDanger = true,
                                    )
                                }
                            }
                        }
                }

                // Error message area - fixed height to prevent UI jump
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    viewModel.errorMessage?.let { errorKey ->
                        val localizedError = getLocalizedErrorMessage(errorKey, context)
                        if (localizedError != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, TacticalRed)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "!",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TacticalRed,
                                )
                                Text(
                                    text = localizedError,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 9.sp,
                                    color = TacticalRed,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Biometric setup dialog after initial PIN creation
        if (viewModel.showBiometricSetup) {
            val linkTitle = stringResource(R.string.biometric_link_pin_title)
            val linkSubtitle = stringResource(R.string.biometric_link_pin_subtitle)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.7f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .background(White)
                        .border(2.dp, Primary)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = IndustrialOrange,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.vault_link_biometric_title),
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.vault_link_biometric_desc),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = Color.Gray,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrutalistSmallButton(
                            text = stringResource(R.string.vault_skip),
                            onClick = {
                                viewModel.showBiometricSetup = false
                                viewModel.setAppState(true)
                                viewModel.navigateToMain()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        BrutalistSmallButton(
                            text = stringResource(R.string.vault_enable),
                            onClick = {
                                val activity = view.findActivity()
                                if (activity != null) {
                                    viewModel.linkBiometric(
                                        activity = activity,
                                        title = linkTitle,
                                        subtitle = linkSubtitle,
                                        onSuccess = {
                                            viewModel.showBiometricSetup = false
                                            viewModel.setAppState(true)
                                            viewModel.navigateToMain()
                                        },
                                        onError = { err ->
                                            viewModel.errorMessage = "BIOMETRIC_LINK_FAILED: $err"
                                            viewModel.showBiometricSetup = false
                                            viewModel.setAppState(true)
                                            viewModel.navigateToMain()
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            isDanger = false,
                        )
                    }
                }
            }
        }

        // Status Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Primary)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.requiredSize(6.dp)) {
                    drawCircle(
                        color = IndustrialOrange,
                        radius = 3.dp.toPx(),
                    )
                }
                Text(
                    text = stringResource(R.string.vault_enc_link),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = IndustrialOrange,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.vault_os_ver),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 9.sp,
                    color = Color.White.copy(0.6f),
                )
            }
            Text(
                text = stringResource(R.string.vault_node_id),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.sp,
                color = Color.White.copy(0.4f),
            )
        }
    }
    }
}

@Composable
private fun Keypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    isProcessing: Boolean = false,
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("DEL", "0", if (isProcessing) "..." else "OK"),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        keys.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, key ->
                    KeypadKey(
                        modifier = Modifier.weight(1f),
                        key = key,
                        onDigit = onDigit,
                        onBackspace = onBackspace,
                        onSubmit = onSubmit,
                        enabled = !isProcessing,
                        hasRightBorder = index < row.size - 1,
                        hasBottomBorder = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(
    modifier: Modifier,
    key: String,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean = true,
    hasRightBorder: Boolean,
    hasBottomBorder: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.surface
    val borderColor = colorScheme.primary

    Box(
        modifier = modifier
            .aspectRatio(4f / 3f)
            .background(if (enabled) bgColor else borderColor.copy(0.05f))
            .drawBehind {
                val sw = 1.dp.toPx()
                if (hasRightBorder) {
                    drawLine(borderColor, Offset(size.width, 0f), Offset(size.width, size.height), sw)
                }
                if (hasBottomBorder) {
                    drawLine(borderColor, Offset(0f, size.height), Offset(size.width, size.height), sw)
                }
            }
            .clickable(enabled = enabled) {
                when (key) {
                    "DEL" -> onBackspace()
                    "OK" -> onSubmit()
                    else -> onDigit(key)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            "DEL" -> {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Primary,
                    modifier = Modifier.requiredSize(20.dp),
                )
            }
            "OK" -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Submit",
                    tint = IndustrialOrange,
                    modifier = Modifier.requiredSize(22.dp),
                )
            }
            else -> {
                Text(
                    text = key,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Primary,
                )
            }
        }
    }
}

@Composable
private fun BrutalistGridBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val dotRadius = 0.5.dp.toPx()
        val gridSize = 20.dp.toPx()
        val w = size.width
        val h = size.height
        var x = gridSize / 2
        while (x < w) {
            var y = gridSize / 2
            while (y < h) {
                drawCircle(
                    color = Color.Black.copy(alpha = 0.03f),
                    radius = dotRadius,
                    center = Offset(x, y),
                )
                y += gridSize
            }
            x += gridSize
        }
    }
}

@Composable
private fun BrutalistActionButton(
    text: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.requiredSize(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = White,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun BrutalistSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDanger: Boolean = false,
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .border(1.dp, if (isDanger) TacticalRed else Primary)
            .clickable(onClick = onClick)
            .background(if (isDanger) TacticalRed else White),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = if (isDanger) White else Primary,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * Maps error keys to localized string resources.
 * Handles both simple keys and composite keys like "BIOMETRIC_FAILED: message"
 */
fun getLocalizedErrorMessage(errorKey: String, context: android.content.Context): String? {
    // Handle composite error messages like "BIOMETRIC_FAILED: Some message"
    val parts = errorKey.split(": ")
    val baseKey = parts.firstOrNull() ?: errorKey
    val extraMessage = if (parts.size > 1) parts[1] else null

    val localizedBase = when (baseKey) {
        "PIN_TOO_SHORT" -> context.getString(R.string.error_pin_too_short)
        "WRONG_PIN" -> context.getString(R.string.error_wrong_pin)
        "CONFIRM_PIN_TOO_SHORT" -> context.getString(R.string.error_confirm_pin_too_short)
        "PIN_MISMATCH" -> context.getString(R.string.error_pin_mismatch)
        "INIT_FAILED" -> context.getString(R.string.error_init_failed)
        "NO_PIN_TO_LINK" -> context.getString(R.string.error_no_pin_to_link)
        "ENTER_PIN_FIRST_TO_LINK" -> context.getString(R.string.error_enter_pin_first)
        "BIOMETRIC_FAILED" -> context.getString(R.string.error_biometric_failed)
        "BIOMETRIC_LINK_FAILED" -> context.getString(R.string.error_biometric_link_failed)
        else -> null
    }

    return if (localizedBase != null && extraMessage != null) {
        "$localizedBase: $extraMessage"
    } else {
        localizedBase ?: errorKey
    }
}

private class VaultUnlockViewModelFactory(
    private val application: LockitApp,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return VaultUnlockViewModel(application) as T
    }
}

