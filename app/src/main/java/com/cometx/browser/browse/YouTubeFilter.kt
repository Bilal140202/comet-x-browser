package com.cometx.browser.browse

import android.content.Context

/**
 * YouTubeFilter (v2.0.0) — site-specific ad suppression for YouTube web
 * (youtube.com, m., music., nocookie).
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). In-stream ads share
 * delivery endpoints with the video itself (googlevideo.com/videoplayback),
 * so they cannot be separated at the network layer. The suppression script
 * (assets/yt-block.js) mirrors the client-side technique used by actively
 * maintained scriptlet-based web blockers (uBlock Origin uAssets
 * quick-fixes):
 *
 *  1. Prune-before-load ("block" behavior): adPlacements / adSlots /
 *     playerAds / adBreaks are deep-pruned from every player JSON — the
 *     initial ytInitialPlayerResponse, /youtubei/ fetch responses and
 *     /youtubei/ XHR responses — before the player parses them, so the player
 *     never schedules those ads at all.
 *  2. UI cleanup: skip buttons and overlay-close buttons are clicked the
 *     moment they appear; the anti-adblock enforcement dialog is dismissed.
 *  3. In-stream fallback: only a confirmed in-stream ad (.ad-showing /
 *     .ad-interrupting on the player root) may mute and fast-forward the
 *     shared video element, with rate/mute captured before and restored
 *     after — overlay ads and normal playback are never touched.
 *  4. CSS hiding of ad renderer elements in feeds/search/watch.
 *
 * Honest scope: this is an arms race. Effectiveness varies as YouTube
 * changes; some formats can still slip through. It is still a large
 * real-world reduction versus no suppression. (docs/CONTENT_BLOCKING scope
 * notes are carried over into the Comet-X docs.)
 */
object YouTubeFilter {

    @Volatile
    private var cached: String? = null

    /** Pure function — unit-test gate. */
    fun matches(host: String?): Boolean {
        if (host == null) return false
        return host.contains("youtube.com") || host.contains("youtube-nocookie.com")
    }

    /** Document-start origins the suppression script is registered for. */
    val ORIGINS: Set<String> = setOf(
        "https://*.youtube.com",
        "https://*.youtube-nocookie.com",
        "https://music.youtube.com"
    )

    /** Suppression script, read once from assets (single source of truth). */
    fun script(context: Context): String {
        val s = cached
        if (s != null) return s
        synchronized(YouTubeFilter::class.java) {
            if (cached == null) {
                cached = runCatching {
                    context.assets.open("yt-block.js").bufferedReader().use { it.readText() }
                }.getOrDefault("")
            }
            return cached!!
        }
    }
}
