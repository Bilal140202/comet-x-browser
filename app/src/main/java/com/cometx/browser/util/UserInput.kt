package com.cometx.browser.util

import java.net.URLEncoder

/**
 * UserInput — Chrome-like omnibox resolution, extracted from MainActivity so
 * the "URL or search?" decision is pure Kotlin and unit-testable on the JVM.
 *
 * Rules (match the previous inline behavior byte-for-byte):
 *  - empty input        → "" (caller decides what to do; do not navigate)
 *  - http(s):// pass    → as-is
 *  - has space OR no dot→ Google search for the raw query
 *  - otherwise          → https://<input> (bare domain like example.com)
 */
object UserInput {

    private const val DEFAULT_TEMPLATE = "https://www.google.com/search?q=%s"

    fun resolve(raw: String): String = resolve(raw, null)

    /**
     * v2.0.0: optional search-engine template. `null`/blank keeps the exact
     * v1.x behavior (Google search) so every existing caller and test is
     * preserved byte-for-byte; a template from SearchEngines replaces the
     * Google query URL for search terms only (URL-looking input is unchanged).
     * `about:home` / `cometx://home` pass through for the start page.
     */
    fun resolve(raw: String, searchTemplate: String?): String {
        val input = raw.trim()
        return when {
            input.isEmpty() -> ""
            input.startsWith("http://") || input.startsWith("https://") -> input
            input == "about:home" || input == "cometx://home" -> input
            input.contains(" ") || !input.contains(".") -> {
                val template = searchTemplate?.takeIf { it.isNotBlank() && it.contains("%s") }
                    ?: DEFAULT_TEMPLATE
                template.replace("%s", encodeQuery(input))
            }
            else -> "https://$input"
        }
    }

    /** Space as %20 (URLEncoder alone would emit '+'). Pure JVM, no android.net. */
    private fun encodeQuery(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
