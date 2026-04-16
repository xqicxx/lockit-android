package com.lockit.domain.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class Credential(
    val id: String,
    val name: String,
    val type: CredentialType,
    val service: String,
    val key: String,
    val value: String,           // decrypted value
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun formatTimestamp(instant: Instant): String {
        val now = Instant.now()
        val days = java.time.Duration.between(instant, now).toDays()
        return when {
            days == 0L -> "TODAY"
            days == 1L -> "1D_AGO"
            days < 30 -> "${days}D_AGO"
            else -> DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC)
                .format(instant)
        }
    }

    fun formatCreatedAt(): String = formatTimestamp(createdAt)
    fun formatUpdatedAt(): String = formatTimestamp(updatedAt)

    fun refId(): String = "0x${id.take(5).uppercase()}"

    /**
     * Returns a human-readable title for display, adapting to credential type.
     * For types where `name` is a meaningful label (ApiKey, Note, Token, etc.),
     * returns the name. For types where `name` is a raw value (Phone, BankCard,
     * Email), returns the type display name.
     */
    fun displayTitle(): String = when (this.type) {
        CredentialType.Phone -> "PHONE"
        CredentialType.BankCard -> "CARD ****${name.takeLast(4)}"
        CredentialType.Email -> "EMAIL"
        CredentialType.IdCard -> "ID CARD"
        CredentialType.Note -> name.takeIf { it.isNotBlank() } ?: "NOTE"
        CredentialType.Account -> service.takeIf { it.isNotBlank() } ?: name
        else -> name
    }
}
