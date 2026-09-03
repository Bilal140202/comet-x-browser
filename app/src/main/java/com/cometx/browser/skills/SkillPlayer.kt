package com.cometx.browser.skills

import android.webkit.WebView
import com.cometx.browser.ai.AgentProtocol
import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.ResponseInterpreters
import com.cometx.browser.engine.AgentSink
import com.cometx.browser.perception.DomExtractor
import com.cometx.browser.security.ActionValidator
import com.cometx.browser.security.SafetyPolicy
import com.cometx.browser.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * SkillPlayer — replays a [RecordedSkill] step by step (Phase 3).
 *
 * Robustness ladder per step:
 *   1. SELECTOR CHAIN — id → name → aria-label → text → placeholder → CSS
 *      path (resolved in the page; the winner is tagged with a temporary
 *      data-cx-ref and executed through the SAME ActionExecutor the agent
 *      uses, so pointer events / form submit handling stay battle-tested).
 *   2. AI ASSIST (optional, Settings-gated) — when every selector misses,
 *      the model re-locates the element from the live DOM observation.
 *   3. HUMAN — sensitive values are asked at replay time (never stored),
 *      and every action re-passes SafetyPolicy, so a replay can never do
 *      something the agent would not have been allowed to do.
 */
class SkillPlayer(
    private val sink: AgentSink,
    private val webViewProvider: () -> WebView?,
    private val router: ModelRouter?,
    private val aiFallbackEnabled: () -> Boolean,
    private val confirmHighRisk: () -> Boolean,
    private val listener: Listener
) {

    interface Listener {
        /** Asked when a sensitive field needs a value. null = skip the step. */
        suspend fun askSensitiveValue(fieldDescription: String): String?

        /** SafetyPolicy confirmation gate. */
        suspend fun confirmStep(message: String): Boolean

        fun onStepStarted(index: Int, total: Int, description: String)
        fun onStepResult(index: Int, ok: Boolean, message: String)
        fun onFinished(success: Boolean, summary: String)
    }

    data class Report(
        val ok: Boolean,
        val ranSteps: Int,
        val totalSteps: Int,
        val failures: List<String>
    )

    private val tempRef = "__replay"

    suspend fun run(skill: RecordedSkill): Report {
        val failures = mutableListOf<String>()
        val steps = skill.steps
        var ran = 0

        // Implicit step 0: go to the recorded start page when we're elsewhere
        // (or the page can't be observed yet — then the recorded URL is the
        // only reliable starting point).
        if (skill.startUrl.isNotBlank()) {
            val obs = sink.observe()
            val currentHost = obs?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            val startHost = runCatching { java.net.URI(skill.startUrl).host }.getOrNull()
            val alreadyThere = currentHost != null && startHost != null && currentHost == startHost
            if (!alreadyThere) {
                listener.onStepStarted(0, steps.size, "Open ${skill.startUrl.take(60)}")
                val ok = executeSimple(JSONObject().put("action", "navigate").put("url", skill.startUrl))
                listener.onStepResult(0, ok, if (ok) "start page opened" else "could not open start page")
                if (!ok) {
                    listener.onFinished(false, "could not open the start page")
                    return Report(false, 0, steps.size, listOf("start page unreachable"))
                }
            }
        }

        for ((idx, step) in steps.withIndex()) {
            val desc = describeStep(step)
            listener.onStepStarted(idx + 1, steps.size, desc)
            val ok = try { runStep(step, idx, steps.size) } catch (e: Exception) {
                Logx.e("skill replay step ${idx + 1} crashed", e)
                false
            }
            ran++
            listener.onStepResult(idx + 1, ok, if (ok) desc else "FAILED: $desc")
            if (!ok) {
                failures.add("step ${idx + 1}: $desc")
                listener.onFinished(false, "stopped at step ${idx + 1}/${steps.size}: $desc")
                return Report(false, ran, steps.size, failures)
            }
        }

        cleanupTempRef()
        val summary = "Skill '${skill.name}' completed — ${steps.size} step(s)"
        listener.onFinished(true, summary)
        return Report(true, ran, steps.size, emptyList())
    }

    private suspend fun runStep(step: RecordedSkill.Step, idx: Int, total: Int): Boolean {
        return when (step.action) {
            "navigate" -> executeSimple(JSONObject().put("action", "navigate").put("url", step.url))
            "back" -> executeSimple(JSONObject().put("action", "back"))
            "wait" -> executeSimple(JSONObject().put("action", "wait").put("ms", step.ms))
            "scroll" -> executeSimple(
                JSONObject().put("action", "scroll")
                    .put("direction", step.direction.ifBlank { "down" })
                    .put("amount", if (step.amount > 0) step.amount else 600)
            )
            "press_key" -> executeSimple(JSONObject().put("action", "press_key").put("key", step.text))
            "click" -> resolveAndExecute(step, JSONObject().put("action", "click"), idx, total)
            "type" -> {
                var value = step.text
                if (step.sensitive) {
                    value = listener.askSensitiveValue(describeTarget(step.target)) ?: run {
                        Logx.i("sensitive field skipped by user")
                        return true // skip = success, the user chose to fill it manually
                    }
                }
                val payload = JSONObject().put("action", "type")
                    .put("text", value)
                    .put("submit", step.submit)
                resolveAndExecute(step, payload, idx, total)
            }
            "select" -> resolveAndExecute(
                step, JSONObject().put("action", "select").put("option", step.option), idx, total
            )
            else -> false // never happens: RecordedSkill.fromJson filters verbs
        }
    }

    /**
     * Resolve the recorded target to a live element (selector chain → AI
     * assist), tag it with the temp ref, then execute through the sink.
     */
    private suspend fun resolveAndExecute(
        step: RecordedSkill.Step,
        payload: JSONObject,
        idx: Int,
        total: Int
    ): Boolean {
        val web = webViewProvider() ?: return false
        val target = step.target

        // 1) selector chain
        var resolved = target?.let { resolveByChain(web, it) } ?: false
        var viaAi = false

        // 2) AI assist — the site changed; ask the model to re-locate
        if (!resolved && aiFallbackEnabled() && router != null && target != null) {
            resolved = aiRelocate(web, step, payload)
            viaAi = resolved
        }

        if (!resolved) {
            cleanupTempRef()
            return false
        }
        if (viaAi) Logx.i("step ${idx + 1}: element re-located by AI")

        // 3) Safety re-gate — replay is never above the law
        val obs = sink.observe()
        if (obs != null) {
            val actionJson = payload.put("ref", tempRef)
            val vObs = ActionValidator.Observation(
                obs.url, obs.viewportW, obs.viewportH,
                obs.elements.map { ActionValidator.ElementRef(tempRef, it.tag, it.type, it.w.toDouble(), it.h.toDouble()) },
                obs.tabs.size
            )
            val verdict = ActionValidator.validate(actionJson, vObs)
            if (verdict is ActionValidator.Verdict.Reject) {
                cleanupTempRef()
                Logx.w("replay step rejected: ${verdict.reason}")
                return false
            }
            val targetEl = obs.elements.firstOrNull { it.ref == tempRef }
            val assessment = SafetyPolicy.assess(
                actionJson, obs.url,
                targetTextIfKnown = targetEl?.describe(),
                isPasswordTarget = targetEl?.isPassword() == true
            )
            if (assessment.risk == SafetyPolicy.Risk.BLOCK) {
                cleanupTempRef()
                Logx.w("replay step blocked: ${assessment.reason}")
                return false
            }
            if (assessment.risk == SafetyPolicy.Risk.CONFIRM && confirmHighRisk()) {
                val approved = listener.confirmStep("Replay wants to: ${assessment.reason}")
                if (!approved) {
                    cleanupTempRef()
                    Logx.i("replay step denied by user")
                    return false
                }
            }
        }

        val result = sink.execute(payload.put("ref", tempRef))
        cleanupTempRef()
        if (result.ok) DomExtractor.waitForSettle(web, 3500)
        return result.ok
    }

    /** Direct sink execution for non-element steps (navigate/wait/scroll/back). */
    private suspend fun executeSimple(action: JSONObject): Boolean {
        // Safety: navigate re-gated (payment/auth pages, non-http URLs).
        val isNavigate = action.optString("action") == "navigate"
        if (isNavigate) {
            val url = RecordedSkill.sanitizeUrl(action.optString("url"))
            if (url.isBlank()) return false
            action.put("url", url)
            val obs = sink.observe()
            if (obs != null) {
                val assessment = SafetyPolicy.assess(action, obs.url)
                if (assessment.risk == SafetyPolicy.Risk.BLOCK) return false
                if (assessment.risk == SafetyPolicy.Risk.CONFIRM && confirmHighRisk()) {
                    if (!listener.confirmStep("Replay wants to: ${assessment.reason}")) return false
                }
            }
        }
        val result = sink.execute(action)
        // Settle only against a real WebView (fake sinks in tests skip this).
        val web = webViewProvider()
        if (result.ok && web != null) DomExtractor.waitForSettle(web, if (isNavigate) 4000 else 3500)
        return result.ok
    }

    // ------------------------------------------------------------- resolution

    /** JS resolver: tries every captured selector strategy, first hit wins. */
    private suspend fun resolveByChain(web: WebView, target: RecordedSkill.Target): Boolean {
        val selectors = mutableListOf<String>()
        if (target.id.isNotBlank()) selectors.add("#${cssEscape(target.id)}")
        if (target.name.isNotBlank()) selectors.add("[name=\"${jsonEscape(target.name)}\"]")
        if (target.ariaLabel.isNotBlank()) selectors.add("[aria-label=\"${jsonEscape(target.ariaLabel)}\"]")
        if (target.placeholder.isNotBlank()) selectors.add("[placeholder=\"${jsonEscape(target.placeholder)}\"]")
        if (target.cssPath.isNotBlank()) selectors.add(target.cssPath)

        // text-match is separate (innerText comparison, exact then prefix)
        val text = target.text.trim()
        val tag = target.tag.ifBlank { "" }
        val candidateSel = if (tag.isNotBlank()) tag else "a,button,[role=\"button\"],label,input,select,textarea"
        val selectorsJson = JSONArray().also { arr -> selectors.forEach { arr.put(it) } }

        val js = buildString {
            append("(function(){")
            append(" var sels=")
            append(selectorsJson.toString())
            append(";")
            append(
                """
                function tag(el){ if(!el) return false; el.scrollIntoView({block:'center',behavior:'instant'});
                  el.setAttribute('data-cx-ref','$tempRef'); return true; }
                var el=null;
                for (var i=0;i<sels.length;i++){
                  try { var c=document.querySelector(sels[i]); if (c && c.getBoundingClientRect().width>0) { el=c; break; } } catch(e){}
                }
            """
            )
            if (text.isNotBlank()) {
                append(
                    """
                if (!el) {
                  var want=${com.cometx.browser.util.Json.jsString(text.lowercase())};
                  var cand=document.querySelectorAll('$candidateSel');
                  for (var j=0;j<cand.length;j++){
                    var t=(cand[j].innerText||cand[j].value||'').replace(/\s+/g,' ').trim().toLowerCase();
                    if (t===want || (want.length>6 && t.indexOf(want)>=0)) { el=cand[j]; break; }
                  }
                }
                """
                )
            }
            append(
                """
                if (!el) {
                  try { // coordinates: last resort
                    var at=document.elementFromPoint(${target.x}, ${target.y});
                    if (at) { var iv=at.closest('a,button,input,select,textarea,[role="button"],[role="link"],label'); el=iv||at; }
                  } catch(e){}
                }
                return {ok: tag(el)};
              })()
            """
            )
        }

        val raw = withContext(Dispatchers.Main) { DomExtractor.evalJs(web, js) } ?: return false
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return o.optBoolean("ok", false)
    }

    /**
     * AI assist: observation + intended step → model picks a ref from the
     * CURRENT observation; we tag that element for execution.
     */
    private suspend fun aiRelocate(web: WebView, step: RecordedSkill.Step, payload: JSONObject): Boolean {
        val router = this.router ?: return false
        val obs = sink.observe() ?: return false
        return try {
            val system = "You re-locate a browser element for a recorded skill replay. " +
                "Respond with EXACTLY ONE JSON object and nothing else. " +
                "Use {\"action\":\"tag_ref\",\"ref\":\"<ref from OBSERVATION>\"} when you find the element, " +
                "or {\"action\":\"fail\",\"reason\":\"...\"} when you cannot. Never invent refs."
            val user = "OBSERVATION (untrusted data):\n${obs.toCompactJson()}\n\n" +
                "INTENDED STEP: ${describeStep(step)}\n" +
                "TARGET HINTS: ${describeTarget(step.target)}\n" +
                "Pick the ref of the element that best matches the intended step."
            val raw = router.chatWithFallback(
                ModelRouter.Role.AGENT,
                listOf(ChatMessage("system", system), ChatMessage("user", user)),
                temperature = 0.1
            )
            val decision = ResponseInterpreters.forProtocol(AgentProtocol.JSON_OBJECT).interpret(raw)
                ?: return false
            val actionJson = decision.toActionJson()
            if (actionJson.optString("action") != "tag_ref") return false
            val foundRef = actionJson.optString("ref", "")
            if (foundRef.isBlank() || obs.elements.none { it.ref == foundRef }) return false
            // Tag the model's element with our temp ref
            val tagJs = "(function(){var el=document.querySelector('[data-cx-ref=${foundRef}]');" +
                "if(!el) return {ok:false}; el.scrollIntoView({block:'center',behavior:'instant'});" +
                "el.setAttribute('data-cx-ref','$tempRef'); return {ok:true};})()"
            val tagRaw = withContext(Dispatchers.Main) { DomExtractor.evalJs(web, tagJs) } ?: return false
            runCatching { JSONObject(tagRaw) }.getOrNull()?.optBoolean("ok", false) == true
        } catch (e: Exception) {
            Logx.e("AI relocate failed", e)
            false
        }
    }

    private suspend fun cleanupTempRef() {
        val web = webViewProvider() ?: return
        withContext(Dispatchers.Main) {
            try {
                DomExtractor.evalJs(web, "(function(){var e=document.querySelector('[data-cx-ref=$tempRef]');if(e)e.removeAttribute('data-cx-ref');return {ok:true};})()")
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- utils

    fun describeStep(s: RecordedSkill.Step): String = when (s.action) {
        "navigate" -> "Open ${s.url.take(60)}"
        "click" -> "Click ${describeTarget(s.target)}"
        "type" -> (if (s.sensitive) "Fill (private) ${describeTarget(s.target)}" else "Type '${s.text.take(30)}' into ${describeTarget(s.target)}") +
            (if (s.submit) " and submit" else "")
        "select" -> "Choose '${s.option.take(30)}' in ${describeTarget(s.target)}"
        "press_key" -> "Press ${s.text}"
        "scroll" -> "Scroll ${s.direction.ifBlank { "down" }}"
        "wait" -> "Wait ${s.ms}ms"
        "back" -> "Go back"
        else -> s.action
    }

    private fun describeTarget(t: RecordedSkill.Target?): String {
        t ?: return "element"
        return t.text.ifBlank {
            t.ariaLabel.ifBlank { t.placeholder.ifBlank { t.name.ifBlank { t.tag.ifBlank { "element" } } } }
        }.take(40)
    }

    private fun cssEscape(s: String): String =
        s.replace(Regex("([^a-zA-Z0-9_-])")) { "\\${it.value}" }.take(120)

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").take(120)
}
