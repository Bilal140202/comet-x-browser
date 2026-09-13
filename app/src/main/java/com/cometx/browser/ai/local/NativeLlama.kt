package com.cometx.browser.ai.local

import com.cometx.browser.util.Logx

/**
 * Live generation progress pushed from the native decode loop. Top-level (NOT
 * nested in [LlamaBridge]) so JVM tests can construct listeners without
 * triggering the native library load in LlamaBridge's static initializer.
 * The JNI lookup binds by method signature onProgress(IIII[B)V, so the
 * interface's declaring class is irrelevant to the native side.
 */
fun interface GenProgressListener {
    /**
     * Called on the thread that invoked [NativeLlama.complete].
     * @param phase 0 = reading/prompt-eval, 1 = writing/decoding
     * @param promptDone prompt tokens processed so far
     * @param promptTotal prompt tokens total
     * @param outTokens tokens generated so far
     * @param partial UTF-8 bytes of the text generated so far (null-safe)
     */
    fun onProgress(phase: Int, promptDone: Int, promptTotal: Int, outTokens: Int, partial: ByteArray?)
}

/**
 * Testable seam over the JNI boundary. Everything in the local package talks to
 * this interface, never to [LlamaBridge] directly, so provider/manager logic is
 * fully unit-testable on the JVM with fake engines.
 *
 * The production instance ([RealLlama]) degrades gracefully when the native
 * library cannot be loaded (x86 emulators, unsupported ABIs): [available] is
 * simply false and the rest of the app treats on-device AI as absent — the
 * provider drops out of the router chain with zero behavioral change.
 */
interface NativeLlama {
    /** False when libcometx_llama.so could not be loaded on this device. */
    val available: Boolean

    fun load(path: String, contextSize: Int, threads: Int): Boolean
    fun isLoaded(): Boolean
    fun contextSize(): Int
    fun countTokens(text: String): Int
    fun complete(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        listener: GenProgressListener?,
        stopSequences: List<String>,
    ): String?
    fun cancel()
    fun free()
}

/** Production engine: thin delegation to [LlamaBridge], safe class-load. */
object RealLlama : NativeLlama {

    override val available: Boolean = try {
        // Touching LlamaBridge triggers its static System.loadLibrary. On
        // devices without the arm64 .so this throws UnsatisfiedLinkError —
        // caught here so the app never crashes over an optional feature.
        Class.forName(LlamaBridge::class.java.name)
        true
    } catch (t: Throwable) {
        Logx.w("on-device AI unavailable on this device: ${t.message}")
        false
    }

    override fun load(path: String, contextSize: Int, threads: Int): Boolean =
        if (!available) false else LlamaBridge.nativeLoadModel(path, contextSize, threads)

    override fun isLoaded(): Boolean = available && LlamaBridge.nativeIsLoaded()

    override fun contextSize(): Int = if (!available) 0 else LlamaBridge.nativeContextSize()

    override fun countTokens(text: String): Int =
        if (!available) -1 else LlamaBridge.nativeCountTokens(text)

    override fun complete(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        listener: GenProgressListener?,
        stopSequences: List<String>,
    ): String? {
        if (!available) return null
        val bytes = LlamaBridge.nativeComplete(
            prompt, maxTokens, temperature, listener, stopSequences.toTypedArray()
        )
        return bytes?.let { String(it, Charsets.UTF_8) }
    }

    override fun cancel() {
        if (available) LlamaBridge.nativeCancel()
    }

    override fun free() {
        if (available) LlamaBridge.nativeFree()
    }
}
