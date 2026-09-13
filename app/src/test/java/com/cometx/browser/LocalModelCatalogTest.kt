package com.cometx.browser

import com.cometx.browser.ai.local.LocalModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0 on-device AI: catalog integrity. The pinned SHA-256 values are the
 * supply-chain contract — every entry must be complete and well-formed so the
 * download pipeline can verify what lands on disk.
 */
class LocalModelCatalogTest {

    @Test fun `catalog is non-empty with unique ids`() {
        assertTrue(LocalModelCatalog.MODELS.size >= 5)
        assertEquals(LocalModelCatalog.MODELS.size, LocalModelCatalog.MODELS.map { it.id }.distinct().size)
    }

    @Test fun `every entry has pinned checksum and size`() {
        for (m in LocalModelCatalog.MODELS) {
            assertTrue("${m.id} sha256 malformed", m.sha256.matches(Regex("^[0-9a-fA-F]{64}$")))
            assertTrue("${m.id} size must be > 100MB", m.sizeBytes > 100L * 1024 * 1024)
            assertTrue("${m.id} ramNeeded must be positive", m.ramNeededGb > 0.0)
        }
    }

    @Test fun `download urls derive from repo and file name`() {
        for (m in LocalModelCatalog.MODELS) {
            assertEquals(
                "https://huggingface.co/${m.repo}/resolve/main/${m.fileName}",
                m.downloadUrl
            )
            assertTrue(m.fileName.endsWith(".gguf"))
        }
    }

    @Test fun `entries are ordered light to heavy for the fallback ladder`() {
        val rams = LocalModelCatalog.MODELS.map { it.ramNeededGb }
        assertEquals(rams, rams.sorted())
    }

    @Test fun `recommendFor picks the largest usable model on a strong device`() {
        val rec = LocalModelCatalog.recommendFor(totalRamGb = 8.0, cores = 8)
        assertEquals(LocalModelCatalog.DeviceClass.POWER, LocalModelCatalog.deviceClass(8.0, 8))
        // 8 GB * 0.45 = 3.6 GB ceiling → qwen25-3b (3.8) does NOT fit, llama32-3b (3.6) does
        assertEquals("llama32-3b", rec.id)
    }

    @Test fun `recommendFor stays safe on low-RAM devices`() {
        val rec = LocalModelCatalog.recommendFor(totalRamGb = 3.0, cores = 8)
        assertEquals(LocalModelCatalog.DeviceClass.BASIC, LocalModelCatalog.deviceClass(3.0, 8))
        // 3 GB * 0.45 = 1.35 GB ceiling → only sub-1.35 GB entries usable
        assertEquals("qwen25-05b", rec.id)
    }

    @Test fun `lighterThan walks down the ladder`() {
        val top = LocalModelCatalog.byId("qwen25-3b")!!
        val lighter = LocalModelCatalog.lighterThan(top)!!
        assertTrue(lighter.ramNeededGb < top.ramNeededGb)
        // smallest entry has no lighter rung
        val smallest = LocalModelCatalog.MODELS.first()
        assertEquals(null, LocalModelCatalog.lighterThan(smallest))
    }

    @Test fun `byId resolves catalog and rejects unknowns`() {
        assertEquals("llama32-1b", LocalModelCatalog.byId("llama32-1b")?.id)
        assertEquals(null, LocalModelCatalog.byId("does-not-exist"))
        assertEquals(null, LocalModelCatalog.byId(null))
    }
}
