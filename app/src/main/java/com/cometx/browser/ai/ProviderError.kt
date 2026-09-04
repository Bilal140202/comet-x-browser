package com.cometx.browser.ai

/**
 * Normalized provider error taxonomy (Phase 2 §15). Provider-specific failures
 * (Groq 400 "does not support JSON", OpenRouter 402 "needs credits", a 404 for
 * a retired model …) are translated here ONCE so every layer above — router,
 * engine, UI — reacts to a stable vocabulary instead of raw strings.
 */
enum class ProviderErrorKind {
    INVALID_API_KEY,
    MODEL_NOT_FOUND,
    MODEL_UNAVAILABLE,
    RATE_LIMIT,
    CONTEXT_TOO_LARGE,
    UNSUPPORTED_RESPONSE_FORMAT,
    UNSUPPORTED_TOOL_CALLING,
    VISION_UNSUPPORTED,
    NETWORK_ERROR,
    PROVIDER_ERROR,
    UNKNOWN
}

/**
 * Single place that maps (HTTP code + provider message text) → [ProviderErrorKind].
 * Message matching is deliberately case-insensitive and tolerant across providers.
 */
object ProviderErrorClassifier {

    private data class Rule(val kind: ProviderErrorKind, val pattern: Regex)

    private val messageRules = listOf(
        // --- response-format capability errors (Groq / OpenAI-compatible) ---
        Rule(ProviderErrorKind.UNSUPPORTED_RESPONSE_FORMAT,
            Regex("""response.?format|json(_|\s)?(schema|mode|object)|structured.?output|does not support (json|structured)""", RegexOption.IGNORE_CASE)),
        Rule(ProviderErrorKind.UNSUPPORTED_RESPONSE_FORMAT,
            Regex("""(not|doesn'?t|does not) support (the )?(json|response|structured)""", RegexOption.IGNORE_CASE)),
        // --- tool calling capability errors ---
        Rule(ProviderErrorKind.UNSUPPORTED_TOOL_CALLING,
            Regex("""tool(s|_choice)? (.*)not supported|does not support (function|tool)|tools? parameter""", RegexOption.IGNORE_CASE)),
        // --- vision errors ---
        Rule(ProviderErrorKind.VISION_UNSUPPORTED,
            Regex("""image(s)? (.*)not supported|does not support (image|vision|multimodal)|image_url""", RegexOption.IGNORE_CASE)),
        // --- context overflow ---
        Rule(ProviderErrorKind.CONTEXT_TOO_LARGE,
            Regex("""context.?length|maximum context|too (large|long|many tokens)|reduce the (length|size)|token limit|input length""", RegexOption.IGNORE_CASE)),
        // --- model identity ---
        Rule(ProviderErrorKind.MODEL_NOT_FOUND,
            Regex("""model (.*)?(not|does not) (exist|found|available)|decommissioned|unknown model|no such model""", RegexOption.IGNORE_CASE)),
        Rule(ProviderErrorKind.MODEL_UNAVAILABLE,
            Regex("""model.*(overloaded|unavailable|loading|currently unavailable|being (loaded|deployed))""", RegexOption.IGNORE_CASE)),
        // --- quota / rate limit ---
        Rule(ProviderErrorKind.RATE_LIMIT,
            Regex("""rate.?limit|quota|too many requests|usage.?limit|limit reached""", RegexOption.IGNORE_CASE)),
        // --- credits (OpenRouter: paid model on a free-only wallet) ---
        Rule(ProviderErrorKind.RATE_LIMIT,
            Regex("""credit|insufficient funds|payment required|requires more credits|billing""", RegexOption.IGNORE_CASE)),
        // --- auth ---
        Rule(ProviderErrorKind.INVALID_API_KEY,
            Regex("""invalid api key|invalid_api_key|authentication|unauthorized|forbidden|api key (expired|revoked|invalid)""", RegexOption.IGNORE_CASE))
    )

    /**
     * Classify an HTTP failure. Status codes take precedence only when the
     * message is unhelpful; capability errors are detected from the message
     * first because a 400 can mean a dozen different things.
     */
    fun classify(httpCode: Int, message: String): ProviderErrorKind {
        val m = message ?: ""
        for (rule in messageRules) {
            if (rule.pattern.containsMatchIn(m)) return rule.kind
        }
        return when (httpCode) {
            401, 403 -> ProviderErrorKind.INVALID_API_KEY
            402 -> ProviderErrorKind.RATE_LIMIT          // OpenRouter: out of credits → treat as quota
            404 -> ProviderErrorKind.MODEL_NOT_FOUND
            408, 504 -> ProviderErrorKind.NETWORK_ERROR
            413 -> ProviderErrorKind.CONTEXT_TOO_LARGE
            429 -> ProviderErrorKind.RATE_LIMIT
            422, 400 -> ProviderErrorKind.UNKNOWN        // ambiguous 4xx: message rules failed to match
            500, 502, 503, 529 -> ProviderErrorKind.PROVIDER_ERROR
            in 500..599 -> ProviderErrorKind.PROVIDER_ERROR
            -1 -> ProviderErrorKind.NETWORK_ERROR        // Http layer: exception, no response
            else -> ProviderErrorKind.UNKNOWN
        }
    }

    /** True when retrying the SAME request on the SAME model could succeed. */
    fun retryableSameTarget(kind: ProviderErrorKind): Boolean = when (kind) {
        ProviderErrorKind.NETWORK_ERROR,
        ProviderErrorKind.PROVIDER_ERROR,
        ProviderErrorKind.MODEL_UNAVAILABLE -> true
        else -> false
    }
}

/**
 * Provider transport/API failure carrying its normalized classification so
 * callers never re-parse error strings.
 */
class ProviderException(
    message: String,
    val httpCode: Int = -1,
    val kind: ProviderErrorKind = ProviderErrorClassifier.classify(httpCode, message ?: "")
) : Exception(message)

/** Thrown when the model answered (HTTP 200) but no interpreter could extract a decision. */
class UnparseableOutputException(message: String) : Exception(message)

/** Thrown when the observation is too large for the model's context window (§19). */
class ContextTooLargeException(message: String) : Exception(message)

/**
 * Thrown when the resolved candidate cannot read images (Phase 3, expert
 * review P1-14): surfaced to the engine so it can retry the step with
 * visionB64=null instead of burning every candidate in the chain.
 */
class VisionUnsupportedException(message: String) : Exception(message)
