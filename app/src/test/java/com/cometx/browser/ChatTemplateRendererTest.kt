package com.cometx.browser

import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.local.ChatTemplateRenderer
import com.cometx.browser.ai.local.LocalModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.0 on-device AI: deterministic chat-template rendering. These templates
 * must byte-match the training formats of the catalog models (same strings the
 * JARVIS reference implementation verified against llama.cpp b4458).
 */
class ChatTemplateRendererTest {

    private val system = ChatMessage("system", "You are Comet-X.")
    private val user = ChatMessage("user", "OBSERVATION: page X")
    private val assistant = ChatMessage("assistant", "{\"action\":\"click\"}")

    @Test fun `chatml wraps system user assistant in im tags`() {
        val r = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.CHATML, listOf(system, user, assistant))
        assertEquals(
            "<|im_start|>system\nYou are Comet-X.<|im_end|>\n" +
                "<|im_start|>user\nOBSERVATION: page X<|im_end|>\n" +
                "<|im_start|>assistant\n",
            r.prompt
        )
    }

    @Test fun `chatml stop sequences cut turn boundaries`() {
        val r = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.CHATML, listOf(system, user))
        assertTrue(r.stopSequences.contains("<|im_end|>"))
        assertTrue(r.stopSequences.contains("<|im_start|>"))
    }

    @Test fun `llama3 uses start and end header tokens`() {
        val r = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.LLAMA3, listOf(system, user))
        assertTrue(r.prompt.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(r.prompt.contains("You are Comet-X.<|eot_id|>"))
        assertTrue(r.prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
        assertTrue(r.stopSequences.contains("<|eot_id|>"))
    }

    @Test fun `plain template has no special tokens`() {
        val r = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.PLAIN, listOf(system, user))
        assertEquals("You are Comet-X.\n\nUSER: OBSERVATION: page X\nASSISTANT:", r.prompt)
        assertTrue(r.stopSequences.contains("\nUSER:"))
    }

    @Test fun `consecutive same-role messages merge with newline`() {
        val a = ChatMessage("user", "first half")
        val b = ChatMessage("user", "second half")
        val r = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.PLAIN, listOf(a, b))
        assertTrue(r.prompt.contains("USER: first half\nsecond half"))
    }

    @Test fun `multimodal image part is dropped but text is kept`() {
        val withImage = ChatMessage("user", "MARKS: badges attached.", imageBase64Jpeg = "c2hvcnQ=")
        val r = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.CHATML, listOf(system, withImage))
        assertTrue(r.prompt.contains("MARKS: badges attached."))
        assertFalse(r.prompt.contains("c2hvcnQ="))
        assertFalse(r.prompt.contains("data:image"))
    }

    @Test fun `last system message wins`() {
        val s2 = ChatMessage("system", "Second system.")
        val r = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.PLAIN, listOf(system, s2, user))
        assertTrue(r.prompt.contains("Second system."))
        assertFalse(r.prompt.contains("You are Comet-X."))
    }

    @Test fun `empty roles render as empty slots without crashing`() {
        val r = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.CHATML, emptyList())
        assertTrue(r.prompt.startsWith("<|im_start|>system\n<|im_end|>"))
    }
}
