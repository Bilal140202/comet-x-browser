package com.cometx.browser

import com.cometx.browser.skills.RecordedSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 — recorded skill model: serialization round-trips, unknown-verb
 * rejection (never replay a verb the player can't execute) and URL hygiene.
 */
class RecordedSkillTest {

    private fun sample(): RecordedSkill = RecordedSkill(
        id = "rec-abc123",
        name = "Daily report",
        description = "Open the dashboard and export",
        startUrl = "https://example.com/app",
        steps = listOf(
            RecordedSkill.Step(action = "navigate", url = "https://example.com/login"),
            RecordedSkill.Step(
                action = "type",
                target = RecordedSkill.Target(name = "user", tag = "input", cssPath = "form>input"),
                text = "me@example.com"
            ),
            RecordedSkill.Step(
                action = "type",
                target = RecordedSkill.Target(name = "pass", tag = "input"),
                text = "",
                sensitive = true,
                submit = true
            ),
            RecordedSkill.Step(
                action = "click",
                target = RecordedSkill.Target(text = "Sign in", ariaLabel = "Sign in", tag = "button", x = 180, y = 400)
            ),
            RecordedSkill.Step(action = "wait", ms = 2000),
            RecordedSkill.Step(action = "scroll", direction = "down", amount = 900)
        ),
        verification = "Dashboard visible",
        failureHandling = "Stop and report",
        source = RecordedSkill.SOURCE_RECORDER
    )

    @Test fun `json round-trip preserves everything`() {
        val s = sample()
        val parsed = RecordedSkill.parse(s.toJson().toString())!!
        assertEquals(s.id, parsed.id)
        assertEquals(s.name, parsed.name)
        assertEquals(s.description, parsed.description)
        assertEquals(s.startUrl, parsed.startUrl)
        assertEquals(s.steps.size, parsed.steps.size)
        assertEquals(s.verification, parsed.verification)
        assertEquals(s.source, parsed.source)
    }

    @Test fun `step fields survive the round-trip`() {
        val parsed = RecordedSkill.parse(sample().toJson().toString())!!
        val sensitive = parsed.steps[2]
        assertEquals("type", sensitive.action)
        assertTrue(sensitive.sensitive)
        assertTrue(sensitive.submit)
        assertEquals("", sensitive.text)
        assertEquals("pass", sensitive.target?.name)

        val click = parsed.steps[3]
        assertEquals("Sign in", click.target?.text)
        assertEquals("Sign in", click.target?.ariaLabel)
        assertEquals(180, click.target?.x)
        assertEquals(400, click.target?.y)
    }

    @Test fun `unknown verbs are dropped — never replayed`() {
        val json = """
        {"id":"x","name":"n","steps":[
          {"action":"click","target":{"text":"ok"}},
          {"action":"deploy_missiles"},
          {"action":"delete_everything"},
          {"action":"navigate","url":"https://ok.example"}
        ]}
        """.trimIndent()
        val parsed = RecordedSkill.parse(json)!!
        assertEquals(2, parsed.steps.size)
        assertTrue(parsed.steps.all { it.action in RecordedSkill.PLAYABLE_ACTIONS })
    }

    @Test fun `parse rejects garbage`() {
        assertNull(RecordedSkill.parse("not json at all"))
        assertNull(RecordedSkill.parse("""{"name":"no id"}"""))
        // id present but zero usable steps → constructible, but replay rejects it upstream
        val empty = RecordedSkill.fromJson(org.json.JSONObject().put("id", "x").put("name", "n"))
        assertTrue(empty != null && empty.steps.isEmpty())
    }

    @Test fun `sanitize url only accepts http(s) and caps length`() {
        assertEquals("https://a.example.com/x", RecordedSkill.sanitizeUrl("https://a.example.com/x"))
        assertEquals("http://127.0.0.1:8081/test", RecordedSkill.sanitizeUrl("http://127.0.0.1:8081/test"))
        assertEquals("", RecordedSkill.sanitizeUrl("javascript:alert(1)"))
        assertEquals("", RecordedSkill.sanitizeUrl("file:///etc/passwd"))
        assertEquals("", RecordedSkill.sanitizeUrl("intent://evil"))
        assertEquals("", RecordedSkill.sanitizeUrl("  "))
        val long = "https://x.example/" + "a".repeat(3000)
        assertEquals(2048, RecordedSkill.sanitizeUrl(long).length)
    }

    @Test fun `selector count reflects available strategies`() {
        assertEquals(0, RecordedSkill.Target(x = 100, y = 200).selectorCount())
        assertEquals(1, RecordedSkill.Target(id = "submit").selectorCount())
        assertEquals(2, RecordedSkill.Target(name = "q", ariaLabel = "search", tag = "input").selectorCount()) // tag is metadata, not a selector
    }

    @Test fun `target json round-trip`() {
        val t = RecordedSkill.Target(id = "i", name = "n", ariaLabel = "a", text = "t", placeholder = "p", tag = "input", cssPath = "a>b", x = 1, y = 2)
        val back = RecordedSkill.Target.fromJson(t.toJson())
        assertEquals(t, back)
    }

    @Test fun `summary line is human readable`() {
        val s = sample()
        assertTrue(s.summaryLine().contains("Daily report"))
        assertTrue(s.summaryLine().contains("6 step"))
    }
}
