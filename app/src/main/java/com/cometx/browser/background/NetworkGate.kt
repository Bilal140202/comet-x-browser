package com.cometx.browser.background

import com.cometx.browser.ai.ProviderErrorKind
import com.cometx.browser.ai.ProviderException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * NetworkWaitPolicy (v1.8.0) — decides whether a failure is a NETWORK
 * FLUCTUATION (flight mode, dead zone, Wi-Fi↔cell handoff, captive portal,
 * router reboot) versus a real error (bad key, full context, model refusal…).
 *
 * The user's contract (v1.8.0 BG): "if data fluctuations happen it should not
 * showcase app-is-stopping" — a fluctuation must look like "waiting for
 * network", NEVER like a failure and NEVER like a crash. Only genuinely
 * transient transport-level problems qualify here; HTTP responses (even 5xx)
 * are the SERVER answering and are handled by the router's own backoff.
 */
object NetworkWaitPolicy {

    /** Transient transport errors that warrant waiting for connectivity. */
    fun isTransientNetworkError(t: Throwable?): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is UnknownHostException ||     // DNS dead — classic dead-zone signature
                cur is ConnectException ||         // nothing listening / no route
                cur is SocketTimeoutException ||   // packets silently dropped
                cur is SSLException                // TLS handshake interrupted mid-flight
            ) return true
            if (cur is ProviderException && cur.kind == ProviderErrorKind.NETWORK_ERROR) return true
            if (cur is IOException && isMessageTransient(cur.message)) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /** Fallback message sniff for IOExceptions too generic to classify by type. */
    fun isMessageTransient(message: String?): Boolean {
        val m = (message ?: "").lowercase()
        return listOf(
            "failed to connect", "connection refused", "connection reset",
            "broken pipe", "network is unreachable", "network unreachable",
            "econnrefused", "econnreset", "ehostunreach", "enetunreach",
            "timeout", "timed out", "unable to resolve host", "no address associated"
        ).any { m.contains(it) }
    }
}

/**
 * NetworkWaiter — the runtime half of the gate: parks the caller while the
 * device has no usable INTERNET connection, up to [maxWaitMs].
 *
 * Injected availability-check + delay make the wait loop unit-testable with
 * zero real sockets. The service wires a ConnectivityManager-backed check.
 */
class NetworkWaiter(
    private val isNetworkAvailable: () -> Boolean,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    private val pollMs: Long = 2_000L
) {
    /**
     * Wait until the network is back or the budget expires.
     * @return true when the network IS available (caller may retry),
     *         false when the wait budget ran out (caller treats it as a
     *         normal failure — the same code path as v1.7.0).
     */
    suspend fun waitUntilAvailable(): Boolean {
        if (isNetworkAvailable()) return true
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            if (isNetworkAvailable()) return true
        }
        return isNetworkAvailable()
    }

    companion object {
        /** Long enough to survive a subway ride; short enough to fail honestly. */
        const val DEFAULT_MAX_WAIT_MS = 5L * 60 * 1000
    }
}
