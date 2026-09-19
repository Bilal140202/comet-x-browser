package com.cometx.browser.browse

import org.json.JSONArray
import org.json.JSONObject

/**
 * SearchEngines (v2.0.0) — built-in and user-defined search engines.
 *
 * Ported from the Zerium codebase and adapted to Comet-X: the omnibox
 * resolver heuristic lives in [com.cometx.browser.util.UserInput]; this object
 * supplies the QUERY TEMPLATE a search is executed with (built-ins first, then
 * the user's custom engines, which must carry a `%s` placeholder).
 *
 * Pure JVM (no Android imports) — part of the unit-test gate. The custom
 * engine list is stored as a JSON array of `{"name":…,"url":…}`.
 */
object SearchEngines {

    /** One selectable search engine (built-in or user-defined). */
    data class Engine(val name: String, val query: String, val custom: Boolean)

    /**
     * Built-in engines. Google is intentionally FIRST (index 0) — Comet-X
     * shipped with Google search from v1.0 (UserInput.resolve default), so
     * the default index preserves every existing behavior byte-for-byte.
     */
    val BUILTIN: List<Engine> = listOf(
        Engine("Google", "https://www.google.com/search?q=%s", false),
        Engine("DuckDuckGo", "https://duckduckgo.com/?q=%s", false),
        Engine("Brave Search", "https://search.brave.com/search?q=%s", false),
        Engine("Startpage", "https://www.startpage.com/sp/search?query=%s", false),
        Engine("Bing", "https://www.bing.com/search?q=%s", false),
        Engine("Wikipedia", "https://en.wikipedia.org/w/index.php?search=%s", false)
    )

    const val MAX_CUSTOM = 20
    const val MAX_NAME = 40

    /** All engines: built-ins first, then the user's custom ones. Never null. */
    fun allEngines(customJson: String?): List<Engine> = BUILTIN + parseCustomEngines(customJson)

    /** Engine for an index, clamped into range. Never null. */
    fun engineAt(customJson: String?, index: Int): Engine {
        val all = allEngines(customJson)
        return all[index.coerceIn(0, all.size - 1)]
    }

    /** Query template for the given engine index, clamped into range. */
    fun templateFor(customJson: String?, index: Int): String = engineAt(customJson, index).query

    fun validCustomEngine(name: String?, url: String?): Boolean {
        val n = name?.trim() ?: return false
        val u = url?.trim() ?: return false
        return n.isNotEmpty() && n.length <= MAX_NAME &&
            u.startsWith("http") && u.contains("%s")
    }

    /** Parses the stored custom-engine JSON; invalid entries are skipped silently. */
    fun parseCustomEngines(raw: String?): List<Engine> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Engine>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                val url = o.optString("url", "").trim()
                if (validCustomEngine(name, url)) out.add(Engine(name, url, true))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Serializes custom engines back to the stored JSON form. */
    fun serializeCustomEngines(engines: List<Engine>): String {
        val arr = JSONArray()
        for (e in engines) {
            if (!e.custom) continue
            try {
                arr.put(JSONObject().put("name", e.name).put("url", e.query))
            } catch (_: Exception) {
            }
        }
        return arr.toString()
    }

    /**
     * True when the input text is a navigation (URL) rather than a search
     * query — mirrors UserInput's heuristic so the start page's JS and the
     * omnibox agree on what happens with a given string.
     */
    fun looksLikeUrl(raw: String): Boolean {
        val input = raw.trim()
        if (input.isEmpty()) return false
        val lower = input.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        return !input.contains(" ") && (input.contains(".") || lower == "localhost") && !input.endsWith(".")
    }

    /**
     * Builds a search URL for [query] against [template]. The template must
     * contain `%s` (validated at engine level); the query is form-encoded
     * exactly like UserInput.encodeQuery (spaces as %20).
     */
    fun searchUrl(query: String, template: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return template.replace("%s", encoded)
    }
}
