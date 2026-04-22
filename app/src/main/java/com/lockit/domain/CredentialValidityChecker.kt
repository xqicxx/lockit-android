package com.lockit.domain

import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility to check if a credential's authentication data is still valid.
 * Used for displaying expiry status in UI.
 */
object CredentialValidityChecker {

    /**
     * Check if a CodingPlan credential's auth data is still valid.
     * Attempts to fetch quota - if successful, credential is valid.
     *
     * @param credential The credential to check
     * @return true if valid, false if expired/invalid
     */
    suspend fun isCredentialValid(credential: Credential): Boolean = withContext(Dispatchers.IO) {
        if (credential.type != CredentialType.CodingPlan) true // Non-CodingPlan credentials don't expire
        else {
            val provider = credential.metadata["provider"]
            if (provider == null) false
            else {
                val fetcher = CodingPlanFetchers.forProvider(provider)
                if (fetcher == null) false
                else {
                    try {
                        val quota = fetcher.fetchQuota(credential.metadata)
                        quota != null && quota.status.isNotBlank()
                    } catch (e: Exception) {
                        android.util.Log.e("CredentialValidity", "Check failed for ${credential.name}: ${e.message}")
                        false
                    }
                }
            }
        }
    }

    /**
     * Batch check multiple credentials.
     * Returns a map of credential ID to validity status.
     */
    suspend fun checkCredentials(credentials: List<Credential>): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val codingPlanCreds = credentials.filter { it.type == CredentialType.CodingPlan }
        val results = mutableMapOf<String, Boolean>()

        for (cred in codingPlanCreds) {
            results[cred.id] = isCredentialValid(cred)
        }

        // Non-CodingPlan credentials are always "valid" (no expiry)
        credentials.filter { it.type != CredentialType.CodingPlan }.forEach {
            results[it.id] = true
        }

        results
    }
}