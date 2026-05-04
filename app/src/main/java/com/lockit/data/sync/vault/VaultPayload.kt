package com.lockit.data.sync.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level vault payload matching Rust's [VaultPayload] exactly.
 *
 * This is the plaintext JSON that sits inside the AES-256-GCM ciphertext
 * of vault.enc. Android serializes this instead of the raw SQLite DB.
 */
@Serializable
data class VaultPayload(
    @SerialName("schema_version")
    val schemaVersion: Int = 2,
    val credentials: List<CredentialDto> = emptyList(),
    @SerialName("audit_events")
    val auditEvents: List<AuditEventDto> = emptyList(),
)
