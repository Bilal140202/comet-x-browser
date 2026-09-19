package com.cometx.browser.browse

import android.content.Context

/**
 * ReaderSupport (v2.0.0) — Reader view toggle scripts.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). Reader mode bundles
 * Mozilla's Readability (v0.6.0, Apache-2.0 — see the header inside
 * assets/readability.js). The library source is injected as part of the same
 * evaluateJavascript call as the toggle logic, wrapped in an IIFE: page CSP
 * does not apply to evaluateJavascript (unlike script-tag injection), and
 * function declarations inside the IIFE are grabbed into a window handle so
 * repeated invocations never re-declare anything.
 *
 * The reader is a client-side *presentation* toggle: the original DOM
 * snapshot is cached on the page itself and restored on toggle-off. It parses
 * whatever the WebView currently holds — paywalled pages yield whatever the
 * page had, nothing is re-fetched or faked.
 */
object ReaderSupport {

    private const val READER_CSS =
        "#cometReaderMain{max-width:680px;margin:0 auto;padding:28px 20px 72px;}" +
            "#cometReaderMain h1{font-size:1.65em;line-height:1.25;margin:0 0 6px;}" +
            "#cometReaderMain .crMeta{opacity:.65;font-size:.85em;margin:0 0 22px;" +
            "font-family:system-ui,sans-serif;}" +
            "#cometReaderMain .crContent{font-size:1.05em;line-height:1.65;word-wrap:break-word;}" +
            "#cometReaderMain .crContent img,#cometReaderMain .crContent video," +
            "#cometReaderMain .crContent table{max-width:100%;height:auto;}" +
            "#cometReaderMain .crContent pre{overflow-x:auto;white-space:pre-wrap;}" +
            "#cometReaderMain .crContent a{color:#6D28D9;}" +
            "body{background:#FCFBFF;color:#1B1A22;}" +
            "@media (prefers-color-scheme:dark){" +
            "body{background:#121016;color:#E6E1F3;}" +
            "#cometReaderMain .crContent a{color:#A78BFA;}}"

    @Volatile
    private var readabilitySource: String? = null

    private fun source(c: Context): String {
        val s = readabilitySource
        if (s != null) return s
        val read = runCatching {
            c.assets.open("readability.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        readabilitySource = read
        return read
    }

    /** Script that enters reader mode. Returns a JSON object via the JS callback. */
    fun onScript(c: Context): String {
        // The readability source is declared inside the IIFE; on first run the
        // function declaration binds locally and is stashed on the window so
        // later invocations reuse it without re-declaring.
        val js = StringBuilder()
        js.append("(function(){try{")
        js.append("if(!window.__cometReadability){")
        js.append(source(c))
        js.append("window.__cometReadability=Readability;}")
        js.append("var R=window.__cometReadability;")
        js.append("if(window.__cometReaderOriginal){return{ok:3};}")
        js.append("var article=new R(document.cloneNode(true),{charThreshold:200}).parse();")
        js.append("if(!article||!article.textContent||article.textContent.trim().length<250){return{ok:0};}")
        js.append("var words=article.textContent.trim().split(/\\s+/).length;")
        js.append("var minutes=Math.max(1,Math.round(words/265));")
        js.append("window.__cometReaderOriginal=document.documentElement.outerHTML;")
        js.append("var css=document.createElement('style');css.id='cometReaderCss';")
        js.append("css.textContent='").append(READER_CSS).append("';")
        js.append("(document.head||document.documentElement).appendChild(css);")
        js.append("var main=document.createElement('main');main.id='cometReaderMain';")
        js.append("var h=document.createElement('h1');")
        js.append("h.textContent=article.title||document.title||'';main.appendChild(h);")
        js.append("var meta=document.createElement('div');meta.className='crMeta';")
        js.append("var bits=[];if(article.byline){bits.push(article.byline);}")
        js.append("bits.push('~'+minutes+' min read');")
        js.append("meta.textContent=bits.join(' \\u00b7 ');main.appendChild(meta);")
        js.append("var body=document.createElement('div');body.className='crContent';")
        js.append("body.innerHTML=article.content||'';main.appendChild(body);")
        js.append("document.body.innerHTML='';document.body.appendChild(main);")
        js.append("window.scrollTo(0,0);")
        js.append("return{ok:1,minutes:minutes};")
        js.append("}catch(e){return{ok:0};}})();")
        return js.toString()
    }

    /** Script that leaves reader mode (restores the cached original DOM). */
    fun offScript(): String =
        "(function(){try{" +
            "if(window.__cometReaderOriginal){" +
            "document.documentElement.innerHTML=window.__cometReaderOriginal;" +
            "window.__cometReaderOriginal=null;window.scrollTo(0,0);return{ok:2};}" +
            "return{ok:0};}catch(e){return{ok:0};}})();"
}
