package com.cometx.browser.ai.local

/**
 * JNI boundary to the native llama.cpp runtime (libcometx_llama.so).
 * Kept minimal and isolated: this class is the ONLY native-aware member of the
 * local package, and proguard-rules.pro keeps it unobfuscated so the native
 * GetMethodID lookups always resolve (see JARVIS v1.5.0 minification lesson).
 *
 * NEVER call these methods from a JVM unit test — use [NativeLlama] fakes.
 */
object LlamaBridge {
    init {
        System.loadLibrary("cometx_llama")
    }

    /**
     * Progress callback — see [GenProgressListener]. Nested-free type: the
     * native side binds by method signature only.
     */

    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    external fun nativeIsLoaded(): Boolean
    external fun nativeContextSize(): Int

    /** Token count for the given text, or -1 when no model is loaded. */
    external fun nativeCountTokens(text: String): Int

    /** One completion. [stopSequences] cut generation natively (tokens/battery saved). */
    external fun nativeComplete(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        listener: GenProgressListener?,
        stopSequences: Array<String>,
    ): ByteArray?

    external fun nativeCancel()
    external fun nativeFree()
}
