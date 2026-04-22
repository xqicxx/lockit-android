package com.lockit.data.biometric

import android.annotation.TargetApi
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the vault PIN encrypted with a hardware-backed key that requires
 * biometric authentication to access.
 */
class BiometricPinStorage(private val sharedPreferences: SharedPreferences) {

    companion object {
        private const val KEY_ALIAS = "lockit_biometric_key"
        private const val PREFS_NAME = "lockit_biometric_prefs"
        private const val ENCRYPTED_PIN_KEY = "encrypted_pin"
        private const val IV_KEY = "pin_iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val BIOMETRIC_PROMPTED_KEY = "biometric_prompted"
        // Key version tracking for backward compatibility
        // v1: AUTH_BIOMETRIC_STRONG only (pre-API 30 or legacy)
        // v2: AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL (API 30+)
        private const val KEY_VERSION_KEY = "key_version"
        private const val KEY_VERSION_DEVICE_CREDENTIAL = 2
    }

    fun isBiometricLinked(): Boolean {
        return sharedPreferences.getString(ENCRYPTED_PIN_KEY, null) != null
    }

    fun hasBeenPromptedForBiometric(): Boolean {
        return sharedPreferences.getBoolean(BIOMETRIC_PROMPTED_KEY, false)
    }

    fun setBiometricPrompted(prompted: Boolean) {
        sharedPreferences.edit().putBoolean(BIOMETRIC_PROMPTED_KEY, prompted).apply()
    }

    fun canAuthenticate(activity: FragmentActivity): Boolean {
        val biometricManager = BiometricManager.from(activity)
        // Device credential + CryptoObject only works on API 30+
        // On API 29 and below, CryptoObject requires BIOMETRIC_STRONG only
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Check if the current key supports device credential fallback.
     * Returns false for legacy keys (created before device credential support was added).
     */
    fun isKeyDeviceCredentialCapable(): Boolean {
        val version = sharedPreferences.getInt(KEY_VERSION_KEY, 1)
        return version >= KEY_VERSION_DEVICE_CREDENTIAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    @TargetApi(30)
    private fun createKeyGenSpecForApi30(): KeyGenParameterSpec {
        return KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            // Allow both biometric strong and device credential for fallback
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()
    }

    private fun createKeyGenSpecForApi26(): KeyGenParameterSpec {
        return KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(30)  // Fallback for API 26-29
            .build()
    }

    fun storePin(
        activity: FragmentActivity,
        pin: String,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cipher = try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            // Delete existing key if present (regenerate for new PIN)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                try {
                    keyStore.deleteEntry(KEY_ALIAS)
                } catch (e: java.security.KeyStoreException) {
                    // TEE busy or unavailable - log but continue, will regenerate
                }
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createKeyGenSpecForApi30()
            } else {
                createKeyGenSpecForApi26()
            }
            keyGenerator.init(spec)
            keyGenerator.generateKey()

            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }
        } catch (e: java.security.KeyStoreException) {
            onError("KEYSTORE_ERROR: ${e.message}")
            return
        } catch (e: java.security.NoSuchAlgorithmException) {
            onError("ALGORITHM_NOT_SUPPORTED: ${e.message}")
            return
        } catch (e: java.security.InvalidAlgorithmParameterException) {
            onError("KEYGEN_FAILED: ${e.message}")
            return
        } catch (e: java.security.InvalidKeyException) {
            onError("KEY_INVALID: ${e.message}")
            return
        } catch (e: java.security.NoSuchProviderException) {
            onError("PROVIDER_NOT_FOUND: ${e.message}")
            return
        } catch (e: IllegalStateException) {
            // SecureElement full or hardware unavailable
            onError("SECURE_ELEMENT_ERROR: ${e.message}")
            return
        } catch (e: Exception) {
            onError("CIPHER_INIT_FAILED: ${e.message}")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val authenticatedCipher = result.cryptoObject?.cipher
                        if (authenticatedCipher == null) {
                            onError("CIPHER_NULL")
                            return
                        }
                        val encrypted = authenticatedCipher.doFinal(pin.toByteArray(Charsets.UTF_8))
                        // Get IV from the authenticated cipher that actually performed encryption
                        val iv = authenticatedCipher.iv

                        val editor = sharedPreferences.edit()
                        editor.putString(ENCRYPTED_PIN_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                        editor.putString(IV_KEY, Base64.encodeToString(iv, Base64.NO_WRAP))
                        // Mark key version to track device credential capability
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            editor.putInt(KEY_VERSION_KEY, KEY_VERSION_DEVICE_CREDENTIAL)
                        } else {
                            editor.putInt(KEY_VERSION_KEY, 1)
                        }
                        editor.apply()

                        onSuccess()
                    } catch (e: Exception) {
                        onError("ENCRYPT_FAILED: ${e.message}")
                    }
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
                // Critical: DEVICE_CREDENTIAL + CryptoObject crashes on API < 30
                // On API 30+: allow device credential fallback, no negative button needed
                // On API 29-: only BIOMETRIC_STRONG, must set negative button text
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

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    fun decryptPin(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val encryptedPin = sharedPreferences.getString(ENCRYPTED_PIN_KEY, null)
        val ivData = sharedPreferences.getString(IV_KEY, null)
        if (encryptedPin == null || ivData == null) {
            onError("BIOMETRIC_NOT_LINKED")
            return
        }

        val cipher = try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val ivBytes = Base64.decode(ivData, Base64.NO_WRAP)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(128, ivBytes),
                )
            }
        } catch (e: Exception) {
            onError("CIPHER_INIT_FAILED: ${e.message}")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val authenticatedCipher = result.cryptoObject?.cipher
                        if (authenticatedCipher == null) {
                            onError("CIPHER_NULL")
                            return
                        }
                        val decrypted = authenticatedCipher.doFinal(
                            Base64.decode(encryptedPin, Base64.NO_WRAP)
                        )
                        val pin = String(decrypted, Charsets.UTF_8)
                        onSuccess(pin)
                    } catch (e: Exception) {
                        // Key may be incompatible with device credential - prompt user to re-link
                        val errorMsg = if (!isKeyDeviceCredentialCapable()) {
                            "KEY_INCOMPATIBLE: 请重新设置生物识别以启用设备密码后备"
                        } else {
                            "DECRYPT_FAILED: ${e.message}"
                        }
                        onError(errorMsg)
                    }
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
                // Critical: DEVICE_CREDENTIAL + CryptoObject crashes on API < 30
                // Also: Legacy keys (v1) don't support device credential - decryption fails
                // Check both API level AND key version before enabling DEVICE_CREDENTIAL
                val supportsDeviceCredential = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    isKeyDeviceCredentialCapable()

                if (supportsDeviceCredential) {
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

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    fun unlink() {
        val editor = sharedPreferences.edit()
        editor.remove(ENCRYPTED_PIN_KEY)
        editor.remove(IV_KEY)
        editor.remove(KEY_VERSION_KEY)
        editor.apply()

        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
        }
    }
}
