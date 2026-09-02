package com.cometx.browser.perception

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Screenshotter — captures the live browser viewport for the vision engine.
 * PixelCopy is primary (works with hardware-accelerated rendering); Canvas
 * draw is the fallback. Output is downscaled + JPEG-compressed to respect
 * provider image limits and the token budget.
 */
object Screenshotter {

    const val MAX_WIDTH = 1024
    const val JPEG_QUALITY = 72

    suspend fun capture(webView: WebView): Bitmap? = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                if (webView.width <= 0 || webView.height <= 0) { cont.resume(null); return@post }
                val out = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val window = (webView.context as? android.app.Activity)?.window
                    if (window != null) {
                        val loc = IntArray(2)
                        webView.getLocationInWindow(loc)
                        try {
                            PixelCopy.request(
                                window,
                                android.graphics.Rect(loc[0], loc[1], loc[0] + webView.width, loc[1] + webView.height),
                                out,
                                { result ->
                                    cont.resume(if (result == PixelCopy.SUCCESS) out else drawFallback(webView, out))
                                },
                                main
                            )
                            return@post
                        } catch (_: Exception) {
                            // fall through to draw
                        }
                    }
                }
                cont.resume(drawFallback(webView, out))
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
    }

    private fun drawFallback(webView: WebView, out: Bitmap): Bitmap {
        val c = Canvas(out)
        webView.draw(c)
        return out
    }

    /** Downscale + JPEG-encode; returns base64 (no data: prefix). */
    @SuppressLint("WrongThread")
    fun toBase64Jpeg(bitmap: Bitmap, maxHeight: Int = 1400): String? {
        return try {
            var w = bitmap.width
            var h = bitmap.height
            val scale = min(MAX_WIDTH.toFloat() / w, maxHeight.toFloat() / h).coerceAtMost(1f)
            if (scale < 1f) {
                w = (w * scale).toInt().coerceAtLeast(1)
                h = (h * scale).toInt().coerceAtLeast(1)
            }
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
