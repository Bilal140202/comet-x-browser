package com.cometx.browser.browse

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * CosmeticFilter (v2.0.0) — element-hiding (cosmetic) filtering.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner; selector list is a
 * validated EasyList generic-hide subset, CC-BY-SA-3.0, with curated
 * additions). Loads curated generic CSS selectors from assets and injects a
 * script that hides matching nodes, including nodes added later by dynamic
 * page scripts (debounced via requestAnimationFrame + MutationObserver).
 *
 * PORT FIX: the Zerium fallback branch contained `hide(n2])` — a JS syntax
 * error that would have failed the WHOLE script at parse time. This port
 * emits `hide(n2[m])` and additionally validates the generated script
 * structure in the unit-test gate (CosmeticFilterTest).
 */
object CosmeticFilter {

    /** Selectors must be plain CSS: letters, digits, and attribute/class syntax only. */
    private val SAFE_SELECTOR = Regex("^[A-Za-z0-9_\\-.\\[\\]\\^$\\*=\"'#~:()>+,|\\s]+$")

    private const val BATCH = 60

    @Volatile
    private var cachedScript: String? = null

    fun invalidate() {
        cachedScript = null
    }

    fun getScript(c: Context, enabled: Boolean): String? {
        if (!enabled) return null
        val s = cachedScript
        if (s != null) return s
        val built = buildScript(loadSelectors(c))
        cachedScript = built
        return built
    }

    /** Updated (downloaded) list wins over the shipped snapshot. */
    fun loadSelectors(c: Context): List<String> {
        runCatching {
            val updated = File(c.filesDir, FilterUpdater.COSMETIC_FILE)
            if (updated.exists() && updated.length() > 0) {
                val fromFile = readFileLines(updated)
                if (fromFile.isNotEmpty()) return fromFile
            }
        }
        return runCatching {
            readAssetLines(c, "blocklists/cosmetic.txt")
        }.getOrDefault(emptyList())
    }

    private fun readFileLines(f: File): List<String> = runCatching {
        FileInputStream(f).use { readStreamLines(it) }
    }.getOrDefault(emptyList())

    private fun readAssetLines(c: Context, path: String): List<String> = runCatching {
        c.assets.open(path).use { readStreamLines(it) }
    }.getOrDefault(emptyList())

    private fun readStreamLines(input: InputStream): List<String> {
        val out = ArrayList<String>(1400)
        val r = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        var line = r.readLine()
        while (line != null) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!")) out.add(t)
            line = r.readLine()
        }
        r.close()
        return out
    }

    /** Visible-for-testing: sanitize + batch + build the hiding script. */
    fun buildScript(rawSelectors: List<String>): String {
        val arr = JSONArray()
        for (sel in rawSelectors) {
            if (sel.length < 3 || sel.length > 200) continue
            if (!SAFE_SELECTOR.matches(sel)) continue
            arr.put(sel)
        }
        val json = arr.toString()
        // Selectors are queried in comma-joined batches (one engine pass per
        // batch). If a batch fails to parse as a whole (e.g. a :has() on an
        // old engine), it is retried selector by selector.
        val js = StringBuilder()
        js.append("(function(){")
            .append("var sels=").append(json).append(";")
            .append("if(!sels||!sels.length)return;")
            .append("var BATCH=").append(BATCH).append(",groups=[];")
            .append("for(var i=0;i<sels.length;i+=BATCH){groups.push(sels.slice(i,i+BATCH));}")
            .append("var pending=false;")
            .append("function hide(el){el.style.setProperty('display','none','important');")
            .append("el.setAttribute('data-cometx-hidden','1');}")
            .append("function applyGroup(g){try{")
            .append("var nodes=document.querySelectorAll(g.join(','));")
            .append("for(var j=0;j<nodes.length;j++)hide(nodes[j]);")
            .append("}catch(e){for(var k=0;k<g.length;k++){try{")
            .append("var n2=document.querySelectorAll(g[k]);")
            .append("for(var m=0;m<n2.length;m++)hide(n2[m]);")
            .append("}catch(e2){}}}}")
            .append("function apply(){pending=false;")
            .append("for(var i=0;i<groups.length;i++)applyGroup(groups[i]);}")
            .append("function schedule(){if(!pending){pending=true;")
            .append("if(window.requestAnimationFrame){requestAnimationFrame(apply);}else{apply();}}}")
            .append("if(document.readyState==='loading'){")
            .append("document.addEventListener('DOMContentLoaded',schedule);}else{schedule();}")
            .append("window.addEventListener('load',schedule);")
            .append("try{var mo=new MutationObserver(schedule);")
            .append("function startMo(){mo.observe(document.documentElement,{childList:true,subtree:true});}")
            .append("if(document.documentElement){startMo();}")
            .append("else{document.addEventListener('DOMContentLoaded',startMo);}}catch(e){}")
            .append("})();")
        return js.toString()
    }

    /** Diagnostics helper: how many selectors survived sanitization. */
    fun countValid(rawSelectors: List<String>): Int {
        var n = 0
        for (sel in rawSelectors) {
            if (sel.length >= 3 && sel.length <= 200 && SAFE_SELECTOR.matches(sel)) n++
        }
        return n
    }
}
