package com.cometx.browser.perception

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    /**
     * Cancellation-safe + time-bounded (expert review P0-3): a cancelled
     * continuation is never resumed (no crash from the PixelCopy callback)
     * and a wedged renderer can stall a step for at most 8s.
     */
    suspend fun capture(webView: WebView): Bitmap? =
        kotlinx.coroutines.withTimeoutOrNull(8_000) { captureInternal(webView) }

    private suspend fun captureInternal(webView: WebView): Bitmap? = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                if (webView.width <= 0 || webView.height <= 0) { if (cont.isActive) cont.resume(null); return@post }
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
                                    if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) out else drawFallback(webView, out))
                                },
                                main
                            )
                            return@post
                        } catch (_: Exception) {
                            // fall through to draw
                        }
                    }
                }
                if (cont.isActive) cont.resume(drawFallback(webView, out))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun drawFallback(webView: WebView, out: Bitmap): Bitmap {
        val c = Canvas(out)
        webView.draw(c)
        return out
    }

    /** Downscale for upload; returns the original bitmap when it already fits. */
    fun scaleForUpload(bitmap: Bitmap, maxHeight: Int = 1400): Bitmap {
        val scale = min(MAX_WIDTH.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height).coerceAtMost(1f)
        return if (scale < 1f) {
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else bitmap
    }

    /** Downscale + JPEG-encode; returns base64 (no data: prefix). */
    @SuppressLint("WrongThread")
    fun toBase64Jpeg(bitmap: Bitmap, maxHeight: Int = 1400): String? {
        return try {
            val scaled = scaleForUpload(bitmap, maxHeight)
            encodeJpeg(scaled)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Set-of-Marks pipeline (v1.5.0): downscale FIRST, then draw numbered
     * badges on the scaled bitmap (crisp numbers under JPEG compression),
     * then encode. @return base64 to badge-count, or null on failure.
     */
    @SuppressLint("WrongThread")
    fun toAnnotatedBase64Jpeg(
        bitmap: Bitmap,
        elements: List<PageObservation.Element>,
        viewportW: Int,
        viewportH: Int,
        maxHeight: Int = 1400
    ): Pair<String, Int>? {
        return try {
            val scaled = scaleForUpload(bitmap, maxHeight)
            val layout = SomLayout.layout(elements, viewportW, viewportH, scaled.width, scaled.height)
            val annotated = annotate(scaled, layout)
            encodeJpeg(annotated)?.let { it to layout.marks.size }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Draws SomLayout marks onto a mutable copy of the bitmap. Purely visual:
     * the page DOM is never touched (the extractor's read-only invariant
     * holds — badges live only in the screenshot bitmap).
     */
    private fun annotate(bitmap: Bitmap, layout: SomLayout.Layout): Bitmap {
        if (layout.marks.isEmpty()) return bitmap
        val out = if (bitmap.isMutable) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val c = Canvas(out)
        val r = layout.marks.first().badgeR
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (r * 0.16f).coerceAtLeast(1.5f)
            color = MARK_COLOR
            alpha = 210
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MARK_COLOR
            setShadowLayer(r * 0.22f, 0f, 0f, Color.argb(140, 0, 0, 0))
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = r * 1.15f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        for (m in layout.marks) {
            c.drawRect(m.outlineLeft, m.outlineTop, m.outlineRight, m.outlineBottom, outline)
            c.drawCircle(m.badgeCx, m.badgeCy, m.badgeR, fill)
            val ty = m.badgeCy - (text.ascent() + text.descent()) / 2f
            c.drawText(m.label, m.badgeCx, ty, text)
        }
        return out
    }

    private fun encodeJpeg(scaled: Bitmap): String? {
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** Badge fill/outline: violet-600 — white-on-violet contrast ≈ 4.6:1 on any page. */
    private const val MARK_COLOR = 0xFF7C3AED.toInt()
}
