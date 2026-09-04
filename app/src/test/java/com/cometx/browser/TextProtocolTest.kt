package com.cometx.browser

import com.cometx.browser.ai.AgentDecision
import com.cometx.browser.ai.AgentProtocol
import com.cometx.browser.ai.Capability
import com.cometx.browser.ai.JsonFixtures
import com.cometx.browser.ai.ModelInfo
import com.cometx.browser.ai.ModelRanker
import com.cometx.browser.ai.ResponseInterpreters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — interpreter layer: every protocol must map into AgentDecision and
 * NEVER execute ambiguous output (§7/§8/§9/§39 red-team).
 */
class TextProtocolTest {

    private val json = ResponseInterpreters.JsonInterpreter()
    private val tagged = ResponseInterpreters.TaggedTextInterpreter()
    private val plain = ResponseInterpreters.PlainTextInterpreter()
    private val tool = ResponseInterpreters.ToolCallInterpreter()

    // -------------------------------------------------- JSON interpreter

    @Test fun `json parses direct object`() {
        val d = json.interpret("""{"action":"click","ref":"e3","note":"ok"}""")
        assertEquals("click", d?.action)
        assertEquals("e3", d?.target)
        assertEquals("ok", d?.reason)
    }

    @Test fun `json parses fenced and prose-wrapped output`() {
        assertNotNull(json.interpret("```json\n{\"action\":\"done\",\"summary\":\"x\"}\n```"))
        assertNotNull(json.interpret("Sure! Here is my action: {\"action\":\"scroll\",\"direction\":\"down\"} and that is all."))
    }

    @Test fun `json tolerates nested action object`() {
        val d = json.interpret("""{"action":{"action":"type","ref":"e1","text":"hi"},"note":"outer note"}""")
        assertEquals("type", d?.action)
        assertEquals("hi", d?.value)
    }

    @Test fun `json tolerates name and tool shapes`() {
        assertEquals("click", json.interpret("""{"name":"click","ref":"e2"}""")?.action)
        assertEquals("click", json.interpret("""{"tool":"click","ref":"e2"}""")?.action)
    }

    @Test fun `json rejects unknown verbs`() {
        assertNull(json.interpret("""{"action":"deploy_missiles"}"""))
        assertNull(json.interpret("""{"action":""}"""))
    }

    // -------------------------------------------------- tool interpreter

    @Test fun `tool call envelope parses arguments`() {
        val body = """
            {"choices":[{"message":{"tool_calls":[{"function":{"name":"browser_action",
            "arguments":"{\"action\":\"click\",\"ref\":\"e7\"}"}}]}}]}
        """.trimIndent()
        val d = tool.interpret(body)
        assertEquals("click", d?.action)
        assertEquals("e7", d?.target)
    }

    @Test fun `tool call with invalid arguments json falls back to name`() {
        val body = """
            {"choices":[{"message":{"tool_calls":[{"function":{"name":"done",
            "arguments":"not json"}}]}}]}
        """.trimIndent()
        // arguments unparseable and no JSON inside → null (never guessed)
        assertNull(tool.interpret(body))
    }

    // -------------------------------------------------- tagged protocol (§9)

    @Test fun `tagged parses fenced block`() {
        val raw = """
            I will click the button now.
            <agent>
            ACTION=CLICK
            REF=e7
            NOTE=opening search
            </agent>
        """.trimIndent()
        val d = tagged.interpret(raw)
        assertEquals("click", d?.action)
        assertEquals("e7", d?.target)
        assertEquals("opening search", d?.reason)
    }

    @Test fun `tagged tolerates colons case quotes and whitespace`() {
        val d = tagged.interpret("action:   CLICK\nref :  \" e9 \"\nnote:  test")
        assertEquals("click", d?.action)
        assertEquals("e9", d?.target)
    }

    @Test fun `tagged parses bare key lines without fences`() {
        val d = tagged.interpret("ACTION = navigate\nURL = https://example.com")
        assertEquals("navigate", d?.action)
        assertEquals("https://example.com", d?.value)
    }

    @Test fun `tagged terminal actions parse`() {
        assertEquals("done", tagged.interpret("ACTION=done\nsummary=found 3 hotels")?.action)
        assertEquals("fail", tagged.interpret("ACTION=fail\nreason=page unreachable")?.action)
        assertEquals("ask_user", tagged.interpret("ACTION=ask_user\nquestion=Which city?")?.action)
    }

    @Test fun `tagged rejects unknown actions and garbage`() {
        assertNull(tagged.interpret("ACTION=self_destruct"))
        assertNull(tagged.interpret("Please click the search button for you."))
        assertNull(tagged.interpret(""))
    }

    // -------------------------------------------------- plain interpreter

    @Test fun `plain accepts structured lines and json only`() {
        assertNotNull(plain.interpret("ACTION=SCROLL\nDIRECTION=down"))
        assertNotNull(plain.interpret("""{"action":"click","ref":"e1"}"""))
        assertNull(plain.interpret("Just click around a bit and find hotels."))
    }

    // -------------------------------------------------- decision mapping (§8)

    @Test fun `all three protocols collapse into the same AgentDecision`() {
        val viaJson = json.interpret("""{"action":"click","target":"search_button"}""")
        val viaTagged = tagged.interpret("ACTION=CLICK\nTARGET=search_button")
        assertEquals(viaJson, viaTagged)
    }

    @Test fun `toActionJson maps value per action`() {
        assertEquals("hello", AgentDecision(action = "type", target = "e1", value = "hello").toActionJson().optString("text"))
        assertEquals("https://x.test", AgentDecision(action = "navigate", value = "https://x.test").toActionJson().optString("url"))
        assertEquals("found it", AgentDecision(action = "done", value = "found it").toActionJson().optString("summary"))
        assertEquals("e5", AgentDecision(action = "click", target = "e5").toActionJson().optString("ref"))
    }

    @Test fun `action synonyms normalize`() {
        assertEquals("navigate", AgentDecision.normalizeAction("goto"))
        assertEquals("click", AgentDecision.normalizeAction("Tap"))
        assertEquals("type", AgentDecision.normalizeAction("FILL"))
        assertEquals("done", AgentDecision.normalizeAction("finish"))
        assertNull(AgentDecision.normalizeAction("launch_nukes"))
    }

    // -------------------------------------------------- red-team (§39)

    @Test fun `redteam malformed and partially valid outputs never execute`() {
        // JVM org.json is lenient (unquoted/single-quoted JSON parses) — that is
        // still a structured object with an action key, downstream validator gates it
        assertNotNull(json.interpret("{action: click}"))
        assertNotNull(json.interpret("{'action':'click'}"))
        // truly broken shapes must produce null, never a guessed action
        assertNull(json.interpret("{\"action\": \"click\""))      // truncated
        assertNull(json.interpret("{\"action\": }"))              // partial value
        assertNull(json.interpret("{\"unrelated\": true}"))       // no action at all
        assertNull(tool.interpret("not json at all"))
        assertNull(tagged.interpret("ACTION=;REF="))
        assertNull(tagged.interpret("no structure here whatsoever"))
    }

    @Test fun `redteam json with braces inside strings salvages correctly`() {
        val raw = """prefix {"action":"type","ref":"e1","text":"use {braces} here"} suffix"""
        val d = json.interpret(raw)
        assertEquals("type", d?.action)
        assertEquals("use {braces} here", d?.value)
    }

    @Test fun `redteam first of multiple objects wins but must contain action`() {
        assertNotNull(json.interpret("""{"unrelated":true} {"action":"done","summary":"s"}""").let { null }
            ?: JsonFixtures.firstJsonObject("""{"unrelated":true} {"action":"done","summary":"s"}"""))
        assertNull(json.interpret("""{"unrelated":true}"""))
    }

    @Test fun `redteam huge value strings are preserved for validator`() {
        val big = "x".repeat(10_000)
        val d = json.interpret("""{"action":"type","ref":"e1","text":"$big"}""")
        assertEquals(10_000, d?.value?.length)
    }

    // -------------------------------------------------- protocol ladder (§5)

    @Test fun `protocol bestFor follows the documented hierarchy`() {
        val full = setOf(Capability.CHAT, Capability.TOOL_CALLING, Capability.JSON_OBJECT, Capability.JSON_SCHEMA, Capability.VISION)
        assertEquals(AgentProtocol.JSON_SCHEMA, AgentProtocol.bestFor(full))
        assertEquals(AgentProtocol.JSON_OBJECT, AgentProtocol.bestFor(full - Capability.JSON_SCHEMA))
        assertEquals(AgentProtocol.TOOL_CALLING, AgentProtocol.bestFor(full - Capability.JSON_SCHEMA - Capability.JSON_OBJECT))
        assertEquals(AgentProtocol.TAGGED_TEXT, AgentProtocol.bestFor(setOf(Capability.CHAT)))
    }

    @Test fun `protocol downgrade ladder terminates`() {
        assertEquals(AgentProtocol.JSON_OBJECT, AgentProtocol.JSON_SCHEMA.downgrade())
        assertEquals(AgentProtocol.TOOL_CALLING, AgentProtocol.JSON_OBJECT.downgrade())
        assertEquals(AgentProtocol.TAGGED_TEXT, AgentProtocol.TOOL_CALLING.downgrade())
        assertEquals(AgentProtocol.PLAIN_TEXT, AgentProtocol.TAGGED_TEXT.downgrade())
        assertNull(AgentProtocol.PLAIN_TEXT.downgrade())
    }

    // -------------------------------------------------- ranking (§10/§11)

    @Test fun `ranker scores capabilities per spec`() {
        val rich = ModelInfo(id = "rich", provider = "p",
            capabilities = setOf(Capability.CHAT, Capability.TOOL_CALLING, Capability.JSON_SCHEMA, Capability.VISION),
            contextLength = 128_000)
        val bare = ModelInfo(id = "bare", provider = "p", capabilities = setOf(Capability.CHAT))
        assertTrue(ModelRanker.score(rich, ModelRanker.Purpose.AGENT) > ModelRanker.score(bare, ModelRanker.Purpose.AGENT))
        // §10 arithmetic sanity: tools(30) + schema(20) + vision(20) + context(10) = 80
        assertEquals(80, ModelRanker.score(rich, ModelRanker.Purpose.AGENT))
    }

    @Test fun `ranker prefers free models on openrouter`() {
        val free = ModelInfo(id = "a:free", provider = "openrouter", free = true, capabilities = setOf(Capability.CHAT, Capability.JSON_OBJECT))
        val paid = ModelInfo(id = "b", provider = "openrouter", free = false, capabilities = setOf(Capability.CHAT, Capability.JSON_OBJECT, Capability.JSON_SCHEMA))
        val ranked = ModelRanker.rank(listOf(paid, free), ModelRanker.Purpose.AGENT, freeOnly = true)
        assertEquals(listOf("a:free"), ranked.map { it.info.id })
    }

    @Test fun `ranker single model is always usable (§11)`() {
        val only = ModelInfo(id = "solo", provider = "p", capabilities = setOf(Capability.CHAT))
        val scored = ModelRanker.bestOrOnly(listOf(only), ModelRanker.Purpose.AGENT)
        assertNotNull(scored)
        assertEquals("solo", scored?.info?.id)
    }

    @Test fun `ranker excludes non-chat endpoints entirely`() {
        val whisper = ModelInfo(id = "whisper-large-v3", provider = "p", chatCapable = false, capabilities = setOf(Capability.CHAT))
        val chat = ModelInfo(id = "real-chat", provider = "p", capabilities = setOf(Capability.CHAT, Capability.JSON_OBJECT))
        assertEquals(listOf("real-chat"), ModelRanker.rank(listOf(whisper, chat), ModelRanker.Purpose.AGENT).map { it.info.id })
    }
}
