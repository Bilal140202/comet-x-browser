package com.cometx.browser.ai.local

import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ProviderErrorKind
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.ai.ContextTooLargeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Live snapshot of an in-flight (or last) on-device generation. Drives the Test-model UI. */
data class GenState(
    val generating: Boolean = false,
    val phase: Int = 0, // 0 = reading prompt, 1 = writing tokens
    val promptDone: Int = 0,
    val promptTotal: Int = 0,
    val outTokens: Int = 0,
    val partialText: String = "",
    val startedAtMs: Long = 0L,
    val lastUpdateAtMs: Long = 0L,
) {
    val elapsedMs: Long get() = if (startedAtMs == 0L) 0 else (if (generating) System.currentTimeMillis() else lastUpdateAtMs) - startedAtMs
    val tokensPerSec: Float
        get() {
            val secs = elapsedMs / 1000f
            return if (secs > 0.4f && outTokens > 0) outTokens / secs else 0f
        }
}

/**
 * llama.cpp-backed [LlmProvider]. The agent never knows which runtime serves a
 * step: this provider participates in the normal router chain (protocol ladder,
 * failover, AI event log) like any cloud provider.
 *
 * Readiness is two-tiered:
 *  - [isReady] (chain membership) is CHEAP and non-native: a selected model file
 *    exists on disk and the native runtime is available on this device.
 *  - [chat] loads the selected model ON DEMAND if it is not resident yet. In
 *    local-first mode the manager preloads at app start, so the first step pays
 *    no load cost; in fallback mode the load happens only when every cloud
 *    provider failed — RAM is not spent on a model the user may never need.
 *
 * Hard guards (the JARVIS v1.3.0 use-after-free class):
 *  - never swap/free the native model while a completion is decoding
 *  - only one completion at a time (single native context)
 *  - context budget pre-checked via token count → [ContextTooLargeException]
 *    so the engine's §19 observation-compression path works for local models
 */
class LocalLlamaProvider(
    private val engine: NativeLlama,
    /** Last user-selected model + its verified file, or null when none selected/downloaded. */
    private val resolveSelected: () -> Pair<LocalModelCatalog.CatalogModel, File>?,
    /** Synchronous, mutex-guarded load used for on-demand activation. */
    private val loadSync: (LocalModelCatalog.CatalogModel, File) -> Boolean,
    /** Context size to run at (already clamped by the manager). */
    private val contextSize: () -> Int,
) : LlmProvider {

    override val id = "local"
    override val displayName = "On-device (llama.cpp)"
    override val defaultBaseUrl = ""

    /** Router hook (v1.6.0): candidates for non-OpenAI providers use this model id. */
    override val defaultModelId: String?
        get() = resolveSelected()?.first?.id

    @Volatile private var loadedModel: LocalModelCatalog.CatalogModel? = null
    private val generating = AtomicBoolean(false)
    private val lastUsed = AtomicLong(0)

    val activeModel: LocalModelCatalog.CatalogModel? get() = loadedModel
    fun isGenerating(): Boolean = generating.get()
    fun lastUsedAt(): Long = lastUsed.get()

    private val _genState = MutableStateFlow(GenState())
    val genState: StateFlow<GenState> = _genState

    /** Cheap readiness: native runtime exists AND a selected model file is on disk. */
    override fun isReady(): Boolean {
        if (!engine.available) return false
        return resolveSelected() != null
    }

    /** Called by the manager after a successful (pre)load so chat() skips the on-demand path. */
    fun markLoaded(model: LocalModelCatalog.CatalogModel) {
        loadedModel = model
        lastUsed.set(System.currentTimeMillis())
    }

    fun markUnloaded() {
        loadedModel = null
        _genState.value = GenState()
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double,
        maxTokens: Int
    ): String = withContext(Dispatchers.Default) {
        if (!engine.available) {
            throw ProviderException("On-device AI is not available on this device", -1, ProviderErrorKind.MODEL_UNAVAILABLE)
        }

        // ---- ensure resident (on-demand load for the fallback path) ----
        val selected = resolveSelected()
            ?: throw ProviderException("No on-device model downloaded — pick one in Settings → On-device AI", -1, ProviderErrorKind.MODEL_UNAVAILABLE)
        if (!engine.isLoaded()) {
            val ok = loadSync(selected.first, selected.second)
            if (!ok || !engine.isLoaded()) {
                throw ProviderException(
                    "Could not load ${selected.first.id} (not enough free RAM or unsupported file)",
                    -1, ProviderErrorKind.MODEL_UNAVAILABLE)
            }
        }

        // ---- single-completion guard (single native context) ----
        if (!generating.compareAndSet(false, true)) {
            throw ProviderException("On-device model is already generating", -1, ProviderErrorKind.MODEL_UNAVAILABLE)
        }
        lastUsed.set(System.currentTimeMillis())
        val started = System.currentTimeMillis()
        _genState.value = GenState(generating = true, startedAtMs = started, lastUpdateAtMs = started)

        try {
            val rendered = ChatTemplateRenderer.render(selected.first.template, messages)

            // ---- context budget BEFORE decode (§19 integration) ----
            val nCtx = engine.contextSize().takeIf { it > 0 } ?: contextSize()
            val cap = maxTokens.coerceIn(64, 512)   // one decision JSON is small; protect prompt room
            val budget = nCtx - cap - 8
            val promptTokens = engine.countTokens(rendered.prompt)
            if (promptTokens > budget && budget > 16) {
                throw ContextTooLargeException(
                    "on-device context $nCtx cannot fit $promptTokens prompt tokens (+$cap output)")
            }

            val listener = GenProgressListener { phase, promptDone, promptTotal, outTokens, partial ->
                _genState.value = GenState(
                    generating = true,
                    phase = phase,
                    promptDone = promptDone,
                    promptTotal = promptTotal,
                    outTokens = outTokens,
                    partialText = partial?.let { String(it, Charsets.UTF_8) } ?: "",
                    startedAtMs = started,
                    lastUpdateAtMs = System.currentTimeMillis(),
                )
            }
            val text = engine.complete(
                rendered.prompt, cap, temperature.toFloat(), listener, rendered.stopSequences)
            if (text == null) {
                throw ProviderException(
                    "On-device generation failed or was cancelled", -1, ProviderErrorKind.PROVIDER_ERROR)
            }
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                throw ProviderException(
                    "On-device model returned empty output", -1, ProviderErrorKind.PROVIDER_ERROR)
            }
            trimmed
        } finally {
            val done = _genState.value
            _genState.value = done.copy(generating = false, lastUpdateAtMs = System.currentTimeMillis())
            generating.set(false)
            // Idle timer restarts after every generation: the watchdog must never
            // consider an in-flight session "idle" and free under a live decode.
            lastUsed.set(System.currentTimeMillis())
        }
    }

    fun cancel() = engine.cancel()

    /** Benchmark: tokens/sec over a fixed agent-style prompt. Honest value or failure. */
    suspend fun benchmark(): Result<Double> = withContext(Dispatchers.Default) {
        if (!engine.isLoaded()) return@withContext Result.failure(IllegalStateException("No model loaded"))
        val prompt = ChatTemplateRenderer.render(
            LocalModelCatalog.ChatTemplate.PLAIN,
            listOf(ChatMessage("user", "USER TASK: open example.com and extract the page title. Respond with one JSON action only."))
        ).prompt
        val start = System.currentTimeMillis()
        val res = runCatching { engine.complete(prompt, 64, 0.2f, null, emptyList()) }
        val ms = System.currentTimeMillis() - start
        if (res.getOrNull() == null) Result.failure(IllegalStateException("Benchmark generation failed"))
        else Result.success(64_000.0 / ms.coerceAtLeast(1))
    }
}
