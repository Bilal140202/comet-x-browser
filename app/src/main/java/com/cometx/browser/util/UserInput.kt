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

    fun resolve(raw: String): String {
        val input = raw.trim()
        return when {
            input.isEmpty() -> ""
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(" ") || !input.contains(".") ->
                "https://www.google.com/search?q=" + encodeQuery(input)
            else -> "https://$input"
        }
    }

    /** Space as %20 (URLEncoder alone would emit '+'). Pure JVM, no android.net. */
    private fun encodeQuery(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
