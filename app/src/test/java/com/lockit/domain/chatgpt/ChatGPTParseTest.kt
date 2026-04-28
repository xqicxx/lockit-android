package com.lockit.domain.chatgpt

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/**
 * Local parse tests for ChatGPT usage API response formats.
 * Run: ./gradlew testDebugUnitTest --tests "com.lockit.domain.chatgpt.ChatGPTParseTest"
 */
class ChatGPTParseTest {

    // ════════════════════════════════════════════════════
    // Format 1: current_usage + limit (ChatGPT raw API)
    // ════════════════════════════════════════════════════
    @Test
    fun `parse raw ChatGPT API — current_usage + limit`() {
        val window = JSONObject("""{"current_usage": 45, "limit": 100, "window_seconds": 18000, "resets_in_seconds": 3600}""")
        val r = ChatGPTCodingPlan.parseWindow(window)
        assertEquals("used", 45, r.used)
        assertEquals("total", 100, r.total)
        assertEquals("pct", 45.0, r.usedPercent, 0.01)
        println("PASS raw API: $r")
    }

    // ════════════════════════════════════════════════════
    // Format 2: used_percent (CodexBar / caut normalized)
    // ════════════════════════════════════════════════════
    @Test
    fun `parse normalized percentage — used_percent`() {
        val window = JSONObject("""{"used_percent": 72.5, "limit_window_seconds": 18000}""")
        val r = ChatGPTCodingPlan.parseWindow(window)
        assertEquals("used from 72.5%", 72, r.used)
        assertEquals("total default", 100, r.total)
        assertEquals("pct", 72.5, r.usedPercent, 0.01)
        println("PASS percent: $r")
    }

    // ════════════════════════════════════════════════════
    // Format 3: used + total
    // ════════════════════════════════════════════════════
    @Test
    fun `parse used + total`() {
        val window = JSONObject("""{"used": 30, "total": 100}""")
        val r = ChatGPTCodingPlan.parseWindow(window)
        assertEquals(30, r.used)
        assertEquals(100, r.total)
        assertEquals(30.0, r.usedPercent, 0.01)
        println("PASS used/total: $r")
    }

    // ════════════════════════════════════════════════════
    // Format 4: remaining + total
    // ════════════════════════════════════════════════════
    @Test
    fun `parse remaining + total`() {
        val window = JSONObject("""{"remaining": 55, "total": 100}""")
        val r = ChatGPTCodingPlan.parseWindow(window)
        assertEquals("used = 100-55", 45, r.used)
        assertEquals(100, r.total)
        assertEquals(45.0, r.usedPercent, 0.01)
        println("PASS remaining/total: $r")
    }

    // ════════════════════════════════════════════════════
    // Format 5: usedPercent (camelCase variant)
    // ════════════════════════════════════════════════════
    @Test
    fun `parse camelCase usedPercent`() {
        val window = JSONObject("""{"usedPercent": 88.0}""")
        val r = ChatGPTCodingPlan.parseWindow(window)
        assertEquals(88, r.used)
        assertEquals(100, r.total)
        println("PASS usedPercent: $r")
    }

    // ════════════════════════════════════════════════════
    // Edge cases
    // ════════════════════════════════════════════════════
    @Test
    fun `null window returns zeros`() {
        val r = ChatGPTCodingPlan.parseWindow(null)
        assertEquals(0, r.used)
        assertEquals(0, r.total)
        assertEquals(0.0, r.usedPercent, 0.01)
        println("PASS null → zeros")
    }

    @Test
    fun `empty window returns zeros`() {
        val r = ChatGPTCodingPlan.parseWindow(JSONObject("{}"))
        assertEquals(0, r.used)
        assertEquals(0, r.total)
        println("PASS empty → zeros")
    }

    @Test
    fun `flat fields override window`() {
        val window = null  // simulate no window object
        val r = ChatGPTCodingPlan.parseWindow(window, flatLimit = 15, flatUsed = 8, defaultTotal = 100)
        assertEquals(8, r.used)
        assertEquals(15, r.total)
        assertEquals(8.0 / 15.0 * 100.0, r.usedPercent, 0.01)
        println("PASS flat: $r")
    }
}
