package com.lockit.data.sync.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON model matching Rust's [AuditEvent] exactly.
 */
@Serializable
data class AuditEventDto(
    val event: String,
    @SerialName("credential_id")
    val credentialId: String? = null,
    val field: String? = null,
    val at: String,
)
