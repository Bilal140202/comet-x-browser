package com.cometx.browser.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * ConnectionDiagnostics (Phase 2 §21/§26): the "Test & Enable" pipeline.
 *
 * CONNECTING…
 *   ✓ API authentication
 *   ✓ Model discovery
 *   ✓ Agent-compatible model
 *   ✓ Structured-output compatibility
 *   ✓ Tool-call compatibility
 *   ✓ Vision capability
 * READY
 *
 * A ⚠ on structured output / tools / vision still ends READY — the text
 * protocol and DOM perception are valid fallbacks, and the user must never be
 * blocked by an optional capability.
 */
class ConnectionDiagnostics(
    private val catalog: ModelCatalog
) {

    data class Step(val label: String, val ok: Boolean, val warn: Boolean, val detail: String)

    data class Report(
        val providerId: String,
        val providerName: String,
        val steps: List<Step>,
        val bestModel: ModelInfo?,
        val protocol: AgentProtocol,
        val freeOnly: Boolean
    ) {
        val ready: Boolean get() = steps.firstOrNull { !it.ok && !it.warn } == null && bestModel != null
        val hardFailed: Boolean get() = steps.firstOrNull { !it.ok && !it.warn } != null

        fun render(): String = buildString {
            for (s in steps) {
                appendLine(when {
                    s.ok -> "✓ ${s.label}"
                    s.warn -> "⚠ ${s.label} — ${s.detail}"
                    else -> "✗ ${s.label} — ${s.detail}"
                })
            }
            if (bestModel != null) {
                appendLine()
                appendLine("Agent model: ${bestModel.toShortLabel()}")
                appendLine("Agent protocol: ${protocol.label}")
                if (freeOnly) appendLine("Billing: free models only")
            }
            appendLine()
            append(if (ready) "READY" else "NOT READY")
        }

        fun toJson(): JSONObject = JSONObject()
            .put("providerId", providerId)
            .put("providerName", providerName)
            .put("steps", JSONArray().apply { for (s in steps) put(JSONObject().put("label", s.label).put("ok", s.ok).put("warn", s.warn).put("detail", s.detail)) })
            .put("bestModel", bestModel?.toJson() ?: JSONObject.NULL)
            .put("protocol", protocol.name)
            .put("freeOnly", freeOnly)
            .put("ts", System.currentTimeMillis())

        companion object {
            fun fromJson(o: JSONObject): Report? = runCatching {
                val stepsArr = o.optJSONArray("steps") ?: return null
                val steps = (0 until stepsArr.length()).mapNotNull { i ->
                    val s = stepsArr.optJSONObject(i) ?: return@mapNotNull null
                    Step(s.optString("label"), s.optBoolean("ok"), s.optBoolean("warn"), s.optString("detail"))
                }
                Report(
                    providerId = o.optString("providerId"),
                    providerName = o.optString("providerName"),
                    steps = steps,
                    bestModel = (o.optJSONObject("bestModel"))?.let { ModelInfo.fromJson(it) },
                    protocol = AgentProtocol.fromNameOrNull(o.optString("protocol")) ?: AgentProtocol.TAGGED_TEXT,
                    freeOnly = o.optBoolean("freeOnly", false)
                )
            }.getOrNull()
        }
    }

    /**
     * Run the full checklist. [deep] adds json_schema + tool probes (used by
     * the Agent Compatibility self-test); normal Test & Enable stays cheap.
     */
    suspend fun run(
        provider: OpenAICompatibleProvider,
        deep: Boolean = false,
        progress: (String) -> Unit = {}
    ): Report {
        val steps = mutableListOf<Step>()
        val freeOnly = provider.id == "openrouter"
        val prober = CapabilityProber(provider, catalog)

        // 1) API authentication + 2) discovery — one call proves both
        progress("Connecting to ${provider.displayName}…")
        val models: List<ModelInfo> = try {
            val m = catalog.fetch(provider)
            steps.add(Step("API authentication", true, false, "key accepted"))
            steps.add(Step("Model discovery", true, false, "${m.size} models discovered"))
            m
        } catch (e: ProviderException) {
            when (e.kind) {
                ProviderErrorKind.INVALID_API_KEY -> steps.add(Step("API authentication", false, false, "key rejected (${e.httpCode})"))
                else -> steps.add(Step("API authentication", false, false, "${e.message?.take(120) ?: "request failed"}"))
            }
            steps.add(Step("Model discovery", false, false, "not reached"))
            return Report(provider.id, provider.displayName, steps, null, AgentProtocol.TAGGED_TEXT, freeOnly)
        }

        // 3) Agent-compatible model
        progress("Ranking ${models.size} models…")
        val best = ModelRanker.best(models, ModelRanker.Purpose.AGENT, freeOnly = freeOnly)
            ?: ModelRanker.bestOrOnly(models, ModelRanker.Purpose.AGENT)
        if (best == null) {
            steps.add(Step("Agent-compatible model", false, false, "no chat-capable model found"))
            return Report(provider.id, provider.displayName, steps, null, AgentProtocol.TAGGED_TEXT, freeOnly)
        }
        steps.add(Step("Agent-compatible model", true, false, best.info.displayName))

        // 4-6) capability verification on the chosen candidate
        val info = prober.refine(best.info, deep = deep)

        val structured = info.supports(Capability.JSON_OBJECT) || info.supports(Capability.JSON_SCHEMA)
        steps.add(Step("Structured-output compatibility", structured, true,
            if (structured) if (info.supports(Capability.JSON_SCHEMA)) "JSON Schema available" else "JSON mode available"
            else "unavailable — tagged-text protocol will drive the agent"))

        val tools = info.supports(Capability.TOOL_CALLING)
        steps.add(Step("Tool-call compatibility", tools, true,
            if (tools) "supported" else "unavailable — JSON/text protocol will drive the agent"))

        val vision = info.supports(Capability.VISION)
        steps.add(Step("Vision capability", vision, true,
            if (vision) "vision model available" else "no vision — DOM/accessibility perception remains fully functional"))

        // re-rank in case probing changed the picture (chat probe failed → not chat capable)
        val finalBest = if (!info.chatCapable) {
            val alt = ModelRanker.rank(models, ModelRanker.Purpose.AGENT, freeOnly = freeOnly)
                .firstOrNull { it.info.id != info.id }?.info
            alt
        } else info
        val protocol = AgentProtocol.bestFor(
            (finalBest ?: info).capabilities + setOf(Capability.CHAT)
        )
        return Report(provider.id, provider.displayName, steps, finalBest ?: info, protocol, freeOnly)
    }

    /**
     * §26 Agent Compatibility self-test: full pipeline rehearsal on the best
     * model — discovery → selection → chat → action extraction → protocol
     * fallback → vision/error handling summary.
     */
    suspend fun compatibilitySelfTest(provider: OpenAICompatibleProvider): String {
        val sb = StringBuilder()
        sb.appendLine("COMET-X AI COMPATIBILITY")
        sb.appendLine("Provider: ${provider.displayName}")
        val report = run(provider, deep = true)
        for (s in report.steps) {
            sb.appendLine(when {
                s.ok -> "✓ ${s.label}"
                s.warn -> "⚠ ${s.label} — ${s.detail}"
                else -> "✗ ${s.label} — ${s.detail}"
            })
        }
        sb.appendLine()
        if (report.bestModel != null) {
            val m = report.bestModel
            sb.appendLine("Best model: ${m.displayName}")
            sb.appendLine("Agent protocol: ${report.protocol.label}")
            sb.appendLine("Vision: ${if (m.supports(Capability.VISION)) "Available" else "Not available (DOM fallback)"}")
            sb.appendLine("Structured output: ${if (m.supports(Capability.JSON_SCHEMA)) "JSON Schema" else if (m.supports(Capability.JSON_OBJECT)) "JSON mode" else "Text protocol"}")
            // action-extraction rehearsal with the REAL interpreter pipeline
            val interpreter = ResponseInterpreters.forProtocol(report.protocol)
            val rehearsal = interpreter.interpret("""{"action":"click","ref":"e1","note":"selftest"}""")
                ?: interpreter.interpret("ACTION=CLICK\nREF=e1")
            sb.appendLine("Action extraction: ${if (rehearsal?.action == "click") "OK" else "FAILED"}")
            val textRehearsal = TaggedTextCheck()
            sb.appendLine("Text-protocol fallback: $textRehearsal")
        }
        sb.appendLine()
        sb.appendLine("Status: ${if (report.ready) "READY" else "NOT READY"}")
        return sb.toString()
    }

    /** TAGGED_TEXT interpreter must always be executable — it is the floor of the ladder. */
    private fun TaggedTextCheck(): String = try {
        val d = ResponseInterpreters.TaggedTextInterpreter().interpret(
            "<agent>\nACTION=CLICK\nREF=e2\n</agent>"
        )
        if (d?.action == "click" && d.target == "e2") "Available" else "FAILED"
    } catch (_: Exception) { "FAILED" }
}
