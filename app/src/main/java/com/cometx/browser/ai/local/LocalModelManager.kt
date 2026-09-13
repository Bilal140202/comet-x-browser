package com.cometx.browser.ai.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * On-device model manager (v1.6.0): discover (catalog) / download (resumable)
 * / pause / resume / cancel / verify (SHA-256) / import / delete / select /
 * load (eager or on-demand) / unload (idle watchdog) / test.
 *
 * Concurrency invariants (the JARVIS v1.3.0 use-after-free class):
 *  - all native model swaps go through [loadLock]; a load NEVER starts while
 *    a completion is decoding (would free the model under the decoder)
 *  - the idle watchdog never frees while [provider.isGenerating]
 *  - every load outcome is reported via [loadState] so the UI can react
 */
class LocalModelManager(
    private val context: Context,
    private val engine: NativeLlama = RealLlama,
    private val settings: com.cometx.browser.ai.SettingsRepository =
        com.cometx.browser.ai.SettingsRepository(context, com.cometx.browser.security.SecureStore(context)),
) {

    // ---------------------------------------------------------------- state

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(val downloaded: Long, val total: Long, val speedKbs: Long) : DownloadState()
        data class Verifying(val downloaded: Long, val total: Long) : DownloadState()
        data object Done : DownloadState()
        data class Failed(val reason: String) : DownloadState()
    }

    sealed class LoadState {
        data object Idle : LoadState()
        data class Loading(val modelId: String) : LoadState()
        data class Loaded(val modelId: String) : LoadState()
        data class Failed(val modelId: String?, val reason: String) : LoadState()
        val loadedModelId: String? get() = (this as? Loaded)?.modelId
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val pauseFlags = HashMap<String, AtomicBoolean>()
    private val loadLock = ReentrantLock()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState

    val provider: LocalLlamaProvider = LocalLlamaProvider(
        engine,
        resolveSelected = { resolveSelected() },
        loadSync = { m, f -> loadSync(m, f) },
        contextSize = { effectiveContext() },
    )

    // ---------------------------------------------------------------- files

    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(m: LocalModelCatalog.CatalogModel): File = File(modelsDir(), m.fileName)

    fun isDownloaded(m: LocalModelCatalog.CatalogModel): Boolean =
        fileFor(m).let { it.exists() && it.length() == m.sizeBytes }

    /** User-imported GGUFs as activatable entries (no pinned checksum, conservative context). */
    fun importedModels(): List<LocalModelCatalog.CatalogModel> =
        modelsDir().listFiles { f -> f.extension.equals("gguf", true) }?.toList().orEmpty()
            .filter { it.length() > 0 }
            .map { f ->
                LocalModelCatalog.CatalogModel(
                    id = "imported:${f.name}",
                    repo = "local import",
                    fileName = f.name,
                    sizeBytes = f.length(),
                    sha256 = "",
                    params = "?",
                    quant = "imported",
                    contextTrain = 4096,
                    ramNeededGb = f.length() / 1024.0 / 1024.0 / 1024.0 * 1.3 + 0.5,
                    minDeviceClass = LocalModelCatalog.DeviceClass.BASIC,
                    template = LocalModelCatalog.ChatTemplate.PLAIN,
                    strengths = "User-imported GGUF model stored on this device.",
                )
            }

    fun allModels(): List<LocalModelCatalog.CatalogModel> = LocalModelCatalog.MODELS + importedModels()

    fun byId(id: String?): LocalModelCatalog.CatalogModel? =
        LocalModelCatalog.byId(id) ?: importedModels().firstOrNull { it.id == id }

    /** The selected model + its on-disk file, or null (drives cheap isReady). */
    fun resolveSelected(): Pair<LocalModelCatalog.CatalogModel, File>? {
        val m = byId(settings.localModelId()) ?: return null
        val f = fileFor(m)
        return if (f.exists() && f.length() > 0) m to f else null
    }

    fun isModelActive(m: LocalModelCatalog.CatalogModel): Boolean =
        _loadState.value.loadedModelId == m.id || provider.activeModel?.id == m.id

    fun isLoading(): Boolean = _loadState.value is LoadState.Loading

    fun nativeAvailable(): Boolean = engine.available

    fun effectiveContext(): Int = settings.localContext().coerceIn(1024, 4096)

    /** Device summary for the settings UI: "6.8 GB RAM · 8 cores · recommend: qwen25-15b". */
    fun deviceSummary(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ramGb = mem.totalMem / 1e9
        val cores = Runtime.getRuntime().availableProcessors()
        val rec = LocalModelCatalog.recommendFor(ramGb, cores)
        return String.format("%.1f GB RAM · %d cores · suggested: %s (%s)", ramGb, cores, rec.id, rec.label)
    }

    fun thermalDegraded(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= 30) pm.currentThermalStatus >= 3 else false
    }

    // ---------------------------------------------------------------- load

    /**
     * User-initiated activation (Settings). Verifies checksum (pinned entries),
     * loads on IO, persists the selection. Honest failures via [LoadState.Failed].
     */
    suspend fun selectAndLoad(m: LocalModelCatalog.CatalogModel): Boolean = withContext(Dispatchers.IO) {
        if (!engine.available) {
            _loadState.value = LoadState.Failed(m.id, "This device cannot run on-device AI (no arm64 native runtime).")
            return@withContext false
        }
        loadLock.withLock {
            if (_loadState.value is LoadState.Loading) {
                Logx.w("local: load already in progress — ignoring request for ${m.id}")
                return@withLock false
            }
            if (provider.isGenerating()) {
                Logx.w("local: activate ${m.id} refused — a task is using the model right now")
                _loadState.value = LoadState.Failed(
                    m.id, "A task is using the model right now. Stop the task (or wait for it), then activate.")
                return@withLock false
            }
            _loadState.value = LoadState.Loading(m.id)
            try {
                val file = fileFor(m)
                if (!file.exists() || file.length() == 0L) {
                    _loadState.value = LoadState.Failed(m.id, "Model file not found on device — download it first.")
                    return@withLock false
                }
                if (m.sha256.isNotBlank() && !verifyChecksum(file, m.sha256)) {
                    _states.value = _states.value + (m.id to DownloadState.Failed(
                        "Checksum mismatch — file may be corrupted. Delete and re-download."))
                    _loadState.value = LoadState.Failed(m.id, "Checksum mismatch — file may be corrupted. Delete and re-download.")
                    return@withLock false
                }
                val ok = doLoad(m, file)
                if (ok) {
                    settings.setLocalModelId(m.id)
                    _loadState.value = LoadState.Loaded(m.id)
                    Logx.i("local: activated ${m.id}")
                } else {
                    _loadState.value = LoadState.Failed(
                        m.id, "Could not load this model (not enough free RAM or unsupported file). Try a smaller model or a lower context size.")
                }
                ok
            } catch (t: Throwable) {
                Logx.e("local: activate ${m.id} crashed: ${t.message}", t)
                _loadState.value = LoadState.Failed(m.id, t.message ?: "Activation failed unexpectedly.")
                false
            }
        }
    }

    /** Blocking load used by the provider's on-demand path (fallback when cloud failed). */
    private fun loadSync(m: LocalModelCatalog.CatalogModel, file: File): Boolean {
        if (!engine.available) return false
        return loadLock.withLock {
            if (provider.isGenerating()) return@withLock false
            val ok = doLoad(m, file)
            if (ok) {
                settings.setLocalModelId(m.id)
                _loadState.value = LoadState.Loaded(m.id)
                Logx.i("local: on-demand loaded ${m.id}")
            } else {
                _loadState.value = LoadState.Failed(m.id, "On-demand load failed (RAM or file problem).")
            }
            ok
        }
    }

    private fun doLoad(m: LocalModelCatalog.CatalogModel, file: File): Boolean {
        val threads = settings.localThreads().takeIf { it > 0 } ?: detectPerfCores()
        val ok = engine.load(file.absolutePath, effectiveContext(), threads)
        if (ok) {
            provider.markLoaded(m)
        } else {
            provider.markUnloaded()
        }
        return ok
    }

    fun unload() {
        if (provider.isGenerating()) {
            Logx.w("local: unload requested while generating — cancelled inference, deferring free")
            provider.cancel()
            return
        }
        if (engine.isLoaded()) {
            engine.free()
            Logx.i("local: model unloaded")
        }
        provider.markUnloaded()
        if (_loadState.value !is LoadState.Loading) _loadState.value = LoadState.Idle
    }

    /** Auto-restore the last active model after process death (local-first mode only). */
    fun autoReloadIfPreferred() {
        scope.launch {
            runCatching {
                if (!engine.available || provider.isReady() || isLoading()) return@launch
                if (!settings.localAiPreferred()) return@launch
                val selected = resolveSelected() ?: return@launch
                Logx.i("local: auto-reloading last active model: ${selected.first.id}")
                selectAndLoad(selected.first)
            }.onFailure { Logx.w("local: auto-reload skipped: ${it.message}") }
        }
    }

    /** Idle unloader: RAM/battery management. Never frees under a live decode. */
    fun startIdleWatchdog() {
        if (!engine.available) return
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                runCatching {
                    val min = settings.localUnloadMin()
                    if (min <= 0) return@runCatching
                    if (provider.isGenerating()) return@runCatching
                    if (engine.isLoaded() &&
                        System.currentTimeMillis() - provider.lastUsedAt() > min * 60_000L
                    ) {
                        unload()
                        Logx.i("local: idle watchdog unloaded the model")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ download

    fun download(m: LocalModelCatalog.CatalogModel) {
        if (jobs[m.id]?.isActive == true) return
        val stat = StatFs(Environment.getDataDirectory().path)
        if (stat.availableBytes < m.sizeBytes + 200L * 1024 * 1024) {
            _states.value = _states.value + (m.id to DownloadState.Failed(
                "Not enough storage (${m.sizeMb} MB needed). Free up space and retry."))
            return
        }
        val pause = AtomicBoolean(false)
        pauseFlags[m.id] = pause
        jobs[m.id] = scope.launch {
            _states.value = _states.value + (m.id to DownloadState.Downloading(0, m.sizeBytes, 0))
            try {
                val target = fileFor(m)
                val partial = File(target.absolutePath + ".part")
                var downloaded = if (partial.exists()) partial.length() else 0L
                var lastTick = System.currentTimeMillis()
                var lastBytes = downloaded

                while (downloaded < m.sizeBytes) {
                    if (pause.get()) {
                        _states.value = _states.value + (m.id to DownloadState.Idle)
                        return@launch
                    }
                    val conn = URL(m.downloadUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val resumeSupported = code == 206
                    if (!resumeSupported) {
                        downloaded = 0
                        partial.outputStream().use { }
                    }

                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(partial, resumeSupported && downloaded > 0).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (pause.get()) return@launch
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastTick > 800) {
                                    val speed = ((downloaded - lastBytes) * 1000 / (now - lastTick).coerceAtLeast(1)) / 1024
                                    _states.value = _states.value + (m.id to DownloadState.Downloading(downloaded, m.sizeBytes, speed))
                                    lastTick = now
                                    lastBytes = downloaded
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    if (downloaded < m.sizeBytes) {
                        // Server closed early; loop to resume.
                        kotlinx.coroutines.delay(800)
                    }
                }

                _states.value = _states.value + (m.id to DownloadState.Verifying(m.sizeBytes, m.sizeBytes))
                partial.renameTo(target)
                if (!verifyChecksum(target, m.sha256)) {
                    target.delete()
                    throw IOException("SHA-256 verification failed — download corrupted, deleted")
                }
                _states.value = _states.value + (m.id to DownloadState.Done)
                Logx.i("local: downloaded+verified ${m.id}")
                // One-tap flow: activate right away; failures surface via loadState.
                selectAndLoad(m)
            } catch (t: Throwable) {
                Logx.e("local: download ${m.id} failed: ${t.message}")
                _states.value = _states.value + (m.id to DownloadState.Failed(t.message ?: "Download failed"))
            } finally {
                jobs.remove(m.id)
            }
        }
    }

    fun pause(m: LocalModelCatalog.CatalogModel) {
        pauseFlags[m.id]?.set(true)
        _states.value = _states.value + (m.id to DownloadState.Idle)
    }

    fun resume(m: LocalModelCatalog.CatalogModel) = download(m)

    fun cancelDownload(m: LocalModelCatalog.CatalogModel) {
        pauseFlags[m.id]?.set(true)
        jobs[m.id]?.cancel()
        File(fileFor(m).absolutePath + ".part").delete()
        _states.value = _states.value + (m.id to DownloadState.Idle)
    }

    fun delete(m: LocalModelCatalog.CatalogModel) {
        cancelDownload(m)
        if (isModelActive(m)) unload()
        fileFor(m).delete()
        if (settings.localModelId() == m.id) settings.setLocalModelId(null)
        _states.value = _states.value + (m.id to DownloadState.Idle)
    }

    /** Import a .gguf from a SAF uri (copied into models dir; magic-checked). */
    suspend fun import(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}.gguf"
            val target = File(modelsDir(), name)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 512 * 1024) }
            }
            target.inputStream().use { ins ->
                val magic = ByteArray(4)
                ins.read(magic)
                if (!magic.toString(Charsets.US_ASCII).startsWith("GGUF")) {
                    target.delete()
                    throw IOException("Not a GGUF model file")
                }
            }
            Logx.i("local: imported model $name (${target.length() / 1024 / 1024} MB)")
            target
        }.onFailure { Logx.e("local: import failed: ${it.message}") }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------ test

    /** Real end-to-end test of the active model: one tiny agent-style generation. */
    data class ModelTestReport(
        val modelId: String,
        val elapsedMs: Long,
        val tokens: Int,
        val tokensPerSec: Float,
        val snippet: String,
    )

    suspend fun testActive(): Result<ModelTestReport> {
        if (!engine.isLoaded()) {
            return Result.failure(IllegalStateException("No model is active — activate one first."))
        }
        val id = provider.activeModel?.id ?: "?"
        val prompt = "USER TASK: open example.com and read the page title. Respond with one JSON action only."
        val messages = listOf(
            ChatMessage("system", "You are a browser agent. Reply with a single JSON object."),
            ChatMessage("user", prompt),
        )
        val start = System.currentTimeMillis()
        val res = runCatching { provider.chat(messages, "local", temperature = 0.2, maxTokens = 48) }
        val ms = System.currentTimeMillis() - start
        return res.fold(
            onSuccess = { text ->
                val g = provider.genState.value
                Result.success(ModelTestReport(id, ms, g.outTokens, g.tokensPerSec, text.take(90)))
            },
            onFailure = { t ->
                Result.failure(IllegalStateException("Test failed after ${ms / 1000}s: ${t.message?.take(90) ?: "no output"}"))
            },
        )
    }

    // ------------------------------------------------------------ native bits

    /**
     * Best-effort count of PHYSICAL performance cores (big.LITTLE aware):
     * reads cpufreq max frequencies from sysfs and counts cores running at
     * >=85% of the fastest core. Falls back to 4 (typical prime+big cluster)
     * when sysfs is unavailable. LITTLE cores add scheduling overhead without
     * adding decode throughput.
     */
    private fun detectPerfCores(): Int = runCatching {
        val cpuDir = File("/sys/devices/system/cpu")
        val freqs = cpuDir.listFiles { f -> f.name.startsWith("cpu") && f.name.length > 3 && f.name[3].isDigit() }
            ?.mapNotNull { cpu ->
                runCatching { File(cpu, "cpufreq/cpuinfo_max_freq").readText().trim().toInt() }.getOrNull()
            }.orEmpty()
        if (freqs.isEmpty()) return 4
        val max = freqs.max()
        freqs.count { it * 100 >= max * 85 }.coerceIn(1, 6)
    }.getOrDefault(4)

    private fun verifyChecksum(file: File, expected: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        return got.equals(expected, ignoreCase = true)
    }
}
