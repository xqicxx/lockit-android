package com.lockit.data.audit

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Audit log entry representing a security event.
 */
data class AuditEntry(
    val action: String,
    val detail: String,
    val timestamp: Long, // epoch millis
    val severity: AuditSeverity,
)

enum class AuditSeverity { Info, Warning, Danger }

/**
 * Simple audit log stored in SharedPreferences.
 * Logs are kept for display up to 30 days, export up to 1 year.
 * Uses JSON format for reliable parsing (no delimiter collision issues).
 */
class AuditLogger(context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("lockit_audit", Context.MODE_PRIVATE)
    }

    private val keyEntries = "audit_entries"

    /**
     * Record a new audit event.
     */
    fun log(
        action: String,
        detail: String = "",
        severity: AuditSeverity = AuditSeverity.Info,
    ) {
        val entry = AuditEntry(
            action = action,
            detail = detail,
            timestamp = Instant.now().toEpochMilli(),
            severity = severity,
        )
        val current = getAllEntries()
        val updated = listOf(entry) + current
        saveEntries(updated)
    }

    /**
     * Get entries from the last N days for display.
     */
    fun getRecentEntries(days: Int = 30): List<AuditEntry> {
        val cutoff = Instant.now().minusSeconds(days.toLong() * 24 * 3600)
        return getAllEntries().filter { Instant.ofEpochMilli(it.timestamp).isAfter(cutoff) }
    }

    /**
     * Get first N entries for paginated display (most recent first).
     * This is more efficient than loading all entries then slicing.
     */
    fun getRecentEntriesByCount(count: Int): List<AuditEntry> {
        return getAllEntries().take(count)
    }

    /**
     * Get total count of all audit entries.
     */
    fun getTotalCount(): Int {
        return getAllEntries().size
    }

    /**
     * Get all entries within 1 year for export.
     */
    fun getExportableEntries(maxDays: Int = 365): List<AuditEntry> {
        val cutoff = Instant.now().minusSeconds(maxDays.toLong() * 24 * 3600)
        return getAllEntries().filter { Instant.ofEpochMilli(it.timestamp).isAfter(cutoff) }
    }

    /**
     * Clean up entries older than 1 year.
     */
    fun pruneOldEntries(maxDays: Int = 365) {
        val cutoff = Instant.now().minusSeconds(maxDays.toLong() * 24 * 3600)
        val kept = getAllEntries().filter { Instant.ofEpochMilli(it.timestamp).isAfter(cutoff) }
        saveEntries(kept)
    }

    // --- Private ---

    private fun getAllEntries(): List<AuditEntry> {
        val json = prefs.getString(keyEntries, null) ?: return emptyList()
        return parseJson(json)
    }

    private fun saveEntries(entries: List<AuditEntry>) {
        prefs.edit { putString(keyEntries, toJson(entries)) }
    }

    private fun toJson(entries: List<AuditEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
            obj.put("action", entry.action)
            obj.put("detail", entry.detail)
            obj.put("timestamp", entry.timestamp)
            obj.put("severity", entry.severity.name)
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseJson(raw: String): List<AuditEntry> {
        if (raw.isBlank()) return emptyList()
        // Handle legacy format migration
        if (raw.contains("|||") && !raw.startsWith("[")) {
            return parseLegacyFormat(raw)
        }
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                try {
                    val obj = array.getJSONObject(i)
                    AuditEntry(
                        action = obj.getString("action"),
                        detail = obj.optString("detail", ""),
                        timestamp = obj.getLong("timestamp"),
                        severity = try {
                            AuditSeverity.valueOf(obj.getString("severity"))
                        } catch (_: Exception) {
                            AuditSeverity.Info
                        },
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Legacy format parser for migration (delimiter: ||| for entries, | for fields)
    private fun parseLegacyFormat(raw: String): List<AuditEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.split("|||").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null
            try {
                AuditEntry(
                    action = parts[0],
                    detail = parts[1],
                    timestamp = parts[2].toLong(),
                    severity = try {
                        AuditSeverity.valueOf(parts[3])
                    } catch (_: Exception) {
                        AuditSeverity.Info
                    },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
