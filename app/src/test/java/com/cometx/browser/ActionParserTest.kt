package com.cometx.browser

import com.cometx.browser.engine.ActionParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserTest {

    @Test fun `parses raw json`() {
        val a = ActionParser.parse("""{"action":"click","ref":"e1","note":"go"}""")
        assertNotNull(a)
        assertEquals("click", a!!.optString("action"))
        assertEquals("e1", a.optString("ref"))
    }

    @Test fun `parses fenced json`() {
        val a = ActionParser.parse("```json\n{\"action\":\"done\",\"summary\":\"all set\"}\n```")
        assertNotNull(a)
        assertEquals("done", a!!.optString("action"))
    }

    @Test fun `salvages json from prose`() {
        val a = ActionParser.parse("Sure! Here is my action:\n{\"action\":\"navigate\",\"url\":\"https://example.com\"}\nHope that helps!")
        assertNotNull(a)
        assertEquals("navigate", a!!.optString("action"))
    }

    @Test fun `unwraps nested action object`() {
        val a = ActionParser.parse("""{"action":{"action":"click","ref":"e3"},"note":"wrapped"}""")
        assertNotNull(a)
        assertEquals("click", a!!.optString("action"))
    }

    @Test fun `accepts name-style tool calls`() {
        val a = ActionParser.parse("""{"name":"scroll","direction":"down"}""")
        assertNotNull(a)
        assertEquals("scroll", a!!.optString("action"))
        assertEquals("down", a.optString("direction"))
    }

    @Test fun `returns null on garbage`() {
        assertNull(ActionParser.parse("I cannot do that."))
        assertNull(ActionParser.parse(""))
        assertNull(ActionParser.parse("{\"unclosed\": true"))
    }
}
