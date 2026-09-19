package com.cometx.browser.browse

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cometx.browser.util.Logx
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * FilterUpdater (v2.0.0) — downloads and validates the two filter lists
 * (hosts blocklist and cosmetic element-hiding rules).
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). Used by the manual
 * "Update filter lists" row in Settings and by the weekly automatic refresh.
 *
 * Both files are written to app-private storage; [AdBlocker] prefers the
 * updated hosts file on load, [CosmeticFilter] prefers the updated cosmetic
 * file. A download only replaces the previous file when it passes validation
 * (minimum size + content marker), so a failed or hostile response can never
 * degrade blocking.
 */
object FilterUpdater {

    const val HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    /**
     * The curated cosmetic list lives in the Zerium repository (same owner as
     * Comet-X); the bundled snapshot keeps working whenever this fetch fails.
     */
    const val COSMETIC_URL =
        "https://raw.githubusercontent.com/ansaribilal14/zerium-browser/main/" +
            "app/src/main/assets/blocklists/cosmetic.txt"

    const val HOSTS_FILE = "hosts_updated.txt"
    const val COSMETIC_FILE = "cosmetic_updated.txt"

    /** One week of millis. */
    private const val WEEK_MS = 7L * 24L * 3600L * 1000L

    fun interface Callback {
        /** Called on the UI thread. updated = 0 (failed), 1 (hosts), 2 (cosmetic), 3 (both). */
        fun onDone(updated: Int)
    }

    /** Pure decision — unit-test gate. */
    fun dueForAutoUpdate(enabled: Boolean, lastUpdateMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        if (lastUpdateMs == 0L) return true // first run: refresh past the shipped snapshot
        return nowMs - lastUpdateMs > WEEK_MS
    }

    fun updateAll(context: Context, onDone: Callback) {
        val app = context.applicationContext
        Thread {
            val hosts = runCatching {
                download(app, HOSTS_URL, HOSTS_FILE, 100000L, "0.0.0.0")
            }.getOrDefault(false)
            val cosmetic = runCatching {
                download(app, COSMETIC_URL, COSMETIC_FILE, 2000L, "##")
            }.getOrDefault(false)
            val updated = (if (hosts) 1 else 0) + (if (cosmetic) 2 else 0)
            Handler(Looper.getMainLooper()).post { onDone.onDone(updated) }
        }.apply {
            name = "cometx-filter-update"
            isDaemon = true
        }.start()
    }

    /**
     * Downloads to a temp file and only swaps it into place when validation
     * passes, atomically via rename.
     */
    private fun download(ctx: Context, url: String, target: String, minBytes: Long, mustContain: String): Boolean {
        val out = File(ctx.filesDir, target)
        val tmp = File(ctx.filesDir, "$target.tmp")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != 200) {
                tmp.delete()
                return false
            }
            var bytes = 0L
            val head = StringBuilder()
            conn.inputStream.use { iss ->
                FileOutputStream(tmp).use { fos ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = iss.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        bytes += n
                        if (head.length < 65536) {
                            head.append(String(buf, 0, n, StandardCharsets.UTF_8))
                        }
                    }
                }
            }
            if (bytes < minBytes || !head.contains(mustContain)) {
                tmp.delete()
                return false
            }
            if (out.exists()) out.delete()
            val ok = tmp.renameTo(out)
            Logx.i("FilterUpdater: $target updated=$ok (${bytes}B)")
            return ok
        } catch (e: Exception) {
            tmp.delete()
            return false
        } finally {
            conn?.disconnect()
        }
    }
}
