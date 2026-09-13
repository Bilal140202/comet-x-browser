package com.cometx.browser.ai.local

/**
 * Curated on-device model catalog. URLs and SHA-256 checksums are real values
 * taken from the HuggingFace Hub API and verified end-to-end by the JARVIS
 * reference implementation (same files, same hashes). Models are downloaded
 * separately from the APK and verified before activation — nothing ships in
 * the APK, nothing runs without explicit user consent.
 */
object LocalModelCatalog {

    enum class ChatTemplate { CHATML, LLAMA3, PLAIN }

    data class CatalogModel(
        val id: String,
        val repo: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val params: String,
        val quant: String,
        val contextTrain: Int,
        val ramNeededGb: Double,
        val minDeviceClass: DeviceClass,
        val template: ChatTemplate,
        val strengths: String,
    ) {
        val sizeMb: Int get() = (sizeBytes / (1024 * 1024)).toInt()
        val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
        val label: String get() = "$params · $quant"
    }

    enum class DeviceClass { BASIC, STANDARD, POWER }

    val MODELS = listOf(
        CatalogModel(
            id = "smollm2-360m",
            repo = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
            fileName = "smollm2-360m-instruct-q8_0.gguf",
            sizeBytes = 386404992,
            sha256 = "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201",
            params = "0.36B",
            quant = "Q8_0",
            contextTrain = 8192,
            ramNeededGb = 0.8,
            minDeviceClass = DeviceClass.BASIC,
            template = ChatTemplate.CHATML,
            strengths = "Ultra-fast: highest tokens/sec of the catalog, quick prefill; best on entry-level phones or for simple goals.",
        ),
        CatalogModel(
            id = "qwen25-05b",
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 491400032,
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            params = "0.5B",
            quant = "Q4_K_M",
            contextTrain = 32768,
            ramNeededGb = 1.0,
            minDeviceClass = DeviceClass.BASIC,
            template = ChatTemplate.CHATML,
            strengths = "Fastest with reliable JSON output for its size; ideal on low-RAM phones.",
        ),
        CatalogModel(
            id = "llama32-1b",
            repo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807694464,
            sha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            params = "1B",
            quant = "Q4_K_M",
            contextTrain = 131072,
            ramNeededGb = 1.7,
            minDeviceClass = DeviceClass.BASIC,
            template = ChatTemplate.LLAMA3,
            strengths = "Speed sweet spot: strong instruction following and reliable JSON output.",
        ),
        CatalogModel(
            id = "smollm2-17b",
            repo = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            sizeBytes = 1055609536,
            sha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33",
            params = "1.7B",
            quant = "Q4_K_M",
            contextTrain = 8192,
            ramNeededGb = 2.2,
            minDeviceClass = DeviceClass.STANDARD,
            template = ChatTemplate.CHATML,
            strengths = "Best speed/quality balance on mid-range devices; strong instruction following.",
        ),
        CatalogModel(
            id = "qwen25-15b",
            repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1117320736,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            params = "1.5B",
            quant = "Q4_K_M",
            contextTrain = 32768,
            ramNeededGb = 2.4,
            minDeviceClass = DeviceClass.STANDARD,
            template = ChatTemplate.CHATML,
            strengths = "Very good single-action planner with long context support.",
        ),
        CatalogModel(
            id = "llama32-3b",
            repo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2019377696,
            sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            params = "3B",
            quant = "Q4_K_M",
            contextTrain = 131072,
            ramNeededGb = 3.6,
            minDeviceClass = DeviceClass.POWER,
            template = ChatTemplate.LLAMA3,
            strengths = "Stronger reasoning for multi-step goals and recovery decisions.",
        ),
        CatalogModel(
            id = "qwen25-3b",
            repo = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2104932768,
            sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            params = "3B",
            quant = "Q4_K_M",
            contextTrain = 32768,
            ramNeededGb = 3.8,
            minDeviceClass = DeviceClass.POWER,
            template = ChatTemplate.CHATML,
            strengths = "Best action-JSON reliability in the 3B class; long context.",
        ),
    )

    fun byId(id: String?): CatalogModel? = MODELS.firstOrNull { it.id == id }

    /**
     * Best model this device can actually run: largest usable entry by RAM
     * (45% of total RAM as the safe ceiling for a background browser process),
     * preferring Qwen at equal size for its JSON reliability. Never null —
     * falls back to the smallest catalog entry.
     */
    fun recommendFor(totalRamGb: Double, cores: Int): CatalogModel {
        val cls = deviceClass(totalRamGb, cores)
        val usable = MODELS.filter {
            it.minDeviceClass <= cls && it.ramNeededGb <= totalRamGb * 0.45
        }
        return usable.maxByOrNull { it.ramNeededGb * 10 + if (it.id.startsWith("qwen")) 0.5 else 0.0 }
            ?: MODELS.minByOrNull { it.ramNeededGb }!!
    }

    fun deviceClass(totalRamGb: Double, cores: Int): DeviceClass = when {
        totalRamGb >= 8 && cores >= 8 -> DeviceClass.POWER
        totalRamGb >= 6 -> DeviceClass.POWER
        totalRamGb >= 4 -> DeviceClass.STANDARD
        else -> DeviceClass.BASIC
    }

    /** Next lighter entry — the fallback rung when a model fails to load. */
    fun lighterThan(m: CatalogModel): CatalogModel? =
        MODELS.filter { it.ramNeededGb < m.ramNeededGb }.maxByOrNull { it.ramNeededGb }
}
