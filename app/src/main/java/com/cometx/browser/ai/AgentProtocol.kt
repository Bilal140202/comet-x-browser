package com.cometx.browser.ai

/**
 * The structured-output ladder (Phase 2 §5). Every agent turn is produced
 * through the best protocol the resolved model actually supports; when the
 * model rejects one, the router downgrades silently to the next.
 *
 * BEST  → JSON_SCHEMA (strict structured outputs)
 *       → JSON_OBJECT (json mode)
 *       → TOOL_CALLING (single no-args decision tool)
 *       → TAGGED_TEXT (KEY=VALUE block protocol)
 *       → PLAIN_TEXT (tolerant line parser; never executes free prose)
 */
enum class AgentProtocol(val rank: Int, val label: String) {
    JSON_SCHEMA(0, "JSON Schema"),
    JSON_OBJECT(1, "JSON mode"),
    TOOL_CALLING(2, "Tool calling"),
    TAGGED_TEXT(3, "Tagged text"),
    PLAIN_TEXT(4, "Plain text");

    /** The next rung down the ladder, or null when already at the floor. */
    fun downgrade(): AgentProtocol? = entries.firstOrNull { it.rank == this.rank + 1 }

    companion object {

        /** Best protocol given claimed capabilities (metadata + probes). */
        fun bestFor(caps: Set<Capability>): AgentProtocol = when {
            caps.contains(Capability.JSON_SCHEMA) -> JSON_SCHEMA
            caps.contains(Capability.JSON_OBJECT) -> JSON_OBJECT
            caps.contains(Capability.TOOL_CALLING) -> TOOL_CALLING
            else -> TAGGED_TEXT
        }

        fun fromNameOrNull(name: String?): AgentProtocol? =
            name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}
