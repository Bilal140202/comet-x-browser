package com.cometx.browser.memory

import android.content.Context
import com.cometx.browser.util.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MemoryStore — three memory tiers (brief §25):
 *  1. Session memory: the running task's goal + step log (kept hot by the engine)
 *  2. Browser memory: last-visited URL + recent task summaries (persisted)
 *  3. User memory: explicit facts saved via agent `remember` action or Settings
 *
 * Storage: JSON files under filesDir/cometx/memory. Nothing leaves the device.
 * The user can view / clear / disable memory (disable is honored at write time).
 */
class MemoryStore(private val baseDir: File, private val enabled: () -> Boolean) {

    private val dir: File get() = File(baseDir, "cometx/memory").apply { mkdirs() }

    private val userMemoryFile: File get() = File(dir, "user_memory.json")
    private val recentFile: File get() = File(dir, "recent_tasks.json")
    private val browserStateFile: File get() = File(dir, "browser_state.json")

    // ---------- Session (in-process, one per agent run) ----------

    data class SessionEntry(val step: Int, val action: String, val result: String, val note: String)

    class Session(val goal: String, val startedAt: Long) {
        val entries = mutableListOf<SessionEntry>()
        fun add(step: Int, action: String, result: String, note: String) {
            entries.add(SessionEntry(step, action.take(60), result.take(200), note.take(160)))
            if (entries.size > 60) entries.removeAt(0)
        }
    }

    // ---------- User memory ----------

    // Expert-review P1-19: the agent loop runs on the main thread — file reads
    // are cached so each step doesn't hit disk. Writes keep the cache hot.
    private var userMemoryCache: MutableMap<String, String>? = null
    private var recentCache: List<Triple<String, String, Long>>? = null

    fun userMemory(): MutableMap<String, String> {
        userMemoryCache?.let { return LinkedHashMap(it) }
        val map = linkedMapOf<String, String>()
        val arr = Json.parseArrayOrNull(readFile(userMemoryFile)) ?: return map
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map[o.optString("key")] = o.optString("value")
        }
        userMemoryCache = LinkedHashMap(map)
        return map
    }

    fun remember(key: String, fact: String) {
        if (!enabled()) return
        val map = userMemory()
        map[key.take(60)] = fact.take(500)
        val arr = JSONArray()
        for ((k, v) in map) arr.put(Json.obj("key", k, "value", v))
        writeFile(userMemoryFile, arr.toString())
        userMemoryCache = LinkedHashMap(map)
    }

    fun forget(key: String) {
        val map = userMemory()
        map.remove(key)
        val arr = JSONArray()
        for ((k, v) in map) arr.put(Json.obj("key", k, "value", v))
        writeFile(userMemoryFile, arr.toString())
        userMemoryCache = LinkedHashMap(map)
    }

    // ---------- Browser memory ----------

    fun saveBrowserState(url: String, title: String) {
        if (!enabled()) return
        writeFile(browserStateFile, Json.obj("url", url, "title", title, "at", System.currentTimeMillis()).toString())
    }

    fun lastBrowserState(): Pair<String, String>? {
        val o = Json.parseOrNull(readFile(browserStateFile)) ?: return null
        return o.optString("url") to o.optString("title")
    }

    fun addRecentTask(goal: String, outcome: String) {
        if (!enabled()) return
        val arr = Json.parseArrayOrNull(readFile(recentFile)) ?: JSONArray()
        val next = JSONArray().put(Json.obj("goal", goal.take(200), "outcome", outcome.take(60), "at", System.currentTimeMillis()))
        for (i in 0 until minOf(arr.length(), 9)) next.put(arr.optJSONObject(i) ?: continue)
        writeFile(recentFile, next.toString())
        recentCache = null
    }

    fun recentTasks(): List<Triple<String, String, Long>> {
        recentCache?.let { return it }
        val arr = Json.parseArrayOrNull(readFile(recentFile)) ?: return emptyList()
        val out = mutableListOf<Triple<String, String, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Triple(o.optString("goal"), o.optString("outcome"), o.optLong("at")))
        }
        recentCache = out
        return out
    }

    // ---------- Maintenance ----------

    fun clearAll() {
        userMemoryFile.delete()
        recentFile.delete()
        browserStateFile.delete()
        userMemoryCache = null
        recentCache = null
    }

    fun exportSummary(): JSONObject = Json.obj(
        "user_memory_keys", JSONArray(userMemory().keys.toList()),
        "recent_tasks", recentTasks().size,
        "enabled", enabled()
    )

    private fun readFile(f: File): String = if (f.exists()) f.readText() else ""
    private fun writeFile(f: File, content: String) {
        if (!enabled()) return
        try { f.writeText(content) } catch (e: Exception) { /* storage full etc. */ }
    }
}
