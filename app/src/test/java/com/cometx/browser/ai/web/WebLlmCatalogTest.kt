package com.cometx.browser.ai.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebLlmCatalog (v2.1.0) — the in-browser transformer zoo must be honest:
 * every entry verified on the Hugging Face hub, int4 (q4) only (the dtype the
 * WebAssembly CPU backend executes reliably), sizes real, defaults sane.
 */
class WebLlmCatalogTest {

    @Test fun `catalog has exactly three models with unique ids`() {
        assertEquals(3, WebLlmCatalog.MODELS.size)
        assertEquals(3, WebLlmCatalog.MODELS.map { it.id }.toSet().size)
    }

    @Test fun `all models are int4 on the wasm cpu backend`() {
        for (m in WebLlmCatalog.MODELS) {
            assertEquals("q4", m.dtype)
            assertTrue("repo id must be HF-style: ${m.id}", m.id.contains("/") && !m.id.startsWith("/"))
            assertTrue("size must be real: ${m.id}", m.sizeBytes > 300_000_000L)
            assertTrue("blurb must exist: ${m.id}", m.blurb.isNotBlank())
        }
    }

    @Test fun `sizes match the hub-reported measurements of 2026-09-19`() {
        // 387.9 MB / 786.2 MB / 1787.6 MB (onnx/model_q4.onnx) — ±1 MB tolerance
        fun mb(m: WebLlmCatalog.WebModel) = m.sizeBytes / 1_000_000.0
        assertEquals(387.9, mb(WebLlmCatalog.SMOLLM_360M), 1.0)
        assertEquals(786.2, mb(WebLlmCatalog.QWEN_05B), 1.0)
        assertEquals(1787.6, mb(WebLlmCatalog.QWEN_15B), 1.0)
    }

    @Test fun `ram guidance scales with model size`() {
        assertTrue(WebLlmCatalog.SMOLLM_360M.minDeviceRamGb < WebLlmCatalog.QWEN_05B.minDeviceRamGb)
        assertTrue(WebLlmCatalog.QWEN_05B.minDeviceRamGb < WebLlmCatalog.QWEN_15B.minDeviceRamGb)
        assertTrue(WebLlmCatalog.QWEN_15B.minDeviceRamGb >= 8)
    }

    @Test fun `default model is the recommended Qwen 0_5B`() {
        assertEquals(WebLlmCatalog.QWEN_05B.id, WebLlmCatalog.DEFAULT_MODEL_ID)
        assertEquals(WebLlmCatalog.QWEN_05B.id, WebLlmCatalog.defaultModel().id)
    }

    @Test fun `byIdOrNull round-trips and rejects unknown or null ids`() {
        for (m in WebLlmCatalog.MODELS) assertEquals(m.id, WebLlmCatalog.byIdOrNull(m.id)?.id)
        assertNull(WebLlmCatalog.byIdOrNull("not/a-model"))
        assertNull(WebLlmCatalog.byIdOrNull(null))
        assertNotNull(WebLlmCatalog.byIdOrNull(WebLlmCatalog.DEFAULT_MODEL_ID))
    }

    @Test fun `sizeMb derived correctly`() {
        assertEquals(387L, WebLlmCatalog.SMOLLM_360M.sizeMb)
        assertFalse(WebLlmCatalog.MODELS.any { it.label.isBlank() })
    }
}
