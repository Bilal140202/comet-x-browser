package com.cometx.browser.browse

/**
 * HttpsFirst (v2.0.0) — HTTPS-first main-frame upgrades.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). Main-frame `http://`
 * navigations are rewritten to `https://` before they leave the browser;
 * local addresses that have no TLS are skipped. Certificate failures still
 * raise the explicit user decision dialog, so a failed upgrade is always
 * visible rather than silently downgraded. Sub-resource (mixed content)
 * decisions remain the WebView compatibility mode's.
 *
 * Pure JVM — part of the unit-test gate.
 */
object HttpsFirst {

    /** True when [host] is a real internet host that should be upgraded. */
    fun isUpgradableHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
            h.endsWith(".lan") || h.endsWith(".internal") || h.endsWith(".home")
        ) return false
        if (h.startsWith("10.") || h.startsWith("127.") || h.startsWith("192.168.") ||
            h.startsWith("172.16.") || h.startsWith("172.17.") || h.startsWith("172.18.") ||
            h.startsWith("172.19.") || h.startsWith("172.2") || h.startsWith("172.30.") ||
            h.startsWith("172.31.")
        ) return false
        return !h.contains(":") // IPv6 literals stay as-is
    }

    /**
     * `http://…` → `https://…` when the host is upgradable, else null.
     * Non-http schemes return null (caller must not loop).
     */
    fun upgraded(url: String?): String? {
        if (url == null) return null
        val t = url.trim()
        if (!t.lowercase().startsWith("http://")) return null
        var rest = t.substring("http://".length)
        // authority = up to the first '/', '?' or '#'
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        val host = authority.substringAfterLast("@").substringBefore(":")
        if (!isUpgradableHost(host)) return null
        val tail = if (end < 0) "" else rest.substring(end)
        return "https://$authority$tail"
    }
}
