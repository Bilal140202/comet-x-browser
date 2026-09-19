package com.cometx.browser.ai.web

import com.cometx.browser.ai.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebLlmProtocol (v2.1.0) — the Kotlin↔JS wire contract. The JS side lives in
 * assets/webllm/runtime.js; these tests pin the shared shape so the two files
 * cannot drift silently. Parsing must be TOTAL (malformed input → null, never
 * throw) because the payload comes from a runtime page across a bridge.
 */
class WebLlmProtocolTest {

    // ------------------------------------------------------------ events

    @Test fun `boot event parses`() {
        assertEquals(WebEvent.Boot, WebLlmProtocol.parseEvent("""{"t":"boot"}"""))
    }

    @Test fun `log event parses`() {
        val ev = WebLlmProtocol.parseEvent("""{"t":"log","msg":"wasm threads: 4"}""")
        assertEquals("wasm threads: 4", (ev as WebEvent.Log).msg)
    }

    @Test fun `status event parses with full and partial payloads`() {
        val full = WebLlmProtocol.parseEvent(
            """{"t":"status","phase":"loading","model":"m","pct":42.5,"loaded":100,"total":200,"file":"model_q4.onnx"}"""
        ) as WebEvent.Status
        assertEquals("loading", full.phase)
        assertEquals("m", full.model)
        assertEquals(42.5, full.pct, 0.001)
        assertEquals(100L, full.loadedBytes)
        assertEquals(200L, full.totalBytes)
        assertEquals("model_q4.onnx", full.file)

        val bare = WebLlmProtocol.parseEvent("""{"t":"status","phase":"booting"}""") as WebEvent.Status
        assertEquals("booting", bare.phase)
        assertEquals(0.0, bare.pct, 0.001)
        assertEquals(0L, bare.loadedBytes)
        assertNull(bare.model)
    }

    @Test fun `ready stream done and error events parse`() {
        assertEquals("m", (WebLlmProtocol.parseEvent("""{"t":"ready","model":"m"}""") as WebEvent.Ready).model)

        val stream = WebLlmProtocol.parseEvent("""{"t":"stream","sid":7,"text":"He"}""") as WebEvent.Stream
        assertEquals(7L, stream.sid)
        assertEquals("He", stream.text)

        val done = WebLlmProtocol.parseEvent("""{"t":"done","sid":7,"text":"Hello"}""") as WebEvent.Done
        assertEquals(7L, done.sid)
        assertEquals("Hello", done.text)

        val withSid = WebLlmProtocol.parseEvent("""{"t":"error","sid":7,"msg":"oom"}""") as WebEvent.Failed
        assertEquals(7L, withSid.sid!!)
        assertEquals("oom", withSid.msg)

        val loadFail = WebLlmProtocol.parseEvent("""{"t":"error","msg":"network"}""") as WebEvent.Failed
        assertNull(loadFail.sid)
        assertEquals("network", loadFail.msg)
    }

    @Test fun `parsing is total - malformed never throws`() {
        assertNull(WebLlmProtocol.parseEvent(null))
        assertNull(WebLlmProtocol.parseEvent(""))
        assertNull(WebLlmProtocol.parseEvent("   "))
        assertNull(WebLlmProtocol.parseEvent("not json at all"))
        assertNull(WebLlmProtocol.parseEvent("""{"t": 12}"""))          // wrong type
        assertNull(WebLlmProtocol.parseEvent("""{"no_type":true}"""))   // unknown shape
        assertNull(WebLlmProtocol.parseEvent("""[1,2,3]"""))            // array, not object
        assertNull(WebLlmProtocol.parseEvent("""{"t":"mystery"}"""))    // unknown event type
    }

    // ------------------------------------------------------------ commands

    @Test fun `load command has the shape runtime-js expects`() {
        val cmd = JSONObject(WebLlmProtocol.loadCommand("org/model", "q4"))
        assertEquals("load", cmd.getString("op"))
        assertEquals("org/model", cmd.getString("model"))
        assertEquals("q4", cmd.getString("dtype"))
    }

    @Test fun `generate command maps chat messages to role-content pairs`() {
        val msgs = listOf(
            ChatMessage("system", "be brief"),
            ChatMessage("user", "hello"),
        )
        val cmd = JSONObject(WebLlmProtocol.generateCommand(sid = 42, messages = msgs, maxTokens = 128, temperature = 0.1))
        assertEquals("generate", cmd.getString("op"))
        assertEquals(42L, cmd.getLong("sid"))
        assertEquals(128, cmd.getInt("maxTokens"))
        assertEquals(0.1, cmd.getDouble("temperature"), 1e-9)
        val arr = cmd.getJSONArray("messages")
        assertEquals(2, arr.length())
        assertEquals("system", (arr[0] as JSONObject).getString("role"))
        assertEquals("be brief", (arr[0] as JSONObject).getString("content"))
        assertEquals("user", (arr[1] as JSONObject).getString("role"))
        assertEquals("hello", (arr[1] as JSONObject).getString("content"))
    }

    @Test fun `generate command tolerates null message text`() {
        val cmd = JSONObject(WebLlmProtocol.generateCommand(1, listOf(ChatMessage("user", null)), 64, 0.0))
        assertEquals("", (cmd.getJSONArray("messages")[0] as JSONObject).getString("content"))
    }

    @Test fun `interrupt command carries the sid`() {
        val cmd = JSONObject(WebLlmProtocol.interruptCommand(9))
        assertEquals("interrupt", cmd.getString("op"))
        assertEquals(9L, cmd.getLong("sid"))
    }

    @Test fun `commands survive evaluateJavascript embedding unchanged`() {
        // The runtime evaluates: window.__cometxAI.handle(<json>);
        // The JSON must survive that embedding: no raw newlines inside strings.
        val cmds = listOf(
            WebLlmProtocol.loadCommand("org/model", "q4"),
            WebLlmProtocol.generateCommand(1, listOf(ChatMessage("user", "line\nbreak \"quoted\"")), 64, 0.2),
            WebLlmProtocol.interruptCommand(2),
        )
        for (c in cmds) {
            // org.json escapes control chars; re-parse proves it's a clean literal
            assertTrue(JSONObject(c).length() > 0)
            assertTrue(!c.contains("\n"))
        }
    }

    @Test fun `messages array builder matches what runtime-js reads`() {
        // runtime.js: msg.messages is consumed by transformers.js pipe(messages)
        // as a chat array — each entry MUST be {role, content}.
        val arr = JSONObject(WebLlmProtocol.generateCommand(
            1, listOf(ChatMessage("user", "x")), 64, 0.0
        )).getJSONArray("messages")
        for (i in 0 until arr.length()) {
            val o = arr[i] as JSONObject
            assertEquals(setOf("role", "content"), o.keys().asSequence().toSet())
        }
    }
}
