package com.cometx.browser.browse

import android.content.Context

/**
 * StartPage (v2.0.0) — the built-in start page shown on new tabs.
 *
 * Adapted from the Zerium codebase (GPL-3.0, same owner) with Comet-X brand
 * tokens: violet gradient wordmark, search pill wired to the selected search
 * engine (built-in or custom), dynamic shortcut tiles ([StartPageLogic]), a
 * live blocking-statistics card and the agent call-to-action that makes
 * Comet-X Comet-X: "Give your task to the Agent".
 *
 * The page never phones home: it is generated locally on every load, honors
 * prefers-color-scheme and carries no telemetry of any kind.
 */
object StartPage {

    /** The omnibox sentinel that always renders the start page. */
    const val HOME_URL = "about:home"
    const val HOME_SCHEME_ALIAS = "cometx://home"

    /** True when [url] is a start-page URL (sentinel or generated data: page). */
    fun isStartPage(url: String?): Boolean {
        if (url == null) return false
        return url.isEmpty() || url == HOME_URL || url == "about:blank" ||
            url.startsWith("data:")
    }

    fun searchAction(engineTemplate: String): String =
        engineTemplate.replace("%s", "\u0001COMETQ\u0001")

    fun versionName(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    fun html(
        c: Context,
        tiles: List<StartPageLogic.Tile>,
        engineTemplate: String,
        totalBlocked: Long
    ): String {
        val action = searchAction(engineTemplate)
        val blockedText = if (totalBlocked > 0) {
            "${totalBlocked} ads and trackers blocked so far"
        } else {
            "Ad & tracker blocking starts with your first page load"
        }
        val version = versionName(c)

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
            .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
            .append("<title>Comet-X</title><style>")
            // Theme tokens — Comet violet on light/dark
            .append(":root{--bg:#FCFBFF;--fg:#1B1A22;--muted:#494657;--card:#F0ECF7;--accent:#6D28D9;")
            .append("--accent2:#A78BFA;--border:#C9C3D8;--chip:#F1EDF8}")
            .append("@media (prefers-color-scheme: dark){:root{--bg:#121016;--fg:#E6E1F3;--muted:#A49FB5;")
            .append("--card:#1E1A26;--accent:#A78BFA;--accent2:#6D28D9;--border:#2E2939;--chip:#241F2F}}")
            .append("*{box-sizing:border-box;margin:0;padding:0}")
            .append("body{font-family:system-ui,sans-serif;background:var(--bg);color:var(--fg);")
            .append("min-height:100vh;display:flex;flex-direction:column;align-items:center;")
            .append("justify-content:center;padding:24px}")
            // Hero
            .append(".logo{font-size:40px;font-weight:800;letter-spacing:-1.5px;line-height:1;")
            .append("background:linear-gradient(135deg,var(--accent),var(--accent2));")
            .append("-webkit-background-clip:text;background-clip:text;color:transparent}")
            .append(".logo b{color:var(--accent);-webkit-text-fill-color:var(--accent)}")
            .append(".tag{color:var(--muted);font-size:13px;margin:8px 0 26px;letter-spacing:.2px}")
            // Agent CTA
            .append(".agent{width:100%;max-width:580px;margin-bottom:22px;display:flex;align-items:center;gap:12px;")
            .append("background:linear-gradient(135deg,color-mix(in srgb,var(--accent) 14%,var(--card)),var(--card));")
            .append("border:1px solid var(--border);border-radius:20px;padding:14px 16px;}")
            .append(".agent .ico{flex:none;width:38px;height:38px;border-radius:12px;display:flex;align-items:center;")
            .append("justify-content:center;background:var(--accent);color:#fff;font-size:18px;font-weight:700}")
            .append(".agent .t1{font-size:14px;font-weight:600;color:var(--fg)}")
            .append(".agent .t2{font-size:12px;color:var(--muted);margin-top:2px}")
            // Search
            .append("form{width:100%;max-width:580px;margin-bottom:26px}")
            .append(".search{display:flex;align-items:center;background:var(--card);")
            .append("border:1px solid var(--border);border-radius:26px;padding:4px 4px 4px 18px;")
            .append("box-shadow:0 4px 18px rgba(60,40,120,.08);transition:border-color .15s}")
            .append(".search:focus-within{border-color:var(--accent)}")
            .append(".search svg{flex:none;opacity:.55}")
            .append("input{flex:1;min-width:0;padding:13px 12px;font-size:16px;border:0;outline:none;")
            .append("background:transparent;color:var(--fg)}")
            .append("button{flex:none;border:0;border-radius:20px;padding:11px 20px;font-size:14px;")
            .append("font-weight:600;color:#fff;background:var(--accent);cursor:pointer}")
            .append("button:active{opacity:.85}")
            // Tiles
            .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(84px,1fr));")
            .append("gap:10px;width:100%;max-width:580px}")
            .append("a.tile{display:flex;flex-direction:column;align-items:center;gap:8px;")
            .append("padding:14px 6px 12px;background:var(--card);border:1px solid var(--border);")
            .append("border-radius:18px;text-decoration:none;color:var(--fg);font-size:12px;")
            .append("transition:transform .1s,border-color .15s}")
            .append("a.tile:active{transform:scale(.96);border-color:var(--accent)}")
            .append(".ic{width:44px;height:44px;border-radius:50%;display:flex;align-items:center;")
            .append("justify-content:center;color:#fff;font-size:19px;font-weight:700}")
            // Stat card
            .append(".stat{margin-top:26px;width:100%;max-width:580px;display:flex;align-items:center;")
            .append("gap:10px;background:var(--chip);border:1px solid var(--border);border-radius:16px;")
            .append("padding:12px 16px;color:var(--muted);font-size:12.5px}")
            .append(".dot{flex:none;width:8px;height:8px;border-radius:50%;background:var(--accent)}")
            .append(".stat b{color:var(--fg);font-weight:600}")
            .append("</style></head><body>")
            .append("<div class='logo'>Comet-X<b>.</b></div>")
            .append("<div class='tag'>Agentic &middot; Private &middot; Yours</div>")
            // Agent CTA — tap opens the in-app agent via the deep link the app resolves
            .append("<div class='agent'><div class='ico'>\u2726</div><div>")
            .append("<div class='t1'>Give your task to the Agent</div>")
            .append("<div class='t2'>It browses, clicks and fills while you do something else.</div>")
            .append("</div></div>")
            .append("<form onsubmit=\"var q=document.getElementById('q').value.trim();")
            .append("if(q){var t='").append(action).append("';")
            .append("location.href=t.replace('\\u0001COMETQ\\u0001',encodeURIComponent(q));}")
            .append("return false;\">")
            .append("<div class='search'>")
            .append("<svg width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor'")
            .append(" stroke-width='2.4' stroke-linecap='round'><circle cx='11' cy='11' r='7'/>")
            .append("<path d='M20 20l-3.6-3.6'/></svg>")
            .append("<input id='q' type='search' placeholder='Search or type a URL' autocomplete='off' autofocus>")
            .append("<button type='submit'>Go</button>")
            .append("</div></form>")
            .append("<div class='grid'>")
        for (t in tiles) {
            sb.append(tile(t.icon, t.color, t.name, t.url))
        }
        sb.append("</div>")
            .append("<div class='stat'><span class='dot'></span><span><b>")
            .append(blockedText).append("</b> &middot; Comet-X v")
            .append(version).append(" &mdash; no telemetry, ever.</span></div>")
            .append("</body></html>")
        return sb.toString()
    }

    private fun tile(icon: String, color: String, name: String, url: String): String {
        val safeName = escape(name)
        val safeUrl = escapeUrl(url)
        val safeIcon = escape(icon)
        return "<a class='tile' href='$safeUrl'><span class='ic' style='background:" +
            color + "'>" + safeIcon + "</span>" + safeName + "</a>"
    }

    private fun escape(s: String?): String =
        (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeUrl(s: String?): String =
        (s ?: "").replace("&", "&amp;").replace("'", "&#39;").replace("\"", "&quot;")
}
