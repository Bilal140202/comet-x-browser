package com.cometx.browser.browse

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * AdBlocker (v2.0.0) — network-level ad and tracker blocking.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). Combines a
 * hosts-derived domain blocklist (StevenBlack unified hosts, MIT) with a set
 * of URL-pattern rules. Domain matching walks up parent domains so an entry
 * for "ads.example.com" also blocks its subdomains, and an entry for
 * "example.com" blocks its known-bad tree (hosts-file semantics).
 *
 * The CORE is pure JVM ([loadFromLines] / [shouldBlock] / [hostOf] touch no
 * Android classes) and is part of the unit-test gate. Disk/asset loading is
 * isolated in [loadFrom] overloads so tests never need Robolectric.
 */
class AdBlocker {

    private val blockedDomains = HashSet<String>(1 shl 17)
    private val allowDomains = HashSet<String>()
    private val lock = Any()

    @Volatile
    var ready = false
        private set

    /** When the current blocklist was loaded (ms epoch), for reload comparisons. */
    @Volatile
    var loadedAt = 0L
        private set

    /** All-time counter lives in settings; session counter is shared here. */
    val sessionBlocked = AtomicLong(0)

    fun isReady(): Boolean = ready

    // ------------------------------------------------------------ loading

    /**
     * Loads the blocklist from disk (updated download preferred over the
     * bundled asset) on a background thread. Safe to call repeatedly — the
     * swap is atomic, in-flight requests keep using the previous set.
     */
    fun loadAsync(appDir: File, assetLoader: () -> List<String>) {
        Thread {
            runCatching { loadFrom(appDir, assetLoader) }
        }.apply {
            name = "cometx-blocklist-load"
            priority = Thread.NORM_PRIORITY - 1
        }.start()
    }

    /** Synchronous load — updated file wins if it carries a real list. */
    fun loadFrom(appDir: File, assetLoader: () -> List<String>) {
        val set = HashSet<String>()
        var count = 0
        val updated = File(appDir, UPDATE_FILE)
        if (updated.exists() && updated.length() > 0) {
            count = runCatching {
                FileInputStream(updated).use { parse(it, set) }
            }.getOrDefault(0)
        }
        if (count < 1000) {
            count = runCatching { parseLines(assetLoader(), set) }.getOrDefault(0)
        }
        synchronized(lock) {
            blockedDomains.clear()
            blockedDomains.addAll(set)
        }
        ready = count > 0
        loadedAt = System.currentTimeMillis()
    }

    /** Visible-for-testing: parse raw hosts lines into the domain set. */
    fun loadFromLines(lines: List<String>): Int = parseLines(lines, HashSet<String>()).also { n ->
        val set = HashSet<String>()
        parseLines(lines, set)
        synchronized(lock) {
            blockedDomains.clear()
            blockedDomains.addAll(set)
        }
        ready = n > 0
        loadedAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------ allowlist

    /** Rebuilds the per-site exemption set (newline/comma separated hosts). */
    fun rebuildAllowlist(raw: String?) {
        val set = HashSet<String>()
        if (raw != null) {
            for (s in raw.split("\n", ",")) {
                val t = s.trim().lowercase()
                if (t.isNotEmpty()) set.add(t)
            }
        }
        synchronized(lock) {
            allowDomains.clear()
            allowDomains.addAll(set)
        }
    }

    // ------------------------------------------------------------ matching

    /** True if this request should be blocked. Safe to call from any thread. */
    fun shouldBlock(requestUrl: String, pageUrl: String? = null): Boolean {
        if (!ready) return false
        if (requestUrl.isEmpty()) return false
        val host = hostOf(requestUrl) ?: return false
        synchronized(lock) {
            if (allowDomains.contains(host)) return false
        }
        // Walk from the exact host up to the TLD, matching blocklist entries.
        var cur: String? = host
        while (cur != null) {
            synchronized(lock) {
                if (blockedDomains.contains(cur)) return true
            }
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
        }
        for (p in URL_PATTERNS) {
            if (requestUrl.contains(p)) return true
        }
        return false
    }

    fun sessionBlockedCount(): Long = sessionBlocked.get()

    // ------------------------------------------------------------ parsing

    private fun parse(input: InputStream, out: MutableSet<String>): Int {
        val r = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        var n = 0
        var line = r.readLine()
        while (line != null) {
            val domain = parseHostsLine(line)
            if (domain != null) {
                out.add(domain)
                n++
            }
            line = r.readLine()
        }
        r.close()
        return n
    }

    private fun parseLines(lines: List<String>, out: MutableSet<String>): Int {
        var n = 0
        for (line in lines) {
            val domain = parseHostsLine(line)
            if (domain != null) {
                out.add(domain)
                n++
            }
        }
        return n
    }

    companion object {
        const val UPDATE_FILE = "hosts_updated.txt"
        const val ASSET_FILE = "blocklists/hosts.txt"

        /**
         * URL-pattern rules layered on top of the hosts list. Hosts entries
         * block by domain; these catch ad/tracker endpoints that share domains
         * with content or are path-based. Patterns are plain substring matches
         * on the request URL — deliberately conservative to avoid breaking
         * page function (availability over purity), so no generic short
         * tokens like "/ad".
         */
        val URL_PATTERNS = arrayOf(
            // --- Google ads / measurement ---
            "/pagead/", "/pagead2/", "/adsbygoogle", "googlesyndication.com",
            "google-analytics.com", "analytics.google.com", "doubleclick.net",
            "adservice.google.", "googleadservices.com", "googletagservices.com",
            "imasdk.googleapis.com",                       // Google IMA video-ad SDK
            "googletagmanager.com/gtag/js",                // GA4 loader
            "googletagmanager.com/gtm.js",                 // GTM container
            "google.com/adsense", "/api/stats/", "play.google.com/log",
            // --- Social & analytics pixels ---
            "connect.facebook.net", "facebook.com/tr?", "analytics.tiktok.com",
            "ads-twitter.com", "ct.pinterest.com", "snap.licdn.com", "px.ads.linkedin.com",
            "bat.bing.com", "clarity.ms", "hotjar.com", "hotjar.io", "fullstory.com",
            "mouseflow.com", "cdn.segment.com", "api.amplitude.com", "cdn.mxpnl.com",
            "heapanalytics.com", "mc.yandex.ru", "an.yandex.ru", "quantserve.com",
            "scorecardresearch.com", "moatads.com", "moatpixel", "adsafeprotected.com",
            // --- Header bidding / exchange endpoints ---
            "pubmatic.com", "rubiconproject.com", "openx.net", "criteo.", "adnxs.com",
            "smartadserver.com", "media.net", "sharethrough.com", "33across.com",
            "teads.tv", "sovrn.com", "casalemedia.com", "indexww.com", "bidswitch.net",
            "adform.net", "improvedigital.com", "districtm.io", "amazon-adsystem.com",
            // --- Native / content-ad and popup networks ---
            "taboola.com", "outbrain.com", "mgid.com", "revcontent.com", "zergnet.com",
            "popads.net", "popcash.net", "propellerads", "adsterra", "adcash",
            "hilltopads", "clickadu", "exoclick", "juicyads", "trafficjunky",
            "adcolony", "applovin.com", "unityads",
            // --- YouTube internals & generic ad paths ---
            "get_midroll_info", "/ptracking?", "ad.click", "/adserver/",
            "/ads.js", "/pagead2", "/popunder", "/prebid"
        )

        private val SKIP_DOMAINS = hashSetOf(
            "localhost", "localhost.localdomain", "local", "ip6-localhost", "broadcasthost"
        )

        /**
         * One hosts line → one blocked domain (null to skip). Handles comments,
         * "0.0.0.0 domain" / "127.0.0.1 domain" and bare-domain formats.
         * Pure function — unit-test gate.
         */
        fun parseHostsLine(rawLine: String): String? {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null
            val domain: String? = if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                line.split(Regex("\\s+")).getOrNull(1)
            } else {
                line.split(Regex("\\s+")).firstOrNull()
            }
            val d = domain?.lowercase() ?: return null
            if (d in SKIP_DOMAINS) return null
            return if (d.indexOf('.') > 0) d else null
        }

        /** Lowercased hostname of [url] (pure — no android.net dependency). */
        fun hostOf(url: String?): String? {
            if (url == null) return null
            var rest = url.trim()
            val schemeEnd = rest.indexOf("://")
            if (schemeEnd >= 0) rest = rest.substring(schemeEnd + 3)
            // strip credentials
            val at = rest.indexOf('@')
            if (at >= 0 && at < rest.indexOf('/').let { if (it < 0) rest.length else it }) {
                rest = rest.substring(at + 1)
            }
            val slash = rest.indexOf('/')
            if (slash >= 0) rest = rest.substring(0, slash)
            val q = rest.indexOf('?')
            if (q >= 0) rest = rest.substring(0, q)
            val hash = rest.indexOf('#')
            if (hash >= 0) rest = rest.substring(0, hash)
            val colon = rest.indexOf(':') // port or IPv6 literal
            if (colon >= 0) {
                if (rest.contains("]:")) rest = rest.substring(0, rest.indexOf("]:") + 1)
                else if (!rest.startsWith("[")) rest = rest.substring(0, colon)
            }
            rest = rest.removePrefix("[").removeSuffix("]")
            if (rest.isEmpty() || rest.contains(' ')) return null
            return rest.lowercase()
        }
    }
}
