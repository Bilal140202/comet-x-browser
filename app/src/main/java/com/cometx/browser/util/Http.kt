package com.cometx.browser.util

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Minimal HTTP layer built on HttpURLConnection (zero extra deps). */
object Http {

    data class Response(val code: Int, val body: String, val error: String?) {
        val ok: Boolean get() = code in 200..299
    }

    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 90_000
    ): Response = request("POST", url, body, headers + mapOf("Content-Type" to "application/json"), timeoutMs)

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30_000
    ): Response = request("GET", url, null, headers, timeoutMs)

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Int
    ): Response {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = timeoutMs
                doInput = true
                if (body != null) doOutput = true
                for ((k, v) in headers) setRequestProperty(k, v)
                setRequestProperty("Accept", "application/json")
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Response(code, text, if (code in 200..299) null else text.take(500))
        } catch (e: Exception) {
            Response(-1, "", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
