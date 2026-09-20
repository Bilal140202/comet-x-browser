package com.cometx.browser.ai

/**
 * KeyFormat (v2.2.0) — per-provider API key shape validation + display masking.
 *
 * Pure JVM (no Android imports) so the Compose Cloud AI screen and unit tests
 * share one source of truth. The regexes are HINTS, not gates: an unexpected
 * provider-side key rename must never brick a working configuration, so the
 * UI warns on mismatch but still saves. Shape references used for the two
 * verified providers:
 *   NVIDIA NIM   nvapi-…                        (live key verified 2026-09-20)
 *   OpenRouter   sk-or-v1-<64 hex>              (live key verified 2026-09-20)
 */
object KeyFormat {

    /** Returns null when the key matches the provider's known shape, else a warning line. */
    fun warning(providerId: String, key: String): String? {
        val k = key.trim()
        if (k.isEmpty()) return null
        return when (providerId) {
            "nvidia" -> if (Regex("^nvapi-[A-Za-z0-9_-]{20,}$").containsMatchIn(k)) null
                else "NVIDIA NIM keys look like nvapi-… — double-check the key was copied in full"
            "openrouter" -> if (Regex("^sk-or-v1-[0-9a-f]{64}$").containsMatchIn(k)) null
                else "OpenRouter keys look like sk-or-v1-… (64 hex characters)"
            "groq" -> if (Regex("^gsk_[A-Za-z0-9]{20,}$").containsMatchIn(k)) null
                else "Groq keys usually look like gsk_…"
            "huggingface" -> if (Regex("^hf_[A-Za-z0-9]{20,}$").containsMatchIn(k)) null
                else "Hugging Face tokens usually look like hf_…"
            else -> if (k.length >= 8) null else "That looks too short for an API key"
        }
    }

    /**
     * Stable, non-secret fingerprint for UI display: keeps a short prefix and
     * the last 4 characters. Never returns more than 12 characters of the key.
     */
    fun mask(key: String): String {
        val k = key.trim()
        if (k.isEmpty()) return ""
        if (k.length <= 12) return "•".repeat(k.length)
        val head = k.take(8)
        val tail = k.takeLast(4)
        return head + "••••" + tail
    }
}
