package com.cometx.browser.ai.local

import com.cometx.browser.ai.ChatMessage

/**
 * Deterministic chat-template rendering for local GGUF models (no jinja).
 * Templates match each catalog model's training format exactly — the same
 * strings the JARVIS reference implementation verified against llama.cpp
 * b4458 with parse_special=true on the native side.
 *
 * This is the only place remote-style [ChatMessage] lists become a single
 * local prompt. Multimodal content is intentionally flattened: on-device
 * models in this catalog are text-only, and the engine already routes
 * vision through a separate chain member (§20).
 */
object ChatTemplateRenderer {

    data class Rendered(val prompt: String, val stopSequences: List<String>)

    /**
     * Render a message list into one prompt for [template].
     *
     * Conversation shaping:
     *  - system messages set the system slot (last one wins)
     *  - user / assistant messages alternate in their slots; consecutive
     *    same-role messages are merged with a blank line
     *  - image parts are dropped (text-only runtime); the text half of a
     *    multimodal message is kept
     */
    fun render(template: LocalModelCatalog.ChatTemplate, messages: List<ChatMessage>): Rendered {
        val system = StringBuilder()
        val user = StringBuilder()
        val assistant = StringBuilder()

        for (m in messages) {
            val text = m.text.orEmpty()
            when (m.role) {
                "system" -> system.clear().append(text)  // last system message wins (remote-chat semantics)
                "assistant" -> {
                    if (assistant.isNotEmpty()) assistant.append('\n')
                    assistant.append(text)
                }
                else -> { // "user" and anything unknown → user slot
                    if (user.isNotEmpty()) user.append('\n')
                    user.append(text)
                }
            }
        }

        val prompt = when (template) {
            LocalModelCatalog.ChatTemplate.CHATML ->
                "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
            LocalModelCatalog.ChatTemplate.LLAMA3 ->
                "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|>" +
                    "<|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|>" +
                    "<|start_header_id|>assistant<|end_header_id|>\n\n"
            LocalModelCatalog.ChatTemplate.PLAIN ->
                "$system\n\nUSER: $user\nASSISTANT:"
        }
        return Rendered(prompt, stopSequences(template))
    }

    /**
     * Template end markers passed to the native stop-sequence scanner: if a
     * small model emits the turn-end token as plain text (parse_special
     * missed it), generation is cut at the boundary instead of the model
     * burning its whole budget echoing template control tokens.
     */
    fun stopSequences(template: LocalModelCatalog.ChatTemplate): List<String> = when (template) {
        LocalModelCatalog.ChatTemplate.CHATML -> listOf("<|im_end|>", "<|im_start|>")
        LocalModelCatalog.ChatTemplate.LLAMA3 -> listOf("<|eot_id|>", "<|start_header_id|>")
        LocalModelCatalog.ChatTemplate.PLAIN -> listOf("\nUSER:")
    }
}
