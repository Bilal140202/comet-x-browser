package com.cometx.browser

import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.SomLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for Set-of-Marks badge layout math (v1.5.0).
 * The invariant under test: mark number N == element ref eN, positions
 * scale/clamp into the upload bitmap, degenerate elements never draw.
 */
class SomLayoutTest {

    private fun el(ref: String, x: Int, y: Int, w: Int, h: Int) = PageObservation.Element(
        ref, "button", null, null, null, null, null, "x", null, null, x, y, w, h, false, false
    )

    @Test fun `badge number equals ref index`() {
        val layout = SomLayout.layout(listOf(el("e14", 10, 10, 100, 40)), 360, 640, 360, 640)
        assertEquals(1, layout.marks.size)
        assertEquals(14, layout.marks[0].number)
        assertEquals("e14", layout.marks[0].ref)
        assertEquals("14", layout.marks[0].label)
        assertEquals(0, layout.skipped)
    }

    @Test fun `coords scale with bitmap factor`() {
        // viewport 360 → bitmap 180: uniform k = 0.5
        val layout = SomLayout.layout(listOf(el("e1", 100, 200, 60, 30)), 360, 640, 180, 320)
        val m = layout.marks[0]
        assertEquals(50f, m.outlineLeft, 0.01f)
        assertEquals(100f, m.outlineTop, 0.01f)
        assertEquals(80f, m.outlineRight, 0.01f)
        assertEquals(115f, m.outlineBottom, 0.01f)
    }

    @Test fun `badges clamp inside bitmap`() {
        // element partially off the left/top edge: badge center must stay ≥ r
        val layout = SomLayout.layout(listOf(el("e1", -30, -30, 80, 40)), 360, 640, 360, 640)
        val m = layout.marks[0]
        assertTrue("badgeCx ${m.badgeCx} < r ${m.badgeR}", m.badgeCx >= m.badgeR)
        assertTrue("badgeCy ${m.badgeCy} < r ${m.badgeR}", m.badgeCy >= m.badgeR)
        assertTrue(m.outlineLeft >= 0f && m.outlineTop >= 0f)
    }

    @Test fun `degenerate and non-ref elements are skipped`() {
        val layout = SomLayout.layout(
            listOf(el("foo", 10, 10, 50, 20), el("e2", 0, 0, 0, 0), el("e3", 10, 10, 50, 20)),
            360, 640, 360, 640
        )
        assertEquals(1, layout.marks.size)
        assertEquals(3, layout.marks[0].number)
        assertEquals(2, layout.skipped)
    }

    @Test fun `fully offscreen elements are culled`() {
        val layout = SomLayout.layout(listOf(el("e1", 10, 10_000, 50, 20)), 360, 640, 360, 640)
        assertTrue(layout.marks.isEmpty())
        assertEquals(1, layout.skipped)
    }

    @Test fun `empty inputs yield empty layout without crashing`() {
        assertEquals(0, SomLayout.layout(emptyList(), 360, 640, 360, 640).marks.size)
        assertEquals(0, SomLayout.layout(listOf(el("e1", 0, 0, 10, 10)), 0, 0, 360, 640).marks.size)
        assertEquals(0, SomLayout.layout(listOf(el("e1", 0, 0, 10, 10)), 360, 640, 0, 640).marks.size)
    }

    @Test fun `badge radius survives downscale`() {
        // 1080-wide viewport captured into 1024-wide bitmap: r must stay ≥ 8
        val layout = SomLayout.layout(listOf(el("e1", 100, 100, 200, 50)), 1080, 2340, 1024, 2220)
        val r = layout.marks[0].badgeR
        assertTrue("radius $r too small to survive JPEG", r in 8f..22f)
    }
}
