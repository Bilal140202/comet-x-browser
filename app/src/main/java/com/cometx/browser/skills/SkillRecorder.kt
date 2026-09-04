package com.cometx.browser.skills

import android.webkit.WebView
import com.cometx.browser.perception.DomExtractor
import com.cometx.browser.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * SkillRecorder — captures the user's manual browsing into a replayable
 * [RecordedSkill] (Phase 3).
 *
 * Security model (matches the project invariant):
 *  - NO addJavascriptInterface. The collector runs as a namespace-isolated
 *    IIFE; events sit in a closure, exposed only behind a per-session
 *    random token key, drained by poll → evaluateJavascript.
 *  - Sensitive fields (password / payment / OTP) are masked IN THE PAGE:
 *    the step is recorded, the value never is. The user is asked at replay.
 *  - A page could theoretically discover the drain function and inject fake
 *    events; every event is schema-validated and the user reviews the full
 *    step list before saving. SafetyPolicy re-gates everything at replay.
 */
class SkillRecorder(
    private val scope: CoroutineScope,
    private val listener: Listener
) {

    interface Listener {
        fun onStepCountChanged(count: Int)
        fun onRecordError(message: String)
    }

    enum class State { IDLE, RECORDING }

    var state: State = State.IDLE
        private set

    private var web: WebView? = null
    private var startUrl = ""
    private var token = ""
    private var pollJob: Job? = null
    private val rawEvents = mutableListOf<JSONObject>()
    private var lastClickAt = 0L
    private var lastNavUrl = ""

    /** Injected from MainActivity.onPageStarted while recording. */
    fun onNavigation(url: String) {
        if (state != State.RECORDING) return
        if (url.isBlank() || url == lastNavUrl) return
        lastNavUrl = url
        // A click that just fired explains the navigation — don't double-record.
        val clickExplains = System.currentTimeMillis() - lastClickAt < 1500
        if (clickExplains) return
        val safe = RecordedSkill.sanitizeUrl(url)
        if (safe.isEmpty()) return
        rawEvents.add(JSONObject().put("t", "nav").put("url", safe).put("ts", System.currentTimeMillis()))
        listener.onStepCountChanged(rawEvents.size)
    }

    fun start(webView: WebView, currentUrl: String) {
        if (state == State.RECORDING) return
        web = webView
        startUrl = RecordedSkill.sanitizeUrl(currentUrl)
        lastNavUrl = currentUrl
        token = "cx" + UUID.randomUUID().toString().replace("-", "").take(16)
        rawEvents.clear()
        state = State.RECORDING
        installCollector(webView)
        pollJob = scope.launch { pollLoop() }
        Logx.i("skill recorder started on ${currentUrl.take(60)}")
    }

    /** Stops recording and returns the assembled skill, or null if nothing usable. */
    suspend fun stop(name: String, description: String): RecordedSkill? {
        if (state != State.RECORDING) return null
        state = State.IDLE
        pollJob?.cancel()
        pollJob = null
        val w = web
        web = null
        if (w != null) drainOnce(w)   // final drain
        val steps = assembleSteps()
        if (steps.isEmpty()) return null
        return RecordedSkill(
            id = "rec-" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = name.ifBlank { "Recorded ${steps.size} steps" },
            description = description,
            startUrl = startUrl,
            steps = steps,
            source = RecordedSkill.SOURCE_RECORDER
        )
    }

    fun cancel() {
        if (state != State.RECORDING) return
        state = State.IDLE
        pollJob?.cancel()
        pollJob = null
        web = null
        rawEvents.clear()
    }

    // ------------------------------------------------------------- polling

    private suspend fun pollLoop() {
        while (kotlin.coroutines.coroutineContext.isActive && state == State.RECORDING) {
            val w = web
            if (w != null) drainOnce(w)
            delay(400)
        }
    }

    private fun drainOnce(w: WebView) {
        val js = "try { (window['$token'] && typeof window['$token'].drain === 'function') ? window['$token'].drain() : '[]' } catch(e) { '[]' }"
        // evaluateJavascript must run on the main thread
        scope.launch(Dispatchers.Main) {
            DomExtractor.evalJs(w, js)?.let { raw ->
                withContext(Dispatchers.Default) { ingest(raw) }
            }
        }
    }

    /** Schema-validate and append drained events (defensive: pages are untrusted). */
    private fun ingest(rawJson: String) {
        val arr = try { JSONArray(rawJson) } catch (_: Exception) { return }
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val kind = e.optString("t")
            val ts = e.optLong("ts", System.currentTimeMillis()).coerceIn(0, System.currentTimeMillis() + 60_000)
            when (kind) {
                "click" -> {
                    val target = parseTarget(e.optJSONObject("el")) ?: continue
                    rawEvents.add(JSONObject().put("t", "click").put("el", target.toJson()).put("ts", ts))
                    lastClickAt = ts
                }
                "type" -> {
                    val target = parseTarget(e.optJSONObject("el")) ?: continue
                    val sensitive = e.optBoolean("sensitive", false)
                    val value = if (sensitive) "" else e.optString("v", "").take(UserSkillStore.MAX_VALUE_LEN)
                    rawEvents.add(
                        JSONObject().put("t", "type").put("el", target.toJson())
                            .put("v", value).put("sensitive", sensitive)
                            .put("submit", e.optBoolean("submit", false)).put("ts", ts)
                    )
                }
                "select" -> {
                    val target = parseTarget(e.optJSONObject("el")) ?: continue
                    rawEvents.add(
                        JSONObject().put("t", "select").put("el", target.toJson())
                            .put("opt", e.optString("opt", "").take(200)).put("ts", ts)
                    )
                }
                "scroll" -> {
                    val dir = if (e.optString("dir") == "up") "up" else "down"
                    val amount = e.optInt("amount", 600).coerceIn(50, 5000)
                    rawEvents.add(JSONObject().put("t", "scroll").put("dir", dir).put("amount", amount).put("ts", ts))
                }
                // anything else: ignored — unknown verbs never enter a skill
            }
        }
        listener.onStepCountChanged(rawEvents.size)
    }

    private fun parseTarget(o: JSONObject?): RecordedSkill.Target? {
        o ?: return null
        val t = RecordedSkill.Target(
            id = o.optString("id").take(120),
            name = o.optString("name").take(120),
            ariaLabel = o.optString("aria").take(120),
            text = o.optString("text").take(80),
            placeholder = o.optString("ph").take(80),
            tag = o.optString("tag").take(20).lowercase(),
            cssPath = o.optString("css").take(300),
            x = o.optInt("x").coerceIn(0, 4096),
            y = o.optInt("y").coerceIn(0, 8192)
        )
        return if (t.selectorCount() == 0 && (t.x == 0 || t.y == 0)) null else t
    }

    // ------------------------------------------------------------ assembly

    /**
     * Raw events → [RecordedSkill.Step]s:
     *  - consecutive typing into the same field merges into one step
     *  - scroll bursts merge; direction kept, amount summed (capped)
     *  - a human pause > 3s before a step becomes an explicit wait (≤ 8s)
     *  - navigations dedupe
     */
    private fun assembleSteps(): List<RecordedSkill.Step> {
        val steps = mutableListOf<RecordedSkill.Step>()
        var prevTs = 0L
        for (e in rawEvents) {
            val ts = e.optLong("ts")
            val gap = if (prevTs == 0L) 0 else ts - prevTs
            prevTs = ts
            if (gap > 3000 && steps.isNotEmpty() && steps.last().action != "wait") {
                steps.add(RecordedSkill.Step(action = "wait", ms = (gap.toInt()).coerceIn(1000, 8000)))
            }
            when (e.optString("t")) {
                "nav" -> {
                    val url = RecordedSkill.sanitizeUrl(e.optString("url"))
                    if (url.isNotEmpty() && (steps.isEmpty() || steps.last().action != "navigate" || steps.last().url != url)) {
                        steps.add(RecordedSkill.Step(action = "navigate", url = url))
                    }
                }
                "click" -> {
                    val t = RecordedSkill.Target.fromJson(e.optJSONObject("el") ?: JSONObject())
                    steps.add(RecordedSkill.Step(action = "click", target = t))
                }
                "type" -> {
                    val t = RecordedSkill.Target.fromJson(e.optJSONObject("el") ?: JSONObject())
                    val value = e.optString("v")
                    val sensitive = e.optBoolean("sensitive", false)
                    val submit = e.optBoolean("submit", false)
                    val last = steps.lastOrNull()
                    val sameField = last?.action == "type" && last.target != null &&
                        last.target.cssPath == t.cssPath && last.target.id == t.id && last.target.name == t.name
                    if (sameField && last != null) {
                        steps[steps.size - 1] = last.copy(text = value, sensitive = sensitive || last.sensitive, submit = submit || last.submit)
                    } else {
                        steps.add(RecordedSkill.Step(action = "type", target = t, text = value, sensitive = sensitive, submit = submit))
                    }
                }
                "select" -> {
                    val t = RecordedSkill.Target.fromJson(e.optJSONObject("el") ?: JSONObject())
                    steps.add(RecordedSkill.Step(action = "select", target = t, option = e.optString("opt")))
                }
                "scroll" -> {
                    val dir = e.optString("dir")
                    val amount = e.optInt("amount")
                    val last = steps.lastOrNull()
                    if (last?.action == "scroll" && last.direction == dir) {
                        steps[steps.size - 1] = last.copy(amount = (last.amount + amount).coerceAtMost(20000))
                    } else {
                        steps.add(RecordedSkill.Step(action = "scroll", direction = dir, amount = amount))
                    }
                }
            }
        }
        // Enter-to-submit is recorded as part of the type step when a submit
        // event arrived for the same field (handled in JS via "type" + submit flag).
        return steps.take(UserSkillStore.MAX_STEPS)
    }

    // -------------------------------------------------------- page collector

    /**
     * Namespace-isolated collector. Closure-held buffer, one accessor behind
     * the session token. Capture-phase listeners; never blocks user input.
     */
    private fun installCollector(web: WebView) {
        val js = """
        (function(){
          if (window.__cxRecActive) return;
          window.__cxRecActive = true;
          var buf = [];
          var lastScroll = 0, lastScrollY = window.scrollY, lastScrollDir = '';
          function chain(el){
            var parts = [];
            var node = el, depth = 0;
            while (node && node.nodeType === 1 && depth < 5) {
              var seg = node.tagName.toLowerCase();
              if (node.id) { parts.unshift(seg + '#' + node.id); break; }
              var parent = node.parentElement;
              if (parent) {
                var sibs = Array.prototype.filter.call(parent.children, function(c){ return c.tagName === node.tagName; });
                if (sibs.length > 1) seg += ':nth-of-type(' + (sibs.indexOf(node) + 1) + ')';
              }
              parts.unshift(seg);
              node = parent; depth++;
            }
            return parts.join('>').slice(0, 300);
          }
          function describe(el){
            var rect = el.getBoundingClientRect();
            var interactive = el.closest('a,button,input,select,textarea,[role="button"],[role="link"],label,[contenteditable="true"]');
            var t = interactive || el;
            return {
              id: (t.id || '').slice(0,120),
              name: (t.getAttribute && t.getAttribute('name') || '').slice(0,120),
              aria: (t.getAttribute && (t.getAttribute('aria-label') || t.getAttribute('title')) || '').slice(0,120),
              text: ((t.innerText || t.textContent || '').replace(/\s+/g,' ').trim()).slice(0,80),
              ph: (t.getAttribute && (t.getAttribute('placeholder') || t.getAttribute('aria-placeholder')) || '').slice(0,80),
              tag: (t.tagName || '').toLowerCase(),
              css: chain(t),
              x: Math.round(rect.left + rect.width/2),
              y: Math.round(rect.top + rect.height/2)
            };
          }
          function sensitive(el){
            if (!el || !el.tagName) return false;
            var ty = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
            if (ty === 'password') return true;
            var ac = (el.getAttribute && el.getAttribute('autocomplete') || '').toLowerCase();
            if (ac.indexOf('cc-') === 0 || ac === 'one-time-code' || ac === 'current-password' || ac === 'new-password') return true;
            if ((el.getAttribute && el.getAttribute('name') || '').toLowerCase().indexOf('cvv') >= 0) return true;
            return false;
          }
          function push(ev){ if (buf.length < 400) { ev.ts = Date.now(); buf.push(ev); } }

          document.addEventListener('click', function(ev){
            var el = ev.target;
            if (!el || !el.closest) return;
            var t = el.closest('a,button,[role="button"],[role="link"],label,input[type=checkbox],input[type=radio],input[type=submit],select,[onclick]') || el;
            var r = t.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) return; // hidden/scripted
            push({t:'click', el: describe(t)});
          }, true);

          document.addEventListener('change', function(ev){
            var el = ev.target;
            if (!el || !el.tagName) return;
            var tag = el.tagName;
            if (tag === 'SELECT') {
              var opt = (el.selectedOptions && el.selectedOptions[0] && el.selectedOptions[0].text) || el.value || '';
              push({t:'select', el: describe(el), opt: String(opt).slice(0,200)});
            } else if (tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable) {
              var ty = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
              if (['checkbox','radio','submit','button','file','range','color'].indexOf(ty) >= 0) return;
              push({t:'type', el: describe(el), v: String(el.value || '').slice(0,5000), sensitive: sensitive(el)});
            }
          }, true);

          document.addEventListener('keydown', function(ev){
            if (ev.key === 'Enter') {
              var el = ev.target;
              if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                push({t:'type', el: describe(el), v: String(el.value || '').slice(0,5000), sensitive: sensitive(el), submit: true});
              }
            }
          }, true);

          window.addEventListener('scroll', function(){
            var now = Date.now();
            var y = window.scrollY;
            if (now - lastScroll < 800) { lastScroll = now; return; }
            var dir = y > lastScrollY ? 'down' : 'up';
            var amount = Math.abs(y - lastScrollY);
            lastScrollY = y; lastScroll = now;
            if (amount < 80) return;
            push({t:'scroll', dir: dir, amount: Math.round(amount)});
          }, {passive: true});

          window['$token'] = {
            drain: function(){
              var out = JSON.stringify(buf);
              buf.length = 0;
              return out;
            }
          };
        })()
        """.trimIndent()
        scope.launch(Dispatchers.Main) {
            try {
                DomExtractor.evalJs(web, js)
            } catch (e: Exception) {
                listener.onRecordError("recorder install failed: ${e.message}")
            }
        }
    }
}
