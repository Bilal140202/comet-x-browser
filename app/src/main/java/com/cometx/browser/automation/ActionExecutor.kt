package com.cometx.browser.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import com.cometx.browser.util.Json
import com.cometx.browser.util.Logx
import com.cometx.browser.perception.DomExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ActionExecutor — performs validated agent actions against the live WebView.
 * Page actions run as namespace-isolated JS through evaluateJavascript and
 * return JSON result objects; browser-level actions (back/forward/tabs/zoom)
 * use the WebView API. All WebView work is marshalled to the main thread.
 */
class ActionExecutor(private val context: Context) {

    data class Result(val ok: Boolean, val message: String, val data: JSONObject? = null) {
        fun summary(): String = if (ok) message else "FAILED: $message"
    }

    suspend fun execute(webView: WebView, action: JSONObject): Result = withContext(Dispatchers.Main) {
        try {
            executeOnMain(webView, action)
        } catch (e: Exception) {
            Logx.e("executor error for ${action.optString("action")}", e)
            Result(false, "executor exception: ${e.message}")
        }
    }

    private suspend fun executeOnMain(webView: WebView, action: JSONObject): Result {
        val kind = action.optString("action", "")
        return when (kind) {
            "click" -> jsAction(webView, jsClick(Json.jsString(action.optString("ref"))))
            "click_at" -> jsAction(webView, jsClickAt(action.optDouble("x", 0.0), action.optDouble("y", 0.0)))
            "type" -> jsAction(webView, jsType(Json.jsString(action.optString("ref")), Json.jsString(action.optString("text", "")), action.optBoolean("submit", false)))
            "select" -> jsAction(webView, jsSelect(Json.jsString(action.optString("ref")), Json.jsString(action.optString("option", ""))))
            "paste" -> {
                val clip = clipboardText() ?: return Result(false, "clipboard is empty")
                jsAction(webView, jsType(Json.jsString(action.optString("ref")), Json.jsString(clip), false))
            }
            "press_key" -> jsAction(webView, jsPressKey(Json.jsString(action.optString("key"))))
            "scroll" -> jsAction(webView, jsScroll(action.optString("direction", "down"), action.optInt("amount", 600)))
            "find_text" -> jsAction(webView, jsFindText(Json.jsString(action.optString("text"))))
            "find_element" -> jsAction(webView, jsFindElement(Json.jsString(action.optString("description", action.optString("text", "")))))
            "extract" -> jsAction(webView, jsExtract(action.optString("what", "text")))
            "copy" -> jsAction(webView, jsCopy())
            "navigate" -> {
                val url = action.optString("url", "")
                webView.loadUrl(url)
                Result(true, "navigating to ${url.take(80)}")
            }
            "back" -> { if (webView.canGoBack()) { webView.goBack(); Result(true, "went back") } else Result(false, "no back history") }
            "forward" -> { if (webView.canGoForward()) { webView.goForward(); Result(true, "went forward") } else Result(false, "no forward history") }
            "reload" -> { webView.reload(); Result(true, "reloading page") }
            "wait" -> { val ms = action.optInt("ms", 800).coerceIn(100, 15000); kotlinx.coroutines.delay(ms.toLong()); Result(true, "waited ${ms}ms") }
            "zoom" -> {
                val level = action.optString("level", "in")
                val cur = webView.settings.textZoom
                val next = when (level) { "in" -> (cur + 15).coerceAtMost(300); "out" -> (cur - 15).coerceAtLeast(30); "reset" -> 100; else -> action.optInt("level", 100).coerceIn(30, 300) }
                webView.settings.textZoom = next
                Result(true, "text zoom set to $next%")
            }
            else -> Result(false, "action '$kind' is not executable here (tabs/downloads handled by browser layer)")
        }
    }

    private suspend fun jsAction(webView: WebView, js: String): Result {
        val raw = DomExtractor.evalJs(webView, js) ?: return Result(false, "page returned no result (may be reloading)")
        val obj = Json.parseOrNull(raw) ?: return Result(false, "unparseable action result")
        return Result(
            obj.optBoolean("ok", false),
            obj.optString("msg", "done").take(200),
            obj
        )
    }

    private fun clipboardText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label.take(20), text))
    }

    // ------------------------------------------------------------- JS snippets
    // All snippets return a JSON object: {ok: bool, msg: string, ...extra}

    private fun jsClick(ref: String) = """
    (function(){
      try {
        var el = document.querySelector('[data-cx-ref=$ref]');
        if (!el) return {ok:false, msg:'element $ref not found (page changed? re-observe)'};
        el.scrollIntoView({block:'center', behavior:'instant'});
        var r = el.getBoundingClientRect();
        var cx = r.left + r.width/2, cy = r.top + r.height/2;
        var opts = {bubbles:true, cancelable:true, clientX:cx, clientY:cy};
        ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){
          var ev = (t.indexOf('pointer')===0 && window.PointerEvent) ? new PointerEvent(t, Object.assign({pointerId:1, isPrimary:true}, opts)) : new MouseEvent(t, opts);
          el.dispatchEvent(ev);
        });
        // ONE click only — double dispatch fired submit handlers twice (expert review P1-12)
        if (typeof el.click === 'function') { try { el.click(); } catch(e){} }
        else { el.dispatchEvent(new MouseEvent('click', opts)); }
        return {ok:true, msg:'clicked ' + (el.innerText||el.getAttribute('aria-label')||el.tagName).toString().slice(0,60)};
      } catch(err){ return {ok:false, msg:'click error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsClickAt(x: Double, y: Double) = """
    (function(){
      try {
        var el = document.elementFromPoint($x, $y);
        if (!el) return {ok:false, msg:'nothing at ($x,$y)'};
        var interactive = el.closest('a,button,input,select,textarea,[role="button"],[role="link"],[role="checkbox"],[role="radio"],[role="menuitem"],label');
        var target = interactive || el;
        var opts = {bubbles:true, cancelable:true, clientX:$x, clientY:$y};
        ['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){
          var ev = (t.indexOf('pointer')===0 && window.PointerEvent) ? new PointerEvent(t, Object.assign({pointerId:1, isPrimary:true}, opts)) : new MouseEvent(t, opts);
          target.dispatchEvent(ev);
        });
        if (typeof target.click === 'function') { try { target.click(); } catch(e){} }
        else { target.dispatchEvent(new MouseEvent('click', opts)); }
        return {ok:true, msg:'clicked at ($x,$y) -> ' + (target.innerText||target.tagName).toString().slice(0,60)};
      } catch(err){ return {ok:false, msg:'click_at error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsType(ref: String, text: String, submit: Boolean) = """
    (function(){
      try {
        var el = document.querySelector('[data-cx-ref=$ref]');
        if (!el) return {ok:false, msg:'element $ref not found'};
        el.scrollIntoView({block:'center', behavior:'instant'});
        el.focus();
        var isCE = el.isContentEditable === true;
        if (!isCE && el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA') {
          return {ok:false, msg:'element is not a text field ('+el.tagName+')'};
        }
        try { if (window.getSelection) { var sel = window.getSelection(); sel.removeAllRanges(); var rng = document.createRange(); rng.selectNodeContents(el); sel.addRange(rng); } } catch(e) {}
        if (isCE) {
          document.execCommand('selectAll', false, null);
          document.execCommand('insertText', false, $text);
        } else {
          var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
          var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
          setter.call(el, $text);
          el.dispatchEvent(new Event('input', {bubbles:true}));
          el.dispatchEvent(new Event('change', {bubbles:true}));
        }
        if ($submit) {
          var form = el.form || el.closest('form');
          var fired = false;
          if (form && typeof form.requestSubmit === 'function') { form.requestSubmit(); fired = true; }
          else if (form) { var btn = form.querySelector('button[type=submit],input[type=submit],button:not([type])'); if (btn) { btn.click(); fired = true; } }
          if (!fired) {
            ['keydown','keypress','keyup'].forEach(function(t){
              el.dispatchEvent(new KeyboardEvent(t, {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
            });
          }
          return {ok:true, msg:'typed and submitted'};
        }
        return {ok:true, msg:'typed ' + $text.length + ' chars into ' + String(el.name||el.tagName||'field').slice(0,30)};
      } catch(err){ return {ok:false, msg:'type error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsSelect(ref: String, option: String) = """
    (function(){
      try {
        var el = document.querySelector('[data-cx-ref=$ref]');
        if (!el || el.tagName !== 'SELECT') return {ok:false, msg:'element is not a select'};
        var want = $option.toLowerCase();
        var idx = -1;
        for (var i=0;i<el.options.length;i++){
          var o = el.options[i];
          if (o.value.toLowerCase()===want || o.text.toLowerCase()===want || o.text.toLowerCase().indexOf(want)>=0) { idx = i; break; }
        }
        if (idx<0) return {ok:false, msg:'no option matching ' + $option + ' (options: ' + Array.from(el.options).slice(0,10).map(function(o){return o.text.slice(0,25)}).join(', ') + ')'};
        el.selectedIndex = idx;
        el.dispatchEvent(new Event('change', {bubbles:true}));
        return {ok:true, msg:'selected "'+el.options[idx].text+'"'};
      } catch(err){ return {ok:false, msg:'select error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsPressKey(key: String) = """
    (function(){
      try {
        var el = document.activeElement || document.body;
        var kc = {'Enter':13,'Tab':9,'Escape':27,'ArrowDown':40,'ArrowUp':38,'ArrowLeft':37,'ArrowRight':39,'PageDown':34,'PageUp':33,'Home':36,'End':35,'Backspace':8,'Delete':46}[$key] || 0;
        ['keydown','keypress','keyup'].forEach(function(t){
          el.dispatchEvent(new KeyboardEvent(t, {key:$key, code:$key, keyCode:kc, which:kc, bubbles:true, cancelable:true}));
        });
        if ($key === 'Enter') {
          var form = el.form || el.closest('form');
          if (form && typeof form.requestSubmit === 'function') { form.requestSubmit(); }
        }
        return {ok:true, msg:'pressed $key on ' + (el.name||el.tagName||'body').toString().slice(0,40)};
      } catch(err){ return {ok:false, msg:'press_key error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsScroll(direction: String, amount: Int) = """
    (function(){
      try {
        var d = '$direction', a = $amount;
        if (d==='top') window.scrollTo(0,0);
        else if (d==='bottom') window.scrollTo(0, document.documentElement.scrollHeight);
        else if (d==='up') window.scrollBy(0,-a);
        else window.scrollBy(0,a);
        return {ok:true, msg:'scrolled ' + d + ' (now at ' + Math.round(scrollY) + ')'};
      } catch(err){ return {ok:false, msg:'scroll error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsFindText(text: String) = """
    (function(){
      try {
        var needle = $text.toLowerCase();
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
        var matches = [], node;
        while ((node = walker.nextNode()) && matches.length < 12) {
          var t = node.textContent || '';
          var idx = t.toLowerCase().indexOf(needle);
          if (idx >= 0) {
            var ctx = t.replace(/\s+/g,' ').trim();
            matches.push(ctx.slice(0, 120));
            var el = node.parentElement;
            if (matches.length === 1 && el) el.scrollIntoView({block:'center', behavior:'instant'});
          }
        }
        return {ok: matches.length > 0, msg: matches.length + ' match(es) for ' + $text, matches: matches};
      } catch(err){ return {ok:false, msg:'find_text error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsFindElement(description: String) = """
    (function(){
      try {
        var words = $description.toLowerCase().split(/[^a-z0-9]+/).filter(function(w){return w.length>2});
        var els = document.querySelectorAll('[data-cx-ref]');
        var scored = [];
        for (var i=0;i<els.length;i++){
          var el = els[i];
          var hay = ((el.innerText||'') + ' ' + (el.getAttribute('aria-label')||'') + ' ' + (el.getAttribute('placeholder')||'') + ' ' + (el.getAttribute('title')||'') + ' ' + (el.getAttribute('name')||'')).toLowerCase();
          var score = 0;
          for (var w=0; w<words.length; w++) if (hay.indexOf(words[w])>=0) score++;
          if (score > 0) scored.push({ref: el.getAttribute('data-cx-ref'), score: score, desc: (el.innerText||el.getAttribute('aria-label')||el.tagName).toString().replace(/\s+/g,' ').trim().slice(0,60)});
        }
        scored.sort(function(a,b){ return b.score - a.score; });
        return {ok: scored.length > 0, msg: scored.length + ' candidate(s)', candidates: scored.slice(0,5)};
      } catch(err){ return {ok:false, msg:'find_element error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsExtract(what: String) = """
    (function(){
      try {
        var kind = '$what';
        var result = {};
        if (kind==='text' || kind==='all') result.text = (document.body.innerText||'').replace(/[ \t]+/g,' ').slice(0, 2500);
        if (kind==='links' || kind==='all') {
          var links = [];
          var as = document.querySelectorAll('a[href]');
          for (var i=0;i<as.length && links.length<25;i++){
            var t = (as[i].innerText||'').replace(/\s+/g,' ').trim();
            if (t && as[i].href) links.push(t.slice(0,60) + ' -> ' + as[i].href.slice(0,90));
          }
          result.links = links;
        }
        if (kind==='tables' || kind==='all') {
          var tables = [];
          var ts = document.querySelectorAll('table');
          for (var t2=0; t2<ts.length && tables.length<4; t2++){
            var rows = [];
            var trs = ts[t2].querySelectorAll('tr');
            for (var r=0; r<Math.min(trs.length, 12); r++){
              var cells = trs[r].querySelectorAll('th,td');
              var line = [];
              for (var c2=0; c2<Math.min(cells.length, 8); c2++) line.push((cells[c2].innerText||'').replace(/\s+/g,' ').trim().slice(0,40));
              rows.push(line.join(' | '));
            }
            tables.push(rows);
          }
          result.tables = tables;
        }
        return Object.assign({ok:true, msg:'extracted ' + kind}, result);
      } catch(err){ return {ok:false, msg:'extract error: '+err.message}; }
    })()
    """.trimIndent()

    private fun jsCopy() = """
    (function(){
      try {
        var sel = window.getSelection ? window.getSelection().toString() : '';
        if (!sel) sel = (document.body.innerText||'').slice(0, 2000);
        return {ok:true, msg:'captured ' + sel.length + ' chars', text: sel.slice(0,2000)};
      } catch(err){ return {ok:false, msg:'copy error: '+err.message}; }
    })()
    """.trimIndent()
}
