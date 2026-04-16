package com.lockit.data.biometric

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
    }

    fun isBiometricLinked(): Boolean {
        return sharedPreferences.getString(ENCRYPTED_PIN_KEY, null) != null
    }

    fun canAuthenticate(activity: FragmentActivity): Boolean {
        val biometricManager = BiometricManager.from(activity)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun storePin(activity: FragmentActivity, pin: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val cipher = try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .build()
            )
            keyGenerator.generateKey()

            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }
        } catch (e: Exception) {
            onError("CIPHER_INIT_FAILED: ${e.message}")
            return
        }

        val iv = cipher.iv
        val encrypted = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))

        val editor = sharedPreferences.edit()
        editor.putString(ENCRYPTED_PIN_KEY, encrypted.toString(Charsets.ISO_8859_1))
        editor.putString(IV_KEY, iv.toString(Charsets.ISO_8859_1))
        editor.apply()

        onSuccess()
    }

    fun decryptPin(activity: FragmentActivity, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
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
            Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(128, ivData.toByteArray(Charsets.ISO_8859_1)),
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
                        val decrypted = result.cryptoObject?.cipher?.doFinal(
                            encryptedPin.toByteArray(Charsets.ISO_8859_1)
                        )
                        val pin = String(decrypted!!, Charsets.UTF_8)
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
            .setTitle("Unlock Vault")
            .setSubtitle("Authenticate to decrypt your vault PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
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
