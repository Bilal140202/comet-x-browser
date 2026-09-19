package com.cometx.browser.browse

/**
 * StartPageLogic (v2.0.0) — pure tile math for the Comet-X start page.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). Kept Android-free
 * for the unit-test gate; [StartPage] renders the HTML around it.
 *
 * The shortcut grid is dynamic: user-defined tiles when configured in
 * Settings, otherwise an automatic blend of the most-visited sites from
 * history and the built-in defaults (history entries fill the front slots,
 * defaults fill the rest, deduplicated by host).
 */
object StartPageLogic {

    const val TILE_COUNT = 8

    /** One start-page shortcut tile. */
    data class Tile(val name: String, val url: String, val icon: String, val color: String)

    /** Built-in default tiles (icon glyphs are plain text, HTML-escaped later). */
    val DEFAULT_TILES: List<Tile> = listOf(
        Tile("Wikipedia", "https://en.wikipedia.org", "W", "#4355b9"),
        Tile("YouTube", "https://www.youtube.com", "\u25b6", "#c2185b"),
        Tile("GitHub", "https://github.com", "G", "#24292f"),
        Tile("Reddit", "https://www.reddit.com", "R", "#ff4500"),
        Tile("Hacker News", "https://news.ycombinator.com", "Y", "#ff6600"),
        Tile("MDN", "https://developer.mozilla.org", "M", "#3b5998"),
        Tile("Gmail", "https://mail.google.com", "@", "#ea4335"),
        Tile("X", "https://x.com", "X", "#272a30")
    )

    val TILE_COLORS = arrayOf(
        "#4355b9", "#c2185b", "#00796b", "#ef6c00", "#5e35b1",
        "#2e7d32", "#0288d1", "#6d4c41", "#455a64", "#ad1457"
    )

    /** Comet violet leads the auto-color rotation so history tiles feel on-brand. */
    const val BRAND_COLOR = "#6D28D9"

    /**
     * Resolves the tile grid: custom tiles first, then the automatic blend of
     * most-visited history sites ([topSites]) and the built-in defaults.
     *
     * @param customTiles user-defined tiles from Settings (empty = automatic)
     * @param topSites most-visited entries from history, best first
     */
    fun resolveTiles(customTiles: List<Tile>, topSites: List<Pair<String, String>>): List<Tile> {
        if (customTiles.isNotEmpty()) {
            return customTiles.take(TILE_COUNT).map {
                Tile(it.name, it.url, monogram(it.name), colorFor(it.name))
            }
        }
        return autoTiles(topSites)
    }

    /** Most-visited history sites first, defaults fill the remaining slots. */
    fun autoTiles(topSites: List<Pair<String, String>>): List<Tile> {
        val out = ArrayList<Tile>(TILE_COUNT)
        val usedHosts = HashSet<String>()
        val defaultHosts = HashSet<String>()
        for (d in DEFAULT_TILES) defaultHosts.add(hostKey(d.url))
        for ((url, title) in topSites) {
            if (out.size >= TILE_COUNT) break
            if (url.startsWith("data:") || url.startsWith("about:")) continue
            val host = AdBlocker.hostOf(url) ?: continue
            // Search-result pages make poor shortcuts.
            if (isSearchResult(url, host)) continue
            val key = hostKey(url)
            // PORT FIX (vs Zerium): check defaultHosts BEFORE marking the host
            // as used — otherwise a visited site whose host matches a default
            // tile suppresses BOTH the history tile and the default tile.
            if (defaultHosts.contains(key)) continue
            // One shortcut per site.
            if (!usedHosts.add(key)) continue
            var name = if (title.isBlank()) host else title
            if (name.length > 14) name = name.take(13) + "\u2026"
            out.add(Tile(name, url, monogram(host), colorFor(host)))
        }
        for (d in DEFAULT_TILES) {
            if (out.size >= TILE_COUNT) break
            if (usedHosts.contains(hostKey(d.url))) continue
            out.add(d)
        }
        return out
    }

    /** Search-result pages make poor shortcuts — detect the big engines. */
    fun isSearchResult(url: String, host: String): Boolean {
        val u = url.lowercase()
        return u.contains("/search?") || u.contains("search?q=") || u.contains("/results?q=") ||
            (host.contains("google.") && u.contains("/search")) ||
            (host.contains("bing.com") && u.contains("/search")) ||
            (host.contains("duckduckgo.com") && u.contains("?q=")) ||
            (host.contains("search.brave.com") && u.contains("?q="))
    }

    /** Host identity for deduplication (www. stripped). */
    fun hostKey(url: String): String {
        var h = AdBlocker.hostOf(url) ?: return url
        if (h.startsWith("www.")) h = h.substring(4)
        return h
    }

    fun monogram(seed: String?): String {
        if (seed.isNullOrEmpty()) return "?"
        return seed.trim().take(1).uppercase()
    }

    fun colorFor(seed: String?): String {
        var hash = 0
        if (seed != null) for (ch in seed) hash = hash * 31 + ch.code
        return TILE_COLORS[Math.abs(hash) % TILE_COLORS.size]
    }
}
