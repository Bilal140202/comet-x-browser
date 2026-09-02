package com.cometx.browser.skills

import android.content.Context
import com.cometx.browser.util.Json
import org.json.JSONObject

/**
 * SkillRegistry — reusable, declarative agent skills (brief §24).
 * Each skill defines: goal template, preferred strategy, verification hints,
 * and failure-handling guidance, injected into the system prompt when active.
 * Skills ship as JSON assets; users can extend by adding files (future).
 */
data class Skill(
    val id: String,
    val name: String,
    val icon: String,
    val keywords: List<String>,
    val goalHint: String,
    val strategy: List<String>,
    val verification: String,
    val failureHandling: String,
    val promptExtra: String
) {
    fun matchScore(goal: String): Int {
        val g = goal.lowercase()
        var score = 0
        for (k in keywords) if (g.contains(k)) score += k.length
        return score
    }

    companion object {
        fun fromJson(o: JSONObject): Skill = Skill(
            id = o.optString("id"),
            name = o.optString("name"),
            icon = o.optString("icon", "✦"),
            keywords = o.optJSONArray("keywords")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList(),
            goalHint = o.optString("goal_hint"),
            strategy = o.optJSONArray("strategy")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it) } } ?: emptyList(),
            verification = o.optString("verification"),
            failureHandling = o.optString("failure_handling"),
            promptExtra = o.optString("prompt_extra")
        )
    }
}

class SkillRegistry(private val assetContext: Context?) {

    val skills: List<Skill> by lazy {
        val list = mutableListOf<Skill>()
        try {
            assetContext?.assets?.list("skills")?.sorted()?.forEach { name ->
                if (!name.endsWith(".json")) return@forEach
                val text = assetContext.assets.open("skills/$name").bufferedReader().use { it.readText() }
                Json.parseOrNull(text)?.let { o -> list.add(Skill.fromJson(o)) }
            }
        } catch (e: Exception) {
            // assets unavailable (unit tests) — fall back to built-in defaults
        }
        if (list.isEmpty()) list.addAll(defaults())
        list
    }

    fun byId(id: String): Skill? = skills.firstOrNull { it.id == id }

    /** Auto-select the best-matching skill for a goal (null = general web). */
    fun match(goal: String, minScore: Int = 4): Skill? =
        skills.map { it to it.matchScore(goal) }.filter { it.second >= minScore }
            .maxByOrNull { it.second }?.first

    private fun defaults(): List<Skill> = listOf(
        Skill("general-web", "General web", "✦", listOf("search", "find", "open", "go"),
            "Complete the user's task by browsing.",
            listOf("Observe the page before acting", "Prefer explicit refs over coordinates"),
            "Confirm the goal is visibly achieved before done.", "If stuck, re-observe; if still stuck, ask_user.", ""),
        Skill("research", "Research", "🔍", listOf("research", "find information", "look up", "compare articles"),
            "Gather the requested information from the web and summarize.",
            listOf("Search", "Open 2-3 authoritative results", "Extract and cross-check"),
            "Information is confirmed from at least two sources when possible.",
            "If results are thin, refine the search query once, then ask_user.", ""),
        Skill("shopping", "Shopping", "🛍", listOf("buy", "price", "shop", "order", "product", "cheapest"),
            "Find the best product/price for the user.",
            listOf("Search the product", "Filter/sort by price or rating", "Open top 2-3 results", "Extract prices"),
            "Price verified with currency and units; seller and availability noted.",
            "If a page requires login or payment, ask_user — never transact without confirmation.", "NEVER complete a purchase. Stop at the confirmation step and use ask_user."),
        Skill("travel", "Travel", "✈", listOf("hotel", "flight", "travel", "book", "train", "bus", "cheapest hotel"),
            "Find travel options matching the user's constraints.",
            listOf("Search with location + date", "Sort/filter by price", "Compare top options"),
            "Dates, location, and price extracted verbatim.",
            "Never complete a booking; present options and ask_user.", "NEVER book or pay. Present findings and stop at confirmation."),
        Skill("forms", "Forms", "📝", listOf("form", "register", "signup", "fill", "submit form"),
            "Locate and fill the requested form with user-provided values.",
            listOf("Identify all fields", "Fill only user-provided values", "Ask for missing values"),
            "All required fields filled; values echoed back before submit.",
            "Never invent personal data; ask_user for missing values.", "Never submit the form without explicit user confirmation."),
        Skill("comparison", "Comparison", "📊", listOf("compare", "versus", "vs", "which is better"),
            "Compare the requested items and produce a structured comparison.",
            listOf("Visit each item's page", "Extract comparable attributes", "Summarize in a table"),
            "Comparison covers every requested item with sources.",
            "If an item page fails, note it and continue with the rest.", ""),
        Skill("extraction", "Extraction", "🗂", listOf("collect", "extract", "table", "scrape", "list"),
            "Collect the requested information from pages into structured output.",
            listOf("Identify the repeating structure", "Extract row by row", "Output as table"),
            "All requested rows collected; columns consistent.",
            "For lazy-loading pages, scroll before extracting.", ""),
        Skill("downloads", "Downloads", "⬇", listOf("download", "save file", "get the file"),
            "Locate and download the requested file.",
            listOf("Find the download link", "Confirm file type with user if executable"),
            "Download started via the browser's download manager.",
            "Executable downloads require user confirmation.", ""),
        Skill("productivity", "Productivity", "⚙", listOf("email", "calendar", "document", "draft"),
            "Assist with the requested productivity task in the user's authenticated session.",
            listOf("Navigate to the service", "Use existing session; never ask for passwords in chat"),
            "Drafts prepared but not sent without confirmation.",
            "Sending/creating/deleting requires user confirmation.", "Never send messages or delete data without explicit confirmation.")
    )
}
