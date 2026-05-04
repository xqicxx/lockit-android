package com.lockit.data.sync.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON model matching Rust's [Credential] exactly.
 *
 * ```json
 * {
 *   "id": "uuid",
 *   "name": "OPENAI",
 *   "type": "api_key",
 *   "service": "openai",
 *   "key": "default",
 *   "fields": {"secret_value": "sk-abc"},
 *   "metadata": {},
 *   "tags": [],
 *   "created_at": "2026-05-04T10:30:00Z",
 *   "updated_at": "2026-05-04T10:30:00Z"
 * }
 * ```
 */
@Serializable
data class CredentialDto(
    val id: String,
    val name: String,
    @SerialName("type")
    val credentialType: String,
    val service: String,
    val key: String,
    val fields: Map<String, String>,
    val metadata: Map<String, String>,
    val tags: List<String>,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)
