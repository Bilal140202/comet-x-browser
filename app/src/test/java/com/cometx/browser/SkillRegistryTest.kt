package com.cometx.browser

import com.cometx.browser.skills.SkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryTest {

    @Test fun `defaults load without assets`() {
        val reg = SkillRegistry(null)
        assertTrue(reg.skills.size >= 8)
        assertNotNull(reg.byId("shopping"))
        assertNotNull(reg.byId("travel"))
    }

    @Test fun `matches shopping goal`() {
        val reg = SkillRegistry(null)
        val s = reg.match("find the cheapest price for wireless headphones and buy")
        assertEquals("shopping", s?.id)
    }

    @Test fun `matches travel goal`() {
        val reg = SkillRegistry(null)
        val s = reg.match("find a hotel in ahmedabad for friday night")
        assertEquals("travel", s?.id)
    }

    @Test fun `generic goal falls back to null or general`() {
        val reg = SkillRegistry(null)
        val s = reg.match("please proceed with the plan we discussed")
        assertTrue(s == null || s.id == "general-web")
    }

    @Test fun `shopping skill carries no-purchase constraint`() {
        val reg = SkillRegistry(null)
        val shopping = reg.byId("shopping")!!
        assertTrue(shopping.promptExtra.contains("NEVER", ignoreCase = true))
    }
}
