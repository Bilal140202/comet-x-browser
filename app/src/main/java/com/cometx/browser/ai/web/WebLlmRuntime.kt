package com.cometx.browser.ai.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.util.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Snapshot of the runtime's load/health state for the Settings UI. */
data class WebLoadProgress(
    val model: String? = null,
    val phase: String = "idle",      // idle | booting | loading | warming | loaded | error | reset
    val pct: Double = 0.0,
    val loadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val file: String? = null,
    val message: String? = null,
)

/** Raised by the runtime for boot/load/generate failures. */
class WebLlmException(message: String) : Exception(message)

/**
 * The engine contract [TransformersWebProvider] codes against. The real
 * implementation hosts a headless WebView running Transformers.js; tests use
 * a fake. All methods are safe to call from any thread.
 */
interface WebLlmEngine {
    /** Cheap readiness signal: false only after an unrecovered renderer loss. */
    val healthy: Boolean

    /** Loads the runtime page once; subsequent calls are no-ops. */
    suspend fun ensureStarted()

    /** Makes [model] the resident pipeline (cached fast-path when resident). */
    suspend fun load(model: String, dtype: String)

    /**
     * Runs one completion. [onStream] receives incremental chunks (may be
     * invoked from the WebView bridge thread). Returns the final text.
     */
    suspend fun generate(
        sid: Long,
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Double,
        onStream: (String) -> Unit,
    ): String

    /** Cooperative stop for the in-flight generation [sid]. */
    fun interrupt(sid: Long)

    /** Destroys the renderer (frees WASM memory); weights stay in Cache Storage. */
    fun release()

    val progress: StateFlow<WebLoadProgress>
}

/**
 * WebLlmRuntime (v2.1.0) — hosts Transformers.js inside an app-owned headless
 * WebView. This is the "transformers web llm" made real: the SAME runtime and
 * model zoo as the Transformers.js ecosystem, executed by the browser engine
 * the app already ships, with weights persisted in that origin's Cache
 * Storage (download once, run offline forever after).
 *
 * Why a WebView and not a native ONNX binding: the JS runtime is the
 * upstream-battle-tested engine (tokenizer, generation loop, KV cache,
 * caching, progress) with zero native code to maintain — and its failure
 * modes are contained: the renderer can crash without taking the app down
 * (onRenderProcessGone → graceful task failure, the v1.8.0 posture).
 *
 * Security posture (matches BrowserController):
 *  - JS on, file/content access OFF, no permissive CORS tricks
 *  - page is served ONLY from https://appassets.androidplatform.net/assets/
 *    via WebViewAssetLoader (secure origin → Cache Storage legal)
 *  - COOP/COEP headers are injected on OUR page only — cross-origin isolation
 *    unlocks SharedArrayBuffer so ORT Web can use worker threads; arbitrary
 *    web pages are never touched by this client
 *  - the bridge surface is one @JavascriptInterface method that only parses
 *    JSON (total parser — malformed input can never throw)
 */
class WebLlmRuntime(private val appContext: Context) : WebLlmEngine {

    companion object {
        private const val TAG = "WebLlm"
        const val PAGE_URL = "https://appassets.androidplatform.net/assets/webllm/index.html"
        private const val BOOT_TIMEOUT_MS = 30_000L                 // 30 s
        private const val LOAD_TIMEOUT_MS = 30L * 60_000            // 30 min (a 1.8 GB weight file on slow Wi-Fi)
        private const val GENERATE_TIMEOUT_MS = 15L * 60_000        // 15 min (512 tokens on slow WASM)
        private const val IDLE_RELEASE_MS = 10L * 60_000            // 10 min idle → free the renderer
    }

    override val healthy: Boolean
        get() = broken.get().not()

    private val main = Handler(Looper.getMainLooper())
    private val broken = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val opMutex = Mutex()
    private val sidCounter = AtomicLong(0)

    override val progress = MutableStateFlow(WebLoadProgress())

    @Volatile private var webView: WebView? = null
    @Volatile private var bootGate: CompletableDeferred<Unit>? = null
    @Volatile private var loadGate: CompletableDeferred<Unit>? = null
    @Volatile private var residentModel: String? = null

    private class GenSession(
        val onStream: (String) -> Unit,
        val gate: CompletableDeferred<String>,
    )

    private val sessions = ConcurrentHashMap<Long, GenSession>()
    private val idleToken = AtomicLong(0)

    fun newSid(): Long = sidCounter.incrementAndGet()

    // ------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun ensureStarted() {
        if (started.get() && webView != null && healthy) return
        opMutex.withLock {
            if (started.get() && webView != null && healthy) return
            broken.set(false)
            val gate = CompletableDeferred<Unit>()
            bootGate = gate
            progress.value = progress.value.copy(phase = "booting")
            main.post {
                try {
                    if (webView == null) webView = buildWebView()
                    webView?.loadUrl(PAGE_URL)
                } catch (e: Exception) {
                    gate.completeExceptionally(WebLlmException("could not start in-browser AI: ${e.message}"))
                }
            }
            val ok = withTimeoutOrNull(BOOT_TIMEOUT_MS) { gate.await() }
            if (ok == null) {
                throw WebLlmException("in-browser AI runtime did not boot (timeout)")
            }
            started.set(true)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(appContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true          // Cache Storage / IndexedDB
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.visibility = android.view.View.GONE
        wv.webViewClient = object : WebViewClient() {
            private val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
                .build()

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val resp = assetLoader.shouldInterceptRequest(request.url) ?: return null
                // Cross-origin isolation for OUR origin only → SharedArrayBuffer
                // → multi-threaded WASM. Never applied to user-facing pages.
                val headers = resp.responseHeaders ?: mutableMapOf()
                headers["Cross-Origin-Opener-Policy"] = "same-origin"
                headers["Cross-Origin-Embedder-Policy"] = "require-corp"
                resp.responseHeaders = headers
                return resp
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Logx.e("web-llm renderer gone (crash=${detail.didCrash()}) — failing tasks gracefully")
                handleRendererGone()
                return true // consumed: the app must not be killed (v1.8.0 posture)
            }
        }
        wv.addJavascriptInterface(Bridge(), "CometXWebAI")
        return wv
    }

    private fun handleRendererGone() {
        broken.set(true)
        started.set(false)
        residentModel = null
        main.post {
            webView?.destroy()
            webView = null
        }
        bootGate?.completeExceptionally(WebLlmException("in-browser AI renderer was lost"))
        loadGate?.completeExceptionally(WebLlmException("in-browser AI renderer was lost"))
        bootGate = null
        loadGate = null
        for ((sid, session) in sessions) {
            sessions.remove(sid)
            session.gate.completeExceptionally(WebLlmException("in-browser AI renderer was lost"))
        }
        progress.value = WebLoadProgress(phase = "error", message = "renderer lost — will restart on next use")
    }

    override fun release() {
        main.post {
            webView?.destroy()
            webView = null
        }
        started.set(false)
        residentModel = null
        loadGate?.completeExceptionally(WebLlmException("runtime released"))
        loadGate = null
        progress.value = WebLoadProgress(phase = "idle")
    }

    /** Called after each completed generation; frees the renderer when idle. */
    fun scheduleIdleRelease() {
        val token = idleToken.incrementAndGet()
        main.postDelayed({
            if (idleToken.get() == token && sessions.isEmpty()) release()
        }, IDLE_RELEASE_MS)
    }

    private fun cancelIdleRelease() = idleToken.incrementAndGet()

    // ------------------------------------------------------------ operations

    override suspend fun load(model: String, dtype: String) {
        ensureStarted()
        if (residentModel == model) return
        cancelIdleRelease()
        opMutex.withLock {
            if (residentModel == model) return
            val gate = CompletableDeferred<Unit>()
            loadGate = gate
            progress.value = WebLoadProgress(model = model, phase = "loading")
            eval(WebLlmProtocol.loadCommand(model, dtype))
            val ok = withTimeoutOrNull(LOAD_TIMEOUT_MS) { gate.await() }
            if (ok == null) {
                throw WebLlmException("model load timed out after 30 min (network too slow or too little RAM)")
            }
            residentModel = model
        }
    }

    override suspend fun generate(
        sid: Long,
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Double,
        onStream: (String) -> Unit,
    ): String {
        ensureStarted()
        cancelIdleRelease()
        val session = GenSession(onStream, CompletableDeferred())
        sessions[sid] = session
        try {
            eval(WebLlmProtocol.generateCommand(sid, messages, maxTokens, temperature))
            val text = withTimeoutOrNull(GENERATE_TIMEOUT_MS) { session.gate.await() }
            if (text == null) {
                throw WebLlmException("generation timed out after 15 min")
            }
            scheduleIdleRelease()
            return text
        } finally {
            sessions.remove(sid)
        }
    }

    override fun interrupt(sid: Long) {
        eval(WebLlmProtocol.interruptCommand(sid))
    }

    private fun eval(js: String) {
        main.post {
            webView?.evaluateJavascript("window.__cometxAI && window.__cometxAI.handle($js);", null)
        }
    }

    // ------------------------------------------------------------ bridge

    private inner class Bridge {
        @JavascriptInterface
        fun post(raw: String) {
            onEvent(WebLlmProtocol.parseEvent(raw))
        }
    }

    /** Runs on the WebView bridge thread. Total-parse contract: never throws. */
    fun onEvent(ev: WebEvent?) {
        when (ev) {
            null -> Unit // malformed payload — swallowed by design
            is WebEvent.Boot -> bootGate?.complete(Unit)
            is WebEvent.Log -> Logx.d(ev.msg)
            is WebEvent.Status -> {
                progress.value = WebLoadProgress(
                    model = ev.model ?: progress.value.model,
                    phase = ev.phase,
                    pct = ev.pct,
                    loadedBytes = ev.loadedBytes,
                    totalBytes = ev.totalBytes,
                    file = ev.file,
                )
            }
            is WebEvent.Ready -> {
                progress.value = progress.value.copy(phase = "loaded", pct = 100.0)
                loadGate?.complete(Unit)
                loadGate = null
            }
            is WebEvent.Stream -> sessions[ev.sid]?.onStream?.invoke(ev.text)
            is WebEvent.Done -> {
                sessions.remove(ev.sid)?.gate?.complete(ev.text)
            }
            is WebEvent.Failed -> {
                val ex = WebLlmException(ev.msg)
                val gate = ev.sid?.let { sessions.remove(it)?.gate }
                if (gate != null) gate.completeExceptionally(ex)
                else {
                    progress.value = WebLoadProgress(phase = "error", message = ev.msg)
                    loadGate?.completeExceptionally(ex)
                    loadGate = null
                }
            }
        }
    }
}
