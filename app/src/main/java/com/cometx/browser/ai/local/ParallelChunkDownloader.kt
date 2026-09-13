package com.cometx.browser.ai.local

import com.cometx.browser.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thrown when the server does not honor Range requests → single-stream fallback. */
class NoRangeSupportException : IOException("server ignored Range")

/**
 * ParallelChunkDownloader (v1.7.0) — fast, resumable, network-fluctuation-proof
 * model downloads.
 *
 *  - The .part file is preallocated to the full size; every chunk writes its
 *    own disjoint byte range via RandomAccessFile → zero reassembly copy.
 *  - Per-chunk progress lives in a sidecar (.part.meta) flushed every
 *    [ChunkPlanner.SIDECAR_FLUSH_BYTES] → crash / process death / network drop
 *    resume at chunk granularity, never from scratch.
 *  - Chunks download in parallel (default 4 connections) → 2–4× faster on the
 *    Hugging Face CDN, which throttles per connection.
 *  - Servers that ignore Range fall back to the v1.6.0 single-stream path
 *    (detected once, then sequential with the server's own resume semantics).
 *  - Every network hiccup retries with backoff inside the chunk loop; bigger
 *    outages surface to WorkManager which reschedules (see ModelDownloadWorker).
 */
class ParallelChunkDownloader(
    private val url: String,
    private val target: File,
    private val meta: File,
    private val totalBytes: Long,
    private val maxChunks: Int = ChunkPlanner.DEFAULT_MAX_CHUNKS,
    private val onProgress: (downloaded: Long, speedKbs: Long) -> Unit = { _, _ -> },
) {

    private val chunkProgress = ConcurrentHashMap<Int, Long>()
    private val sidecarMutex = Mutex()
    private val overallDone = AtomicLong(0)
    private val lastTick = AtomicLong(System.currentTimeMillis())
    private val lastTickBytes = AtomicLong(0)

    /** Max retry attempts per chunk inside one worker run (blips, resets, CDN 5xx). */
    private val maxChunkTries = 8

    suspend fun run() = withContext(Dispatchers.IO) {
        if (totalBytes <= 0) throw IOException("Server did not report a usable content length")
        preallocate()
        val chunks = ChunkPlanner.plan(totalBytes, maxChunks)
        restoreSidecar(chunks)
        overallDone.set(chunkProgress.values.sum().coerceIn(0, totalBytes))
        try {
            downloadInParallel(chunks)
        } catch (e: NoRangeSupportException) {
            Logx.w("local-dl: server has no Range support → single-stream fallback")
            chunkProgress.clear()
            singleStream()
        }
        if (overallDone.get() < totalBytes) throw IOException("incomplete download (${overallDone.get()}/${totalBytes})")
        // full file present → mark every chunk complete so a re-run short-circuits
        saveSidecar(complete = true)
    }

    // ------------------------------------------------------------- parallel

    private suspend fun downloadInParallel(chunks: List<ChunkPlanner.Chunk>) = coroutineScope {
        chunks.map { c ->
            async {
                var done = chunkProgress[c.index] ?: 0L
                var attempted = 0
                while (done < c.length) {
                    ensureActive()
                    attempted++
                    if (attempted > maxChunkTries) {
                        throw IOException("chunk ${c.index} failed after $maxChunkTries attempts")
                    }
                    try {
                        done = downloadChunk(c, done)
                    } catch (e: NoRangeSupportException) {
                        throw e
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Logx.w("local-dl: chunk ${c.index} attempt $attempted failed: ${e.message}")
                        delay(backoffMs(attempted))
                    }
                }
            }
        }.awaitAll()
    }

    /** Streams [c]'s remaining bytes into the .part at absolute offsets. Returns new chunk progress. */
    private suspend fun downloadChunk(c: ChunkPlanner.Chunk, startDone: Long): Long =
        withContext(Dispatchers.IO) {
            val from = c.start + startDone
            val conn = open(range = "bytes=$from-${c.endInclusive}")
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                if (code != 206) throw NoRangeSupportException()
                var local = startDone
                var flushed = startDone
                conn.inputStream.use { input ->
                    RandomAccessFile(target, "rw").use { raf ->
                        raf.seek(c.start + local)
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            local += n
                            tick(c.index, n, local)
                            if (local - flushed >= ChunkPlanner.SIDECAR_FLUSH_BYTES) {
                                flushed = local
                                saveSidecar()
                            }
                        }
                    }
                }
                chunkProgress[c.index] = local
                saveSidecar()
                local
            } finally {
                conn.disconnect()
            }
        }

    // ----------------------------------------------------------- single stream

    /** v1.6.0-compatible fallback for servers without Range support. */
    private suspend fun singleStream() = withContext(Dispatchers.IO) {
        var downloaded = 0L
        while (downloaded < totalBytes) {
            ensureActive()
            val conn = open(range = if (downloaded > 0) "bytes=$downloaded-" else null)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val resumeOk = code == 206
                if (!resumeOk) downloaded = 0
                var flushed = downloaded
                conn.inputStream.use { input ->
                    RandomAccessFile(target, "rw").use { raf ->
                        raf.seek(downloaded)
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            downloaded += n
                            tick(0, n, downloaded)
                            if (downloaded - flushed >= ChunkPlanner.SIDECAR_FLUSH_BYTES) {
                                flushed = downloaded
                                saveSidecar(singleProgress = downloaded)
                            }
                        }
                    }
                }
                if (downloaded < totalBytes) delay(800) // server closed early → resume loop
            } finally {
                conn.disconnect()
            }
        }
        chunkProgress[0] = totalBytes
        saveSidecar(complete = true)
    }

    // ------------------------------------------------------------------ misc

    private fun open(range: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    private fun preallocate() {
        if (target.exists() && target.length() == totalBytes) return
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { it.setLength(totalBytes) }
        meta.delete()
        chunkProgress.clear()
    }

    private fun restoreSidecar(chunks: List<ChunkPlanner.Chunk>) {
        chunkProgress.putAll(ChunkPlanner.parseSidecar(if (meta.exists()) meta.readText() else ""))
        // drop anything impossible for this plan (plan change, stale sidecar)
        for (c in chunks) {
            if ((chunkProgress[c.index] ?: 0L) > c.length) chunkProgress[c.index] = 0L
        }
        chunkProgress.keys.retainAll(chunks.map { it.index }.toSet())
    }

    /** Fast per-read bookkeeping: chunk progress + aggregate counter + speed ticks. */
    private fun tick(chunkIndex: Int, bytes: Int, chunkLocal: Long) {
        chunkProgress[chunkIndex] = chunkLocal
        val totalNow = overallDone.addAndGet(bytes.toLong())
        val now = System.currentTimeMillis()
        val lt = lastTick.get()
        if (now - lt >= 800 && lastTick.compareAndSet(lt, now)) {
            val lb = lastTickBytes.getAndSet(totalNow)
            val speed = ((totalNow - lb) * 1000 / (now - lt).coerceAtLeast(1)) / 1024
            onProgress(totalNow.coerceAtMost(totalBytes), speed)
        }
    }

    private suspend fun saveSidecar(
        complete: Boolean = false,
        singleProgress: Long = -1,
    ) = withContext(Dispatchers.IO) {
        sidecarMutex.withLock {
            val snapshot: Map<Int, Long> = when {
                complete -> ChunkPlanner.plan(totalBytes, maxChunks).associate { it.index to it.length }
                singleProgress >= 0 -> mapOf(0 to singleProgress)
                else -> chunkProgress.toMap()
            }
            runCatching {
                meta.parentFile?.mkdirs()
                meta.writeText(ChunkPlanner.serializeSidecar(snapshot))
            }
        }
    }

    private fun backoffMs(attempt: Int): Long =
        (1000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

    companion object {
        private const val BUFFER = 256 * 1024
    }
}
