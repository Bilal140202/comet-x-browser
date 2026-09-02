package com.cometx.browser

import com.cometx.browser.memory.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(enabled: Boolean = true) = MemoryStore(tmp.root, { enabled })

    @Test fun `remember and read back`() {
        val s = store()
        s.remember("home_city", "Ahmedabad")
        s.remember("budget", "under 5000 INR")
        val mem = s.userMemory()
        assertEquals("Ahmedabad", mem["home_city"])
        assertEquals(2, mem.size)
    }

    @Test fun `forget removes a key`() {
        val s = store()
        s.remember("k", "v")
        s.forget("k")
        assertTrue(s.userMemory().isEmpty())
    }

    @Test fun `remember persists across instances`() {
        store().remember("city", "Mumbai")
        assertEquals("Mumbai", store().userMemory()["city"])
    }

    @Test fun `recent tasks capped at 10`() {
        val s = store()
        repeat(14) { s.addRecentTask("task $it", "completed") }
        assertEquals(10, s.recentTasks().size)
        assertEquals("task 13", s.recentTasks().first().first) // newest first
    }

    @Test fun `disabled memory writes nothing`() {
        store(enabled = false).remember("k", "v")
        assertTrue(store(enabled = true).userMemory().isEmpty())
    }

    @Test fun `clearAll wipes everything`() {
        val s = store()
        s.remember("k", "v")
        s.addRecentTask("g", "completed")
        s.saveBrowserState("https://example.com", "Example")
        s.clearAll()
        assertTrue(s.userMemory().isEmpty())
        assertTrue(s.recentTasks().isEmpty())
        assertEquals(null, s.lastBrowserState())
    }
}
