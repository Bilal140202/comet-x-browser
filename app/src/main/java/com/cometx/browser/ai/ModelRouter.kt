package com.cometx.browser.ai

import com.cometx.browser.util.Logx
import org.json.JSONObject

/**
 * ModelRouter — Phase 2 capability negotiation + recovery ladder.
 *
 * Normal users never pick a model (§12/§22): AUTO mode discovers the provider's
 * live catalog, ranks candidates for agent suitability (§10), negotiates the
 * best output protocol (§5) and recovers from every failure class (§16–§20):
 *
 *   UNSUPPORTED_RESPONSE_FORMAT  → silently downgrade protocol, same model
 *   MODEL_NOT_FOUND / UNAVAILABLE→ refresh catalog, rank replacement, retry
 *   RATE_LIMIT                   → next candidate, then next provider, then backoff
 *   CONTEXT_TOO_LARGE            → surfaced to engine for observation compression
 *   INVALID_API_KEY              → next provider
 *   unparseable output           → one repair round, then downgrade protocol
 *
 * Every automatic switch is logged to the AI event log (§37) — never with secrets.
 */
class ModelRouter(
    private val settings: SettingsRepository,
    private val providers: Map<String, LlmProvider>,
    private val catalog: ModelCatalog? = null
) {

    enum class Role { AGENT, FAST, REASONING, VISION, STRONG, CHEAP }

    data class Target(
        val provider: LlmProvider,
        val model: ModelInfo,
        val protocol: AgentProtocol
    ) {
        val modelId: String get() = model.id
        val providerId: String get() = provider.id
    }

    /** Negotiated result of one agent step — engine never sees wire formats. */
    data class AgentTurn(
        val decision: AgentDecision,
        val target: Target,
        val events: List<String>
    )

    /** Request for one negotiated agent step; messages rebuilt per protocol. */
    class AgentRequest(
        val role: Role,
        val temperature: Double = 0.2,
        val maxTokens: Int = 2000,   // headroom so long `type` actions don't truncate mid-JSON (expert review P2-33)
        val messagesForProtocol: (AgentProtocol) -> List<ChatMessage>
    )

    companion object {
        private const val MAX_PROVIDER_CANDIDATES = 3   // models tried per provider per step
        private const val MAX_BACKOFF_MS = 8_000L

        /**
         * Best-known-good FALLBACK SUGGESTIONS only (§33). Never a mandatory
         * runtime dependency: used solely when no catalog and no override exist
         * (e.g. embedded/scripted providers), where the id is advisory.
         */
        fun defaultModelFor(providerId: String, role: Role): String {
            val table = when (providerId) {
                "groq" -> mapOf(
                    Role.FAST to "llama-3.1-8b-instant",
                    Role.CHEAP to "llama-3.1-8b-instant",
                    Role.REASONING to "llama-3.3-70b-versatile",
                    Role.STRONG to "llama-3.3-70b-versatile",
                    Role.AGENT to "llama-3.3-70b-versatile",
                    Role.VISION to "meta-llama/llama-4-scout-17b-16e-instruct"
                )
                "openrouter" -> mapOf(
                    Role.FAST to "meta-llama/llama-3.3-70b-instruct:free",
                    Role.CHEAP to "meta-llama/llama-3.3-70b-instruct:free",
                    Role.REASONING to "meta-llama/llama-3.3-70b-instruct:free",
                    Role.STRONG to "meta-llama/llama-3.3-70b-instruct:free",
                    Role.AGENT to "meta-llama/llama-3.3-70b-instruct:free",
                    Role.VISION to "meta-llama/llama-4-scout:free"
                )
                "huggingface" -> mapOf(
                    Role.FAST to "meta-llama/Llama-3.1-8B-Instruct",
                    Role.CHEAP to "meta-llama/Llama-3.1-8B-Instruct",
                    Role.REASONING to "meta-llama/Llama-3.3-70B-Instruct",
                    Role.STRONG to "meta-llama/Llama-3.3-70B-Instruct",
                    Role.AGENT to "meta-llama/Llama-3.3-70B-Instruct",
                    Role.VISION to "Qwen/Qwen2.5-VL-7B-Instruct"
                )
                else -> mapOf(
                    Role.FAST to "gpt-4o-mini",
                    Role.CHEAP to "gpt-4o-mini",
                    Role.REASONING to "gpt-4o",
                    Role.STRONG to "gpt-4o",
                    Role.AGENT to "gpt-4o",
                    Role.VISION to "gpt-4o-mini"
                )
            }
            return table[role] ?: table[Role.AGENT]!!
        }

        fun purposeOf(role: Role): ModelRanker.Purpose = when (role) {
            Role.FAST, Role.CHEAP -> ModelRanker.Purpose.FAST
            Role.REASONING -> ModelRanker.Purpose.REASONING
            Role.VISION -> ModelRanker.Purpose.VISION
            Role.AGENT, Role.STRONG -> ModelRanker.Purpose.AGENT
        }
    }

    // ------------------------------------------------------------------ chain

    /** The live fallback chain: enabled + configured providers in user-defined order. */
    fun chain(): List<LlmProvider> {
        val byId = providers.values.associateBy { it.id }
        val configured = settings.liveChain().mapNotNull { byId[it] }.filter { it.isReady() }
        if (configured.isNotEmpty()) return configured
        return providers.values.filter { it.isReady() && it.id !in SettingsRepository.ALL_PROVIDERS }
    }

    // -------------------------------------------------------------- resolution

    /**
     * Resolve candidates for one provider, honoring AUTO (ranked discovery) or
     * MANUAL (advanced override, §13). Returns ranked [Scored] candidate list.
     */
    private suspend fun candidatesFor(
        p: LlmProvider,
        role: Role,
        events: MutableList<String>
    ): List<ModelRanker.Scored> {
        val purpose = purposeOf(role)

        // ---- Advanced MANUAL override (only when the user chose it) ----
        val override = if (p.id in SettingsRepository.ALL_PROVIDERS) {
            if (settings.modelMode(p.id) == SettingsRepository.ModelMode.MANUAL)
                settings.modelFor(p.id, role) ?: settings.modelFor(p.id, Role.AGENT)
            else null
        } else null

        if (override != null) {
            events.add("${p.displayName}: advanced override model '$override'")
            val cat = catalog?.models(p as? OpenAICompatibleProvider ?: return manualCandidate(p, role, override))
            val info = cat?.firstOrNull { it.id == override }
                ?: ModelInfo(
                    id = override, provider = p.id, displayName = override,
                    capabilities = setOf(Capability.CHAT, Capability.JSON_OBJECT, Capability.STREAMING),
                    chatCapable = true
                )
            return listOf(ModelRanker.Scored(info, 0, AgentProtocol.bestFor(info.capabilities)))
        }

        // ---- AUTO: discovery + ranking ----
        val oai = p as? OpenAICompatibleProvider
            ?: return manualCandidate(p, role, defaultModelFor(p.id, role))  // embedded/scripted provider
        val cat = catalog ?: return manualCandidate(p, role, defaultModelFor(p.id, role))
        val models = cat.models(oai) ?: run {
            events.add("${p.displayName}: model discovery failed")
            return emptyList()
        }
        if (models.isEmpty()) {
            events.add("${p.displayName}: model catalog empty")
            return emptyList()
        }

        val ranked = ModelRanker.rank(models, purpose, freeOnly = p.id == "openrouter")
        if (ranked.isEmpty() && p.id == "openrouter") {
            // free-only left nothing (provider changed pricing rules?) — degrade
            // gracefully rather than dead-ending (§38 consistency)
            val any = ModelRanker.rank(models, purpose, freeOnly = false)
            if (any.isNotEmpty()) {
                events.add("OpenRouter: no free models — falling back to paid candidates")
                return any.take(MAX_PROVIDER_CANDIDATES)
            }
        }
        if (ranked.isEmpty()) {
            events.add("${p.displayName}: no agent-compatible model in ${models.size} discovered")
            return emptyList()
        }
        return ranked.take(MAX_PROVIDER_CANDIDATES)
    }

    /** Candidate for providers without discovery (scripted/embedded runtimes). */
    private fun manualCandidate(p: LlmProvider, role: Role, modelId: String): List<ModelRanker.Scored> {
        val info = ModelInfo(
            id = modelId, provider = p.id, displayName = modelId,
            capabilities = setOf(Capability.CHAT, Capability.JSON_OBJECT, Capability.STREAMING),
            chatCapable = true
        )
        return listOf(ModelRanker.Scored(info, 0, AgentProtocol.bestFor(info.capabilities)))
    }

    /** Resolve the current best target (AUTO/MANUAL) for a role — no network calls. */
    suspend fun resolve(role: Role): Target? {
        val events = mutableListOf<String>()
        for (p in chain()) {
            val cands = candidatesFor(p, role, events)
            if (cands.isNotEmpty()) {
                val best = cands.first()
                val knownGood = catalog?.knownGoodProtocol(p.id, best.info.id)
                return Target(p, best.info, ModelRanker.protocolFor(best.info, knownGood))
            }
        }
        return null
    }

    // ------------------------------------------------------------- negotiation

    /**
     * ONE negotiated agent step across the whole fallback chain. [request]
     * rebuilds its messages per protocol so prompt instructions always match
     * the wire format. Returns the first [AgentDecision] any candidate yields.
     */
    suspend fun agentStep(request: AgentRequest): AgentTurn {
        val events = mutableListOf<String>()
        var lastError: Exception? = null
        var backoffAttempt = 0

        while (backoffAttempt <= 2) {   // §18: bounded exponential backoff when nothing else remains
            for (p in chain()) {
                var cands = candidatesFor(p, request.role, events)
                if (cands.isEmpty()) continue

                var candIdx = 0
                var protocolOverride: AgentProtocol? = null
                val failedIds = HashSet<String>()
                val retriedIds = HashSet<String>()
                while (candIdx < cands.size) {
                    val cand = cands[candIdx]
                    val knownGood = catalog?.knownGoodProtocol(p.id, cand.info.id)
                    var protocol = protocolOverride ?: ModelRanker.protocolFor(cand.info, knownGood)
                    var repairUsed = false

                    var attempt = 0
                    var candidateExhausted = false
                    while (attempt < 6 && !candidateExhausted) {
                        attempt++
                        try {
                            val messages = request.messagesForProtocol(protocol)
                            val raw = callModel(p, cand.info.id, messages, request, protocol)
                            var decision = ResponseInterpreters.forProtocol(protocol).interpret(raw)

                            if (decision == null && !repairUsed && protocol != AgentProtocol.TOOL_CALLING) {
                                // one protocol-aware repair round (§5 repair, not failure)
                                repairUsed = true
                                events.add("${p.displayName}: unparseable ${protocol.label} output — requesting repair")
                                val repairMessages = messages +
                                    ChatMessage("assistant", raw.take(800)) +
                                    ChatMessage("user", AgentProtocolContract.repairInstruction(protocol))
                                val repaired = callModel(p, cand.info.id, repairMessages, request, protocol)
                                decision = ResponseInterpreters.forProtocol(protocol).interpret(repaired)
                            }

                            if (decision == null) {
                                val down = protocol.downgrade()
                                if (down != null) {
                                    events.add("${p.displayName}: ${cand.info.displayName} produced no usable ${protocol.label} — downgrading to ${down.label}")
                                    protocol = down
                                    protocolOverride = down
                                    continue
                                }
                                throw UnparseableOutputException("${p.displayName}: model output unparseable even as plain text")
                            }

                            catalog?.rememberGoodProtocol(p.id, cand.info.id, protocol)
                            return AgentTurn(decision, Target(p, cand.info, protocol), events.toList())
                        } catch (e: ContextTooLargeException) {
                            throw e   // §19: engine compresses the observation and re-issues the step
                        } catch (e: ProviderException) {
                            lastError = e
                            when (e.kind) {
                                ProviderErrorKind.UNSUPPORTED_RESPONSE_FORMAT,
                                ProviderErrorKind.UNSUPPORTED_TOOL_CALLING -> {
                                    val down = protocol.downgrade()
                                    if (down != null) {
                                        events.add("${p.displayName}: ${cand.info.displayName} rejected ${protocol.label} — switching to ${down.label}")
                                        protocol = down
                                        protocolOverride = down
                                    } else {
                                        events.add("${p.displayName}: ${cand.info.displayName} rejected every structured format — moving on")
                                        candidateExhausted = true
                                    }
                                }
                                ProviderErrorKind.MODEL_NOT_FOUND -> {
                                    events.add("${p.displayName}: model '${cand.info.id}' not found — refreshing catalog and re-ranking")
                                    catalog?.invalidate(p.id)
                                    failedIds.add(cand.info.id)
                                    val refreshed = try { candidatesFor(p, request.role, events) } catch (_: Exception) { emptyList() }
                                    val replacement = refreshed.firstOrNull { it.info.id !in failedIds }
                                        // last resort: the SAME model once more (transient 404s happen) — never twice
                                        ?: if (cand.info.id in retriedIds) null else refreshed.firstOrNull()
                                    if (replacement != null) {
                                        if (replacement.info.id == cand.info.id) retriedIds.add(replacement.info.id)
                                        cands = listOf(replacement)
                                        events.add("${p.displayName}: auto-switched to '${replacement.info.id}'")
                                    } else {
                                        cands = emptyList()
                                    }
                                    protocolOverride = null
                                    candIdx = -1   // restart from the head of the replacement list
                                    candidateExhausted = true
                                }
                                ProviderErrorKind.MODEL_UNAVAILABLE -> {
                                    val down = nextCandidateOrExit(p, cands, candIdx, events, e.message ?: "model unavailable")
                                    if (down) candidateExhausted = true else candIdx++
                                }
                                ProviderErrorKind.RATE_LIMIT -> {
                                    events.add("${p.displayName}: rate limited on ${cand.info.displayName} — trying next compatible fallback")
                                    candidateExhausted = true
                                }
                                ProviderErrorKind.INVALID_API_KEY -> {
                                    events.add("${p.displayName}: API key rejected — skipping provider")
                                    candIdx = cands.size
                                    candidateExhausted = true
                                }
                                ProviderErrorKind.VISION_UNSUPPORTED -> {
                                    // Phase 3 (expert review P1-14): surface to the
                                    // engine so it retries the step TEXT-ONLY instead of
                                    // burning every candidate on an image it can't read.
                                    logEvents(events)
                                    throw VisionUnsupportedException(
                                        "${p.displayName}: ${cand.info.displayName} cannot read images"
                                    )
                                }
                                ProviderErrorKind.NETWORK_ERROR,
                                ProviderErrorKind.PROVIDER_ERROR,
                                ProviderErrorKind.UNKNOWN,
                                ProviderErrorKind.CONTEXT_TOO_LARGE -> {
                                    if (attempt >= 2) {
                                        events.add("${p.displayName}: ${e.kind.name.lowercase()} on ${cand.info.displayName} — next candidate")
                                        candidateExhausted = true
                                    } else {
                                        kotlinx.coroutines.delay(150L * attempt)
                                    }
                                }
                            }
                        } catch (e: UnparseableOutputException) {
                            lastError = e
                            val down = protocol.downgrade()
                            if (down != null) {
                                events.add("${p.displayName}: downgrading to ${down.label}")
                                protocol = down
                                protocolOverride = down
                            } else {
                                candidateExhausted = true
                            }
                        }
                    }
                    candIdx++
                }
            }
            if (lastError == null ||
                lastError !is ProviderException ||
                lastError.kind != ProviderErrorKind.RATE_LIMIT) break
            // Every provider rate-limited: bounded backoff, then retry (§18)
            backoffAttempt++
            if (backoffAttempt <= 2) {
                val delayMs = (500L shl backoffAttempt).coerceAtMost(MAX_BACKOFF_MS)
                events.add("all providers rate limited — retrying in ${delayMs / 1000}s")
                logEvents(events)
                events.clear()   // already reported — don't double-log (expert review P2-21)
                kotlinx.coroutines.delay(delayMs)
            }
        }
        logEvents(events)
        throw lastError ?: ProviderException(
            "No AI provider could complete the request. Open Settings → AI Provider and test your key.",
            -1
        )
    }

    private fun nextCandidateOrExit(p: LlmProvider, cands: List<ModelRanker.Scored>, idx: Int, events: MutableList<String>, why: String): Boolean {
        events.add("${p.displayName}: $why — next candidate")
        return idx >= cands.size - 1
    }

    private fun logEvents(events: List<String>) {
        for (e in events) {
            Logx.w("router: $e")
            settings.appendAiLog(e)
        }
    }

    private suspend fun callModel(
        p: LlmProvider,
        modelId: String,
        messages: List<ChatMessage>,
        request: AgentRequest,
        protocol: AgentProtocol
    ): String = when {
        protocol == AgentProtocol.TOOL_CALLING && p is OpenAICompatibleProvider ->
            p.chatWithTools(messages, modelId, request.temperature, request.maxTokens).rawBody
        protocol == AgentProtocol.JSON_OBJECT && p is OpenAICompatibleProvider ->
            p.chat(messages, modelId, request.temperature, request.maxTokens, ResponseFormat.JsonObject)
        protocol == AgentProtocol.JSON_SCHEMA && p is OpenAICompatibleProvider ->
            p.chat(messages, modelId, request.temperature, request.maxTokens,
                ResponseFormat.JsonSchema("agent_decision", AgentProtocolContract.decisionSchema()))
        else -> p.chat(messages, modelId, request.temperature, request.maxTokens)
    }.let { body ->
        // Unwrap the chat envelope FIRST so text-protocol interpreters see the
        // model's actual message (not JSON-escaped wire bytes). Non-envelope
        // bodies pass through unchanged.
        (p as? OpenAICompatibleProvider)?.parseContent(body) ?: body
    }

    // --------------------------------------------------------- vision fallback

    /**
     * §20: when the agent model cannot see, find a SEPARATE vision-capable
     * model anywhere in the chain and describe the screenshot as text.
     * @return description, or null when no vision model exists (DOM path)
     */
    suspend fun describeScreenshot(visionB64: String): String? {
        for (p in chain()) {
            val events = mutableListOf<String>()
            val cands = try { candidatesFor(p, Role.VISION, events) } catch (_: Exception) { continue }
            for (cand in cands) {
                if (!cand.info.supports(Capability.VISION)) continue
                val oai = p as? OpenAICompatibleProvider ?: continue
                return try {
                    val answer = oai.chat(
                        listOf(
                            ChatMessage(role = "system",
                                text = "You describe screenshots for a browser agent. List visible interactive elements, their labels/positions, and any result or status text. Be terse."),
                            ChatMessage(role = "user",
                                text = "Describe this browser screenshot for the agent.",
                                imageBase64Jpeg = visionB64)
                        ),
                        cand.info.id, temperature = 0.1, maxTokens = 500
                    )
                    settings.appendAiLog("vision: screenshot described by ${p.displayName}/${cand.info.id}")
                    answer
                } catch (e: ProviderException) {
                    if (e.kind == ProviderErrorKind.VISION_UNSUPPORTED) continue
                    null
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------ legacy chat

    /**
     * Plain-text chat across the fallback chain (no protocol negotiation —
     * used by non-agent calls: /grill-me interview, skill AI assist).
     * [maxTokens] raised by callers that need long structured output.
     */
    suspend fun chatWithFallback(
        role: Role,
        messages: List<ChatMessage>,
        temperature: Double = 0.2,
        maxTokens: Int = 1024
    ): String {
        val events = mutableListOf<String>()
        var last: Exception? = null
        for (p in chain()) {
            val cands = candidatesFor(p, role, events)
            for (cand in cands.take(2)) {
                try {
                    return when (p) {
                        is OpenAICompatibleProvider -> p.parseContent(
                            p.chat(messages, cand.info.id, temperature, maxTokens)
                        ) ?: p.chat(messages, cand.info.id, temperature, maxTokens)
                        else -> p.chat(messages, cand.info.id, temperature, maxTokens)
                    }
                } catch (e: ProviderException) {
                    last = e
                    events.add("${p.displayName}: ${cand.info.id} failed (${e.kind.name.lowercase()})")
                    when (e.kind) {
                        ProviderErrorKind.INVALID_API_KEY -> break
                        ProviderErrorKind.MODEL_NOT_FOUND -> catalog?.invalidate(p.id)
                        else -> {}
                    }
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        logEvents(events)
        throw last ?: ProviderException("All providers failed", -1)
    }
}

/**
 * Wire contracts shared by the negotiation ladder: the decision JSON schema for
 * JSON_SCHEMA mode and per-protocol repair instructions.
 */
object AgentProtocolContract {

    fun decisionSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string")
                .put("description", "one of: navigate, back, forward, reload, click, click_at, type, press_key, scroll, select, wait, find_text, find_element, extract, screenshot, request_vision, open_tab, close_tab, switch_tab, download, copy, paste, zoom, remember, done, fail, ask_user"))
            .put("ref", JSONObject().put("type", "string"))
            .put("text", JSONObject().put("type", "string"))
            .put("url", JSONObject().put("type", "string"))
            .put("option", JSONObject().put("type", "string"))
            .put("x", JSONObject().put("type", "integer"))
            .put("y", JSONObject().put("type", "integer"))
            .put("key", JSONObject().put("type", "string"))
            .put("direction", JSONObject().put("type", "string"))
            .put("amount", JSONObject().put("type", "integer"))
            .put("ms", JSONObject().put("type", "integer"))
            .put("what", JSONObject().put("type", "string"))
            .put("description", JSONObject().put("type", "string"))
            .put("index", JSONObject().put("type", "integer"))
            .put("level", JSONObject().put("type", "string"))
            .put("key_name", JSONObject().put("type", "string"))
            .put("fact", JSONObject().put("type", "string"))
            .put("question", JSONObject().put("type", "string"))
            .put("summary", JSONObject().put("type", "string"))
            .put("reason", JSONObject().put("type", "string"))
            .put("note", JSONObject().put("type", "string")))
        .put("required", org.json.JSONArray().put("action"))

    fun repairInstruction(protocol: AgentProtocol): String = when (protocol) {
        AgentProtocol.JSON_SCHEMA, AgentProtocol.JSON_OBJECT ->
            "Your previous response was not a single valid JSON object per the schema. Respond with ONLY the JSON object — no markdown, no prose."
        AgentProtocol.TOOL_CALLING ->
            "Call the browser_action tool exactly once with a valid action."
        AgentProtocol.TAGGED_TEXT ->
            "Your previous response was unusable. Respond with ONLY the block:\n<agent>\nACTION=CLICK\nREF=e7\nNOTE=why\n</agent>\nNo prose, no markdown."
        AgentProtocol.PLAIN_TEXT ->
            "Respond with lines like:\nACTION=CLICK\nREF=e7\nUse only the actions you were given."
    }
}
