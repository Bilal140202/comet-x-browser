package com.cometx.browser.perception

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.cometx.browser.util.Logx
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * DomExtractor — injects a namespace-isolated runtime into the page, tags
 * interactive elements with stable refs, and returns a compact JSON snapshot.
 *
 * Security properties:
 *  - IIFE-wrapped, never registers globals except window.__cxSnapshot (last result)
 *  - No addJavascriptInterface: results come back via evaluateJavascript callbacks
 *  - Read-only: tagging adds a data attribute only; actions are a separate module
 */
object DomExtractor {

    /** Extract a compact observation. Must be called on the main thread. */
    suspend fun observe(webView: WebView, tabInfos: List<PageObservation.TabInfo>, activeTabIndex: Int): PageObservation? {
        val raw = evalJs(webView, JS_EXTRACT) ?: return null
        val root = try { JSONObject(raw) } catch (e: Exception) {
            Logx.e("DOM extract parse failed: ${e.message}")
            return null
        }
        return parse(root, tabInfos, activeTabIndex)
    }

    fun parse(root: JSONObject, tabInfos: List<PageObservation.TabInfo>, activeTabIndex: Int): PageObservation {
        val elements = mutableListOf<PageObservation.Element>()
        val elArr = root.optJSONArray("elements") ?: JSONArray()
        for (i in 0 until elArr.length()) {
            val o = elArr.optJSONObject(i) ?: continue
            elements.add(
                PageObservation.Element(
                    ref = o.optString("ref"),
                    tag = o.optString("tag"),
                    type = o.optStringOrNull("type"),
                    role = o.optStringOrNull("role"),
                    label = o.optStringOrNull("label"),
                    name = o.optStringOrNull("name"),
                    placeholder = o.optStringOrNull("ph"),
                    text = o.optStringOrNull("text"),
                    value = o.optStringOrNull("value"),
                    href = o.optStringOrNull("href"),
                    x = o.optInt("x"), y = o.optInt("y"),
                    w = o.optInt("w"), h = o.optInt("h"),
                    disabled = o.optBoolean("disabled", false),
                    required = o.optBoolean("required", false)
                )
            )
        }
        val forms = mutableListOf<PageObservation.Form>()
        val fArr = root.optJSONArray("forms") ?: JSONArray()
        for (i in 0 until fArr.length()) {
            val o = fArr.optJSONObject(i) ?: continue
            forms.add(
                PageObservation.Form(
                    index = o.optInt("index", 0),
                    action = o.optStringOrNull("action"),
                    method = o.optStringOrNull("method"),
                    fields = o.optInt("fields", 0),
                    hasPassword = o.optBoolean("has_password", false)
                )
            )
        }
        return PageObservation(
            url = root.optString("url", "about:blank"),
            title = root.optString("title", ""),
            viewportW = root.optInt("vw", 360),
            viewportH = root.optInt("vh", 640),
            scrollY = root.optInt("scrollY", 0),
            scrollMax = root.optInt("scrollMax", 0),
            elements = elements,
            forms = forms,
            tabs = tabInfos,
            activeTabIndex = activeTabIndex,
            textSample = root.optString("textSample", "")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val v = optString(key, "")
        return v.ifBlank { null }
    }

    /**
     * Evaluates JS that returns an OBJECT and resolves with the JSON text.
     * evaluateJavascript JSON-serializes non-string results itself, which is
     * the safest transport (no double-escaping bugs).
     */
    suspend fun evalJs(webView: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                webView.evaluateJavascript(js) { result ->
                    cont.resume(result?.takeIf { it != "null" && it.isNotBlank() })
                }
            } catch (e: Exception) {
                Logx.e("evaluateJavascript failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    // ------------------------------------------------------------------ JS
    // NOTE: written as a Kotlin raw string; JSON string-literals are produced
    // by the JS itself via JSON.stringify so quoting stays safe.

    private val JS_EXTRACT = """
    (function(){
      try {
        if (!document.body) return {url:location.href, title:document.title||'', vw:innerWidth, vh:innerHeight, scrollY:0, scrollMax:0, elements:[], forms:[], textSample:''};
        var els = document.querySelectorAll('[data-cx-ref]');
        for (var i=0;i<els.length;i++) els[i].removeAttribute('data-cx-ref');
        var n = 0, out = [];
        function vpRect(el){
          var r = el.getBoundingClientRect();
          return {x:Math.round(r.left), y:Math.round(r.top), w:Math.round(r.width), h:Math.round(r.height)};
        }
        function visible(el){
          var r = el.getBoundingClientRect();
          if (r.width<2 || r.height<2) return false;
          var st = getComputedStyle(el);
          if (st.display==='none' || st.visibility==='hidden' || st.opacity==='0') return false;
          if (r.bottom < -40 || r.top > innerHeight+40 || r.right < -40 || r.left > innerWidth+40) return false;
          return true;
        }
        var sel = 'a[href], button, input, select, textarea, [role="button"], [role="link"], [role="checkbox"], [role="radio"], [role="tab"], [role="menuitem"], [role="combobox"], [role="textbox"], [role="switch"], [role="option"], [contenteditable="true"], [contenteditable=""], summary, [onclick]';
        var nodes = document.querySelectorAll(sel);
        var limit = Math.min(nodes.length, 160);
        for (var i=0; i<limit; i++){
          var el = nodes[i];
          if (!visible(el)) continue;
          var tag = el.tagName.toLowerCase();
          var type = (tag==='input') ? (el.getAttribute('type')||'text') : null;
          if (tag==='input' && ['hidden','file'].indexOf(type)>=0) continue;
          var ref = 'e' + (++n);
          el.setAttribute('data-cx-ref', ref);
          var r = vpRect(el);
          var text = (el.innerText || el.value || '').replace(/\s+/g,' ').trim().slice(0,80);
          if (!text && tag==='a') { var img = el.querySelector('img[alt]'); if (img) text = img.alt.slice(0,80); }
          var aria = el.getAttribute('aria-label') || el.getAttribute('title') || '';
          var ph = el.getAttribute('placeholder') || '';
          var role = el.getAttribute('role') || '';
          out.push({
            ref: ref, tag: tag,
            type: type || null,
            role: role || null,
            label: aria ? aria.slice(0,80) : null,
            name: el.getAttribute('name') ? String(el.getAttribute('name')).slice(0,40) : null,
            ph: ph ? ph.slice(0,60) : null,
            text: text || null,
            value: (tag==='input' && type==='password') ? '[password]' : ((tag==='input'||tag==='textarea') ? String(el.value||'').slice(0,60) : null),
            href: (tag==='a' && el.href) ? el.href.slice(0,100) : null,
            x:r.x, y:r.y, w:r.w, h:r.h,
            disabled: !!el.disabled,
            required: !!el.required
          });
        }
        var forms = [];
        var fs = document.forms;
        for (var f=0; f<Math.min(fs.length,8); f++){
          var fm = fs[f];
          var hasPw = !!fm.querySelector('input[type=password]');
          forms.push({index:f, action:(fm.getAttribute('action')||null), method:(fm.getAttribute('method')||null), fields:fm.elements.length, has_password:hasPw});
        }
        var bodyText = (document.body.innerText || '').replace(/\s+/g,' ').trim().slice(0,600);
        return {
          url: location.href.slice(0,300),
          title: document.title || '',
          vw: innerWidth, vh: innerHeight,
          scrollY: Math.round(scrollY), scrollMax: Math.max(0, Math.round(document.documentElement.scrollHeight - innerHeight)),
          elements: out, forms: forms, textSample: bodyText
        };
      } catch(err) {
        return {url:location.href, title:'extract-error', vw:innerWidth, vh:innerHeight, scrollY:0, scrollMax:0, elements:[], forms:[], textSample:('extract error: '+err.message)};
      }
    })()
    """.trimIndent()
}
