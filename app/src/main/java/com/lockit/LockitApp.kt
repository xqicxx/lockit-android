package com.lockit

import android.app.Application
import android.content.Context
import com.lockit.data.audit.AuditLogger
import com.lockit.data.crypto.KeyManager
import com.lockit.data.database.LockitDatabase
import com.lockit.data.vault.VaultManager
import com.lockit.utils.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LockitApp : Application() {

    val database: LockitDatabase by lazy { LockitDatabase.getInstance(this) }
    val keyManager: KeyManager by lazy { KeyManager(this) }
    val auditLogger: AuditLogger by lazy { AuditLogger(this) }
    val vaultManager: VaultManager by lazy {
        VaultManager(this, database.credentialDao(), keyManager, auditLogger)
    }

    // Shared state for vault recovery detection
    private val _needsRecovery = MutableStateFlow(false)
    val needsRecovery: kotlinx.coroutines.flow.StateFlow<Boolean> = _needsRecovery.asStateFlow()

    fun setNeedsRecovery(value: Boolean) {
        _needsRecovery.value = value
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }
}
