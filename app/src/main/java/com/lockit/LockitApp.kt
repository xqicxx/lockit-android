package com.lockit

import android.app.Application
import android.content.Context
import com.lockit.data.audit.AuditLogger
import com.lockit.data.crypto.KeyManager
import com.lockit.data.database.LockitDatabase
import com.lockit.data.vault.VaultManager
import com.lockit.utils.LocaleHelper

class LockitApp : Application() {

    val database: LockitDatabase by lazy { LockitDatabase.getInstance(this) }
    val keyManager: KeyManager by lazy { KeyManager(this) }
    val auditLogger: AuditLogger by lazy { AuditLogger(this) }
    val vaultManager: VaultManager by lazy {
        VaultManager(this, database.credentialDao(), keyManager, auditLogger)
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }
}
