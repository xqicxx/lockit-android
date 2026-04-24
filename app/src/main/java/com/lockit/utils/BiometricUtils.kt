package com.lockit.utils

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricUtils {
    private var lastAuthTimeMillis: Long = 0
    private var isAppSessionValid = false

    /**
     * Cache duration in milliseconds (15 minutes).
     */
    private const val CACHE_DURATION_MS = 15 * 60 * 1000L

    /**
     * Check if the global app session is still valid.
     * Invalidated on background or timeout.
     */
    fun isSessionValid(): Boolean = isAppSessionValid

    /**
     * Validate the global app session (after successful auth).
     */
    fun validateSession() {
        lastAuthTimeMillis = System.currentTimeMillis()
        isAppSessionValid = true
    }

    /**
     * Invalidate the global app session (on background).
     */
    fun invalidateSession() {
        lastAuthTimeMillis = 0
        isAppSessionValid = false
    }

    /**
     * Check if a valid biometric authentication is still in cache (15 min).
     */
    fun isBiometricCacheValid(): Boolean {
        if (!isAppSessionValid) return false
        val elapsed = System.currentTimeMillis() - lastAuthTimeMillis
        return elapsed < CACHE_DURATION_MS
    }

    /**
     * Refresh the biometric cache timestamp (after successful PIN verification).
     */
    fun refreshCache() {
        lastAuthTimeMillis = System.currentTimeMillis()
        isAppSessionValid = true
    }

    fun canAuthenticate(activity: FragmentActivity): Boolean {
        val biometricManager = BiometricManager.from(activity)
        // DEVICE_CREDENTIAL requires API 30+, fall back to BIOMETRIC_STRONG only on older devices
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Prompt for biometric authentication if the cache is not valid.
     * If cache is valid (within 15 minutes), calls onSuccess immediately.
     */
    fun requireBiometric(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isBiometricCacheValid()) {
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lastAuthTimeMillis = System.currentTimeMillis()
                    isAppSessionValid = true
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onError("Authentication failed. Try again.")
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply {
                // DEVICE_CREDENTIAL requires API 30+ (Android R)
                // On API 30+: allow device credential fallback, no negative button needed (system handles)
                // On API < 30: only BIOMETRIC_STRONG, cannot use device credential
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    setNegativeButtonText("取消")
                }
            }
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
