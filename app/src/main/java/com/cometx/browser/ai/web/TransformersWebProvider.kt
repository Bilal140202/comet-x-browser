package com.cometx.browser.ai.web

import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.ContextTooLargeException
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ProviderErrorKind
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TransformersWebProvider (v2.1.0) — the in-browser transformer LLM as a
 * first-class [LlmProvider]. The agent never knows which runtime serves a
 * step: this provider rides the normal router chain (failover, event log)
 * exactly like the cloud providers and the on-device llama.cpp provider.
 *
 * Execution model: a headless WebView runs the bundled Transformers.js
 * runtime (ONNX models, q4 weights, WebAssembly CPU backend). Weights are
 * fetched from the Hugging Face hub ONCE into the WebView origin's Cache
 * Storage; afterwards the model runs fully offline.
 *
 * Readiness is two-tiered (mirrors LocalLlamaProvider):
 *  - [isReady] (chain membership) is CHEAP: a web model is selected and the
 *    runtime is not in a broken state. No WebView is created for this check.
 *  - [chat] starts the runtime and loads the model on demand (fast no-op when
 *    the pipeline is already resident in the page).
 *
 * Text-only, honestly: multimodal messages are REFUSED with
 * [ProviderErrorKind.MODEL_UNAVAILABLE] so the router transparently moves on
 * to a vision-capable provider — the same contract LocalLlamaProvider keeps.
 *
 * Hard guards:
 *  - one completion at a time (the page itself is single-flight too)
 *  - output cap 64..512 tokens (agent decisions are small; protects WASM RAM)
 *  - empty output → PROVIDER_ERROR (never return "" to the engine)
 */
class TransformersWebProvider(
    private val engine: WebLlmEngine,
    /** User-selected web model, or null when none picked yet. */
    private val resolveSelected: () -> WebLlmCatalog.WebModel?,
) : LlmProvider {

    override val id = "webtransformers"
    override val displayName = "On-device (Transformers.js)"
    override val defaultBaseUrl = ""

    /** Router hook: web providers use the HF repo id as the model id. */
    override val defaultModelId: String?
        get() = resolveSelected()?.id

    private val generating = AtomicBoolean(false)

    private val _genState = MutableStateFlow(GenSnapshot())
    val genState: StateFlow<GenSnapshot> = _genState

    /** Lightweight progress mirror for UI (same shape the runtime publishes). */
    val progress: StateFlow<WebLoadProgress> get() = engine.progress

    /** Cheap readiness: model selected AND runtime not broken. */
    override fun isReady(): Boolean = resolveSelected() != null && engine.healthy

    override suspend fun chat(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double,
        maxTokens: Int,
    ): String = withContext(Dispatchers.Default) {
        if (!engine.healthy) {
            throw ProviderException(
                "In-browser AI lost its runtime — retry will restart it",
                -1, ProviderErrorKind.MODEL_UNAVAILABLE)
        }
        if (messages.any { !it.imageBase64Jpeg.isNullOrBlank() }) {
            throw ProviderException(
                "In-browser transformer models are text-only; vision needs a cloud model",
                -1, ProviderErrorKind.MODEL_UNAVAILABLE)
        }
        val selected = resolveSelected()
            ?: throw ProviderException(
                "No in-browser model selected — pick one in Settings → In-browser AI",
                -1, ProviderErrorKind.MODEL_UNAVAILABLE)

        // ---- context budget BEFORE decode (§19 integration): refuse absurd
        // inputs so the engine's compression path engages, not the renderer ----
        checkPromptBudget(messages.sumOf { it.text?.length ?: 0 }, selected)

        // ---- single-completion guard (page is single-flight too) ----
        if (!generating.compareAndSet(false, true)) {
            throw ProviderException(
                "In-browser model is already generating", -1, ProviderErrorKind.MODEL_UNAVAILABLE)
        }
        val startMs = System.currentTimeMillis()
        _genState.value = GenSnapshot(generating = true, startedAtMs = startMs)

        try {
            engine.ensureStarted()
            engine.load(selected.id, selected.dtype)

            val cap = maxTokens.coerceIn(64, 512)   // one decision JSON is small
            _genState.value = _genState.value.copy(phase = "decoding")
            val sid = lastSid.incrementAndGet()
            val text = engine.generate(
                sid = sid,
                messages = messages,
                maxTokens = cap,
                temperature = temperature,
                onStream = { chunk ->
                    val cur = _genState.value
                    _genState.value = cur.copy(
                        outTokens = cur.outTokens + 1,
                        partialText = cur.partialText + chunk,
                        lastUpdateAtMs = System.currentTimeMillis(),
                    )
                },
            )
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                throw ProviderException(
                    "In-browser model returned empty output", -1, ProviderErrorKind.PROVIDER_ERROR)
            }
            trimmed
        } catch (e: WebLlmException) {
            throw ProviderException(e.message ?: "in-browser AI failed", -1, ProviderErrorKind.PROVIDER_ERROR)
        } finally {
            val done = _genState.value
            _genState.value = done.copy(generating = false, lastUpdateAtMs = System.currentTimeMillis())
            generating.set(false)
        }
    }

    /** Cooperative cancel of the live generation (stopping criteria inside the page). */
    fun cancel() {
        (engine as? WebLlmRuntime)?.interrupt(lastSid.get())
    }

    private val lastSid = AtomicLong(0)

    /** Honest one-line snapshot for the Settings "Test" path and logs. */
    data class GenSnapshot(
        val generating: Boolean = false,
        val phase: String = "idle",     // idle | decoding
        val outTokens: Int = 0,
        val partialText: String = "",
        val startedAtMs: Long = 0L,
        val lastUpdateAtMs: Long = 0L,
    )

    /** Sanity helper reused by tests: context guard for very long inputs. */
    internal fun checkPromptBudget(promptChars: Int, model: WebLlmCatalog.WebModel) {
        // Web transformer models carry 16k–32k contexts; q4 wasm RAM is the real
        // ceiling. Refuse absurd inputs long before the renderer would OOM.
        val maxChars = 60_000
        if (promptChars > maxChars) {
            throw ContextTooLargeException(
                "${model.label} in-browser context cannot fit $promptChars chars (cap $maxChars)")
        }
    }

    init {
        Logx.d("TransformersWebProvider ready (additive provider v2.1.0)")
    }
}

/**
 * The provider id lives here AND in [com.cometx.browser.ai.SettingsRepository.WEB_PROVIDER_ID]
 * (mirroring the "local" pattern: provider hardcodes, settings declares for router wiring).
 * Contract test keeps the two in lockstep.
 */
