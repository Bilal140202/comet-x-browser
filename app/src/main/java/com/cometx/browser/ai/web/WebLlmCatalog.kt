package com.cometx.browser.ai.web

/**
 * WebLlmCatalog (v2.1.0) — the in-browser transformer model zoo.
 *
 * These are the SAME ONNX exports the Transformers.js ecosystem ships: int4
 * (q4) weights with int8/fp32 compute, which the WebAssembly CPU backend
 * executes reliably on every evergreen WebView (no WebGPU requirement).
 * Repos were verified against the Hugging Face hub API on 2026-09-19
 * (library_name=transformers.js or onnx/ exports with model_q4.onnx).
 *
 * Sizes are the hub-reported byte counts of onnx/model_q4.onnx — shown to the
 * user BEFORE any download, never adjusted, never rounded down. The 1.5B
 * model is kept in the list because it is real and works, but it is flagged
 * for high-RAM devices (a ~1.8 GB weight file inside a WebView renderer).
 */
object WebLlmCatalog {

    data class WebModel(
        val id: String,          // HF repo id — also the provider model id
        val label: String,       // human name shown in Settings
        val dtype: String,       // transformers.js dtype passed to pipeline()
        val sizeBytes: Long,     // onnx/model_q4.onnx size, hub-reported
        val blurb: String,       // one honest line about speed/quality
        val minDeviceRamGb: Int, // guidance gate shown in UI (advisory)
    ) {
        /** Decimal MB (hub-reported convention), matches the Settings display. */
        val sizeMb: Long get() = sizeBytes / 1_000_000L
    }

    val SMOLLM_360M = WebModel(
        id = "HuggingFaceTB/SmolLM2-360M-Instruct",
        label = "SmolLM2 360M Instruct",
        dtype = "q4",
        sizeBytes = 387_900_000L,
        blurb = "Fastest · lightest; decent short answers, weakest at strict JSON",
        minDeviceRamGb = 3,
    )

    val QWEN_05B = WebModel(
        id = "onnx-community/Qwen2.5-0.5B-Instruct",
        label = "Qwen2.5 0.5B Instruct",
        dtype = "q4",
        sizeBytes = 786_200_000L,
        blurb = "Recommended · best agent-JSON quality at usable speed",
        minDeviceRamGb = 4,
    )

    val QWEN_15B = WebModel(
        id = "onnx-community/Qwen2.5-1.5B-Instruct",
        label = "Qwen2.5 1.5B Instruct",
        dtype = "q4",
        sizeBytes = 1_787_600_000L,
        blurb = "Most capable · slowest; needs a high-RAM device (8 GB+)",
        minDeviceRamGb = 8,
    )

    val MODELS: List<WebModel> = listOf(SMOLLM_360M, QWEN_05B, QWEN_15B)

    val DEFAULT_MODEL_ID: String = QWEN_05B.id

    fun byIdOrNull(id: String?): WebModel? = MODELS.firstOrNull { it.id == id }

    fun defaultModel(): WebModel = QWEN_05B
}
