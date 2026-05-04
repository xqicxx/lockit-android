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
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [VaultFileProvider] that serializes credentials as Rust-compatible
 * VaultPayload JSON instead of raw SQLite bytes.
 *
 * On push: decrypts all credentials from Room → JSON → bytes.
 * On pull: parses JSON → encrypts each credential → upserts into Room.
 * Falls back to legacy binary SQLite format if JSON parsing fails.
 */
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
        val dtos: List<CredentialDto> = entities.map { e -> entityToDto(e) }
        val payload = VaultPayload(
            schemaVersion = 2,
            credentials = dtos,
            auditEvents = emptyList(),
        )
        return json.encodeToString(payload)
    }

    // ── Checksum ──────────────────────────────────────────────────

    override fun computeChecksum(): String {
        val vaultBytes = readVaultBytes()
        val digest = MessageDigest.getInstance("SHA-256")
        val hash: ByteArray = vaultBytes.inputStream().use { input: InputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest()
        }
        return "sha256:" + hash.joinToString("") { b -> "%02x".format(b) }
    }

    // ── Import: VaultPayload JSON → Room ──────────────────────────

    override fun closeAndReplace(newBytes: ByteArray) {
        val content = String(newBytes, Charsets.UTF_8)

        // Try JSON format first
        if (content.trimStart().startsWith("{")) {
            importVaultPayloadJson(content)
            return
        }

        // Fallback: legacy SQLite binary format
        importLegacyBytes(newBytes)
    }

    private fun importVaultPayloadJson(content: String) {
        val payload: VaultPayload = json.decodeFromString(content)
        val dao = database.credentialDao()

        // Attempt import. If anything throws, the caller (closeAndReplace/newBytes)
        // will be caught by the sync engine and reported as failure.
        onIo { dao.getAllEntities() }.forEach { entity ->
            onIo { dao.delete(entity) }
        }
        for (dto in payload.credentials) {
            onIo { dao.insert(dtoToEntity(dto)) }
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
            fields["value"] = plaintext
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
        val value = dto.fields["value"] ?: ""
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
        val obj = org.json.JSONObject(raw)
        val map = mutableMapOf<String, String>()
        for (key in obj.keys()) {
            map[key] = obj.optString(key, "")
        }
        return map
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
            Instant.now().toEpochMilli()
        }
    }

    companion object {
        private fun <T> onIo(block: suspend () -> T): T {
            return runBlocking(Dispatchers.IO) { block() }
        }
    }
}
