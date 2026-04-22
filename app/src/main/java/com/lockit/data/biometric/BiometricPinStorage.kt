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
        // Check for biometric OR device credential (password/PIN/pattern)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun canAuthenticateWithDeviceCredential(activity: FragmentActivity): Boolean {
        val biometricManager = BiometricManager.from(activity)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
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
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            // Note: Cannot set negative button text when DEVICE_CREDENTIAL is enabled
            // Android automatically shows "Use device credential" option
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
                        onError("DECRYPT_FAILED: ${e.message}")
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
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            // Note: Cannot set negative button text when DEVICE_CREDENTIAL is enabled
            // Android automatically shows "Use device credential" option
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    fun unlink() {
        val editor = sharedPreferences.edit()
        editor.remove(ENCRYPTED_PIN_KEY)
        editor.remove(IV_KEY)
        editor.apply()

        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
        }
    }
}
