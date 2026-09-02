package com.cometx.browser.ai

import com.cometx.browser.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Injectable HTTP boundary (Phase 2 §14 provider abstraction). Production uses
 * [REAL] (delegating to the zero-dependency Http layer); unit tests inject
 * scripted transports so the full discovery → negotiation → fallback ladder is
 * exercised against fixture catalogs with no network and no secrets.
 */
interface HttpTransport {
    suspend fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): Http.Response
    suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response

    companion object {
        val REAL: HttpTransport = object : HttpTransport {
            override suspend fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): Http.Response =
                withContext(Dispatchers.IO) { Http.postJson(url, body, headers, timeoutMs) }

            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response =
                withContext(Dispatchers.IO) { Http.get(url, headers, timeoutMs) }
        }
    }
}
