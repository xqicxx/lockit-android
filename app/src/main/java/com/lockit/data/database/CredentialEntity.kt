package com.lockit.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "credentials",
    indices = [
        Index("service"),
        Index("name")
    ]
)
data class CredentialEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: String,
    val service: String,
    val key: String,
    val value: ByteArray,          // encrypted (nonce + ciphertext)
    val metadata: String = "{}",   // JSON string
    val createdAt: Long,           // epoch millis
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CredentialEntity
        return id == other.id && name == other.name && type == other.type &&
            service == other.service && key == other.key &&
            value.contentEquals(other.value) && metadata == other.metadata &&
            createdAt == other.createdAt && updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + service.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
