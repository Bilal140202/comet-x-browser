package com.cometx.browser.browse

/**
 * TranslateSupport (v2.0.0) — one-tap full-page translation through Google's
 * `translate.goog` proxy.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). Translates the
 * current page in the same tab (subsequent links stay translated), targeting
 * the device language, with no API key. While on a proxy page the menu offers
 * *View original*, which returns to the stored pre-translation URL (or a
 * best-effort reconstruction when the translation was opened from a link).
 *
 * Pure JVM (manual URL surgery, no android.net.Uri) — part of the unit-test
 * gate.
 */
object TranslateSupport {

    private const val PROXY_SUFFIX = ".translate.goog"

    fun isTranslated(url: String?): Boolean {
        if (url == null) return false
        val host = hostOf(url) ?: return false
        return host == "translate.goog" || host.endsWith(PROXY_SUFFIX)
    }

    /**
     * Builds the proxy URL for [url]: host dots become dashes under
     * translate.goog, `_x_tr_*` parameters steer the language pair.
     */
    fun translateUrl(url: String, targetLang: String): String? {
        val t = url.trim()
        if (!t.lowercase().startsWith("http")) return null
        val schemeEnd = t.indexOf("://")
        if (schemeEnd < 0) return null
        var rest = t.substring(schemeEnd + 3)
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
        val tail = if (pathStart < 0) "" else rest.substring(pathStart)
        val host = authority.substringAfterLast("@").substringBefore(":")
        if (host.isEmpty() || host.contains(":")) return null
        val lang = targetLang.ifBlank { "en" }
        val sep = if (tail.contains("?")) {
            if (tail.endsWith("?") || tail.endsWith("&")) "" else "&"
        } else "?"
        return "https://${host.replace('.', '-')}$PROXY_SUFFIX$tail${sep}" +
            "_x_tr_sl=auto&_x_tr_tl=$lang&_x_tr_hl=$lang&_x_tr_pto=ajax,elem"
    }

    /**
     * Best-effort inverse of the translate.goog host rewriting: recover the
     * original scheme/host and strip every `_x_tr_*` parameter.
     */
    fun originalFromTranslateUrl(url: String): String? {
        if (!isTranslated(url)) return null
        val t = url.trim()
        val schemeEnd = t.indexOf("://")
        if (schemeEnd < 0) return null
        var rest = t.substring(schemeEnd + 3)
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
        val tail = if (pathStart < 0) "" else rest.substring(pathStart)
        val host = authority.substringAfterLast("@").substringBefore(":")
        val idx = host.lastIndexOf(PROXY_SUFFIX)
        if (idx <= 0) return null
        val origHost = host.substring(0, idx).replace('-', '.')
        if (origHost.isEmpty() || origHost.startsWith(".") || origHost.endsWith(".")) return null
        // strip _x_tr_* query params, keep everything else in order
        val qIdx = tail.indexOf('?')
        val path = if (qIdx < 0) tail else tail.substring(0, qIdx)
        val out = StringBuilder("https://").append(origHost).append(path)
        if (qIdx >= 0) {
            val query = tail.substring(qIdx + 1)
            val kept = ArrayList<String>()
            val fragIdx = query.indexOf('#')
            val frag = if (fragIdx >= 0) query.substring(fragIdx) else ""
            val qOnly = if (fragIdx >= 0) query.substring(0, fragIdx) else query
            for (pair in qOnly.split('&')) {
                if (pair.isEmpty()) continue
                if (pair.startsWith("_x_tr_")) continue
                kept.add(pair)
            }
            if (kept.isNotEmpty()) {
                out.append('?').append(kept.joinToString("&"))
            }
            out.append(frag)
        }
        return out.toString()
    }

    /** Lowercased hostname (pure). */
    private fun hostOf(url: String): String? {
        var rest = url.trim()
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) rest = rest.substring(schemeEnd + 3)
        val slash = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (slash >= 0) rest = rest.substring(0, slash)
        val host = rest.substringAfterLast("@").substringBefore(":")
        return host.lowercase().ifEmpty { null }
    }
}
