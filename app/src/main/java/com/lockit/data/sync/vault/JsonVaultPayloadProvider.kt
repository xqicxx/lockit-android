package com.lockit.data.sync.vault

import androidx.room.withTransaction
import com.lockit.data.database.CredentialEntity
import com.lockit.data.database.LockitDatabase
import com.lockit.data.sync.VaultFileProvider
import com.lockit.data.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class JsonVaultPayloadProvider(
    private val vaultManager: VaultManager,
    private val database: LockitDatabase,
) : VaultFileProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    override fun getVaultFile(): File =
        LockitDatabase.getDatabaseFile(vaultManager.context)

    // ── Export: Room → VaultPayload JSON ──────────────────────────

    override fun readVaultBytes(): ByteArray {
        return exportVaultPayloadJson().toByteArray(Charsets.UTF_8)
    }

    fun exportVaultPayloadJson(): String {
        val entities = onIo { database.credentialDao().getAllEntities() }
        val dtos = entities.map { e -> entityToDto(e) }
        val payload = VaultPayload(
            schemaVersion = 2,
            credentials = dtos,
            auditEvents = emptyList(),
        )
        return json.encodeToString(payload)
    }

    // ── Checksum ──────────────────────────────────────────────────

    override fun computeChecksum(): String {
        if (!vaultManager.isUnlocked()) return "sha256:empty"
        val jsonString = exportVaultPayloadJson()
        val bytes = jsonString.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return "sha256:" + hash.joinToString("") { "%02x".format(it) }
    }

    // ── Import: VaultPayload JSON → Room ──────────────────────────

    override fun closeAndReplace(newBytes: ByteArray) {
        val content = String(newBytes, Charsets.UTF_8)

        if (content.trimStart().startsWith("{")) {
            runBlocking(Dispatchers.IO) { importVaultPayloadJson(content) }
            return
        }

        // Fallback: legacy SQLite binary format
        importLegacyBytes(newBytes)
    }

    private suspend fun importVaultPayloadJson(content: String) {
        val payload: VaultPayload = try {
            json.decodeFromString(content)
        } catch (_: Exception) {
            VaultPayload()
        }
        val dao = database.credentialDao()
        val entities = payload.credentials.map { dtoToEntity(it) }

        database.withTransaction {
            val existing = dao.getAllEntities()
            for (entity in existing) {
                dao.delete(entity)
            }
            for (entity in entities) {
                dao.insert(entity)
            }
        }
    }

    private fun importLegacyBytes(newBytes: ByteArray) {
        LockitDatabase.closeAndReset(vaultManager.context)
        val dbFile = getVaultFile()
        val backupFile = File(dbFile.parent, "vault.db.backup")
        if (dbFile.exists()) {
            dbFile.copyTo(backupFile, overwrite = true)
        }
        dbFile.delete()
        File(dbFile.parent, "${dbFile.name}-wal").delete()
        File(dbFile.parent, "${dbFile.name}-shm").delete()
        dbFile.writeBytes(newBytes)
    }

    // ── Entity ↔ DTO mapping ──────────────────────────────────────

    private fun entityToDto(entity: CredentialEntity): CredentialDto {
        val plaintext = vaultManager.decryptCredentialValue(entity)
        val typeName = entity.type.ifEmpty { "custom" }
        val fields = mutableMapOf<String, String>()
        if (plaintext.isNotEmpty()) {
            fields["secret_value"] = plaintext
        }
        val metadataMap = parseMetadataJson(entity.metadata)
        val formatter = DateTimeFormatter.ISO_INSTANT

        return CredentialDto(
            id = entity.id,
            name = entity.name,
            credentialType = typeName,
            service = entity.service,
            key = entity.key,
            fields = fields,
            metadata = metadataMap,
            tags = emptyList(),
            createdAt = formatter.format(
                Instant.ofEpochMilli(entity.createdAt).atOffset(ZoneOffset.UTC)
            ),
            updatedAt = formatter.format(
                Instant.ofEpochMilli(entity.updatedAt).atOffset(ZoneOffset.UTC)
            ),
        )
    }

    private fun dtoToEntity(dto: CredentialDto): CredentialEntity {
        val value = dto.fields["secret_value"] ?: ""
        val encryptedValue = vaultManager.encryptValueForImport(value)
        val metadataJson = buildMetadataJson(dto.metadata)
        val createdAt = safeParseInstant(dto.createdAt)
        val updatedAt = safeParseInstant(dto.updatedAt)

        return CredentialEntity(
            id = dto.id,
            name = dto.name,
            type = dto.credentialType,
            service = dto.service,
            key = dto.key,
            value = encryptedValue,
            metadata = metadataJson,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun parseMetadataJson(raw: String): Map<String, String> {
        if (raw.isBlank() || raw == "{}") return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            val map = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                map[key] = obj.optString(key, "")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun buildMetadataJson(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        val obj = org.json.JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        return obj.toString()
    }

    private fun safeParseInstant(iso: String): Long {
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            Instant.EPOCH.toEpochMilli()
        }
    }

    companion object {
        private fun <T> onIo(block: suspend () -> T): T {
            return runBlocking(Dispatchers.IO) { block() }
        }
    }
}
