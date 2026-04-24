package com.lockit.ui.screens.vault_unlock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.lockit.LockitApp
import com.lockit.R
import com.lockit.data.recovery.AccountRecoveryManager
import com.lockit.data.recovery.RecoveryVerificationResult
import com.lockit.ui.components.BrutalistTopBar
import com.lockit.ui.theme.IndustrialOrange
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Account recovery screen - allows users to verify identity via Google
 * and reset their PIN when they forget it.
 */
@Composable
fun AccountRecoveryScreen(
    onRecoveryComplete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LockitApp
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    // Pre-fetch strings to avoid calling stringResource in non-Composable contexts
    val strRecoveryFailed = stringResource(R.string.recovery_failed)
    val strVaultNotUnlocked = stringResource(R.string.recovery_vault_not_unlocked)
    val strRecoveryMismatch = stringResource(R.string.recovery_mismatch)
    val strRecoveryNotConfigured = stringResource(R.string.recovery_not_configured)

    val recoveryManager = remember { AccountRecoveryManager(context) }
    var recoveryState by remember { mutableStateOf(RecoveryState.VERIFY_ACCOUNT) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var recoveredMasterKey by remember { mutableStateOf<ByteArray?>(null) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    // Google Sign-In launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            val account = task.result
            if (account != null) {
                val verification = recoveryManager.verifyAndDecryptMasterKey(account)
                when (verification) {
                    is RecoveryVerificationResult.VerifiedWithKey -> {
                        recoveredMasterKey = verification.masterKey
                        recoveryState = RecoveryState.SET_NEW_PIN
                        errorMessage = null
                    }
                    RecoveryVerificationResult.Mismatch -> {
                        errorMessage = strRecoveryMismatch
                    }
                    RecoveryVerificationResult.NotConfigured -> {
                        errorMessage = strRecoveryNotConfigured
                    }
                    is RecoveryVerificationResult.Failure -> {
                        errorMessage = "$strRecoveryFailed ${verification.message}"
                    }
                }
            } else {
                errorMessage = "$strRecoveryFailed No account"
            }
        } else {
            errorMessage = "$strRecoveryFailed ${task.exception?.message}"
        }
    }

    Scaffold(
        topBar = {
            Box(Modifier.statusBarsPadding()) {
                BrutalistTopBar()
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(colorScheme.background),
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.recovery_title),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    color = Primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.recovery_subtitle),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Content based on state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (recoveryState) {
                    RecoveryState.VERIFY_ACCOUNT -> {
                        // Verify account section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, colorScheme.primary)
                                .background(colorScheme.surface)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.recovery_verify_title),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Primary,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.recovery_verify_desc),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                                color = colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            RecoveryActionButton(
                                text = stringResource(R.string.recovery_sign_in),
                                onClick = {
                                    errorMessage = null
                                    signInLauncher.launch(recoveryManager.getSignInIntent())
                                },
                                enabled = !isProcessing,
                            )
                        }
                    }

                    RecoveryState.SET_NEW_PIN -> {
                        // Set new PIN section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, colorScheme.primary)
                                .background(colorScheme.surface)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.recovery_new_pin_title),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Primary,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.recovery_new_pin_desc),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 10.sp,
                                color = colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // PIN input display
                            PinInputDisplay(
                                label = stringResource(R.string.recovery_new_pin),
                                pin = newPin,
                                isConfirmStep = false,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            PinInputDisplay(
                                label = stringResource(R.string.recovery_confirm_pin),
                                pin = confirmPin,
                                isConfirmStep = true,
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Keypad
                            RecoveryKeypad(
                                onDigit = { digit ->
                                    if (newPin.length < 4) {
                                        newPin += digit
                                    } else if (confirmPin.length < 4) {
                                        confirmPin += digit
                                    }
                                },
                                onBackspace = {
                                    if (confirmPin.isNotEmpty()) {
                                        confirmPin = confirmPin.dropLast(1)
                                    } else if (newPin.isNotEmpty()) {
                                        newPin = newPin.dropLast(1)
                                    }
                                },
                                onSubmit = {
                                    if (newPin.length < 4) {
                                        errorMessage = "PIN_TOO_SHORT"
                                    } else if (confirmPin.length < 4) {
                                        errorMessage = "CONFIRM_PIN_TOO_SHORT"
                                    } else if (newPin != confirmPin) {
                                        errorMessage = "PIN_MISMATCH"
                                        confirmPin = ""
                                    } else {
                                        isProcessing = true
                                        errorMessage = null
                                        scope.launch {
                                            var success = false
                                            var error: String? = null
                                            try {
                                                val key = recoveredMasterKey
                                                if (key == null) {
                                                    error = strVaultNotUnlocked
                                                } else {
                                                    // Unlock vault with recovered key
                                                    val unlockResult = withContext(Dispatchers.IO) {
                                                        app.vaultManager.unlockVaultWithRecoveredKey(key)
                                                    }
                                                    if (unlockResult.isFailure) {
                                                        error = "$strRecoveryFailed ${unlockResult.exceptionOrNull()?.message}"
                                                    } else {
                                                        // Reset PIN
                                                        val resetResult = withContext(Dispatchers.IO) {
                                                            app.vaultManager.resetPin(newPin)
                                                        }
                                                        if (resetResult.isSuccess) {
                                                            success = true
                                                        } else {
                                                            error = "$strRecoveryFailed ${resetResult.exceptionOrNull()?.message}"
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                error = "$strRecoveryFailed ${e.message}"
                                            }
                                            isProcessing = false
                                            if (success) {
                                                recoveryState = RecoveryState.SUCCESS
                                            } else {
                                                errorMessage = error
                                            }
                                        }
                                    }
                                },
                                isProcessing = isProcessing,
                            )
                        }
                    }

                    RecoveryState.SUCCESS -> {
                        // Success message
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, IndustrialOrange)
                                .background(colorScheme.surface)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = IndustrialOrange,
                                modifier = Modifier.requiredSize(48.dp),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.recovery_success),
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = IndustrialOrange,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            RecoveryActionButton(
                                text = "Continue",
                                onClick = onRecoveryComplete,
                                enabled = true,
                            )
                        }
                    }
                }

                // Error message
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
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
                            text = error,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 9.sp,
                            color = TacticalRed,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrutalistSmallButton(
                    text = stringResource(R.string.btn_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PinInputDisplay(
    label: String,
    pin: String,
    isConfirmStep: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(4) { index ->
                val isFilled = index < pin.length
                Box(
                    modifier = Modifier
                        .requiredSize(8.dp)
                        .border(1.dp, Primary)
                        .background(if (isFilled) Primary else White),
                )
            }
        }
    }
}

@Composable
private fun RecoveryKeypad(
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
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        keys.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(4f / 3f)
                            .background(colorScheme.surface)
                            .drawBehind {
                                val sw = 1.dp.toPx()
                                if (index < row.size - 1) {
                                    drawLine(colorScheme.primary, Offset(size.width, 0f), Offset(size.width, size.height), sw)
                                }
                                drawLine(colorScheme.primary, Offset(0f, size.height), Offset(size.width, size.height), sw)
                            }
                            .clickable(enabled = !isProcessing) {
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
            }
        }
    }
}

@Composable
private fun RecoveryActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(if (enabled) Primary else Primary.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = White,
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

private enum class RecoveryState {
    VERIFY_ACCOUNT,
    SET_NEW_PIN,
    SUCCESS,
}