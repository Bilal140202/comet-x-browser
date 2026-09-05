package com.cometx.browser.perception

/**
 * SomLayout — Set-of-Marks badge layout math (v1.5.0).
 *
 * The mark space IS the ref space: element ref "e14" renders as badge "14".
 * Badge geometry is computed in SCALED-bitmap pixel space from the
 * observation's viewport-CSS-px rects, so the overlay always agrees with the
 * element list the model receives — including after §19 compression, because
 * both derive from the same PageObservation instance.
 *
 * This object is deliberately free of android.graphics types so the math is
 * plain-JVM testable; [Screenshotter.annotate] does the drawing.
 */
object SomLayout {

    /** One drawable mark: element outline + numbered badge. */
    data class Mark(
        val number: Int,          // badge number == ref index (e14 → 14)
        val ref: String,          // original ref ("e14")
        val outlineLeft: Float,
        val outlineTop: Float,
        val outlineRight: Float,
        val outlineBottom: Float,
        val badgeCx: Float,
        val badgeCy: Float,
        val badgeR: Float,
        val label: String         // number as string, ready to draw
    )

    /** Result of laying out marks for one annotated screenshot. */
    data class Layout(val marks: List<Mark>, val skipped: Int)

    /**
     * @param elements  the observation's element list (same list the model sees)
     * @param viewportW viewport width in CSS px, from the same observation
     * @param viewportH viewport height in CSS px
     * @param bitmapW   target (already downscaled) bitmap width in px
     * @param bitmapH   target (already downscaled) bitmap height in px
     */
    fun layout(
        elements: List<PageObservation.Element>,
        viewportW: Int, viewportH: Int,
        bitmapW: Int, bitmapH: Int
    ): Layout {
        if (bitmapW <= 0 || bitmapH <= 0 || viewportW <= 0 || viewportH <= 0 || elements.isEmpty()) {
            return Layout(emptyList(), elements.size)
        }
        val kx = bitmapW.toFloat() / viewportW
        val ky = bitmapH.toFloat() / viewportH
        // Badge radius scales with the bitmap so numbers survive the JPEG
        // downscale (they would blur if drawn before scaling — landmine #3).
        val r = (bitmapW / 45f).coerceIn(8f, 22f)
        val marks = ArrayList<Mark>(elements.size)
        var skipped = 0
        for (el in elements) {
            val n = el.ref.removePrefix("e").toIntOrNull()
            if (n == null || n <= 0 || el.w <= 0 || el.h <= 0) { skipped++; continue }
            val l = el.x * kx
            val t = el.y * ky
            val rt = ((el.x + el.w) * kx).coerceAtMost(bitmapW.toFloat())
            val bt = ((el.y + el.h) * ky).coerceAtMost(bitmapH.toFloat())
            // Cull marks entirely outside the bitmap (stale scroll positions).
            if (rt <= 0f || bt <= 0f || l >= bitmapW || t >= bitmapH) { skipped++; continue }
            val cx = (l + r * 0.5f).coerceIn(r, bitmapW - r)
            val cy = (t + r * 0.5f).coerceIn(r, bitmapH - r)
            marks.add(
                Mark(
                    number = n, ref = el.ref,
                    outlineLeft = l.coerceAtLeast(0f), outlineTop = t.coerceAtLeast(0f),
                    outlineRight = rt, outlineBottom = bt,
                    badgeCx = cx, badgeCy = cy, badgeR = r,
                    label = n.toString()
                )
            )
        }
        return Layout(marks, skipped)
    }
}
