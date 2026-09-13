package com.cometx.browser.ai.local

/**
 * ChunkPlanner — pure math for the v1.7.0 parallel model downloader.
 *
 * A model file is split into N disjoint byte ranges ([Chunk]); each range is
 * downloaded by its own connection straight into its own offset of the
 * preallocated .part file (no reassembly copy). Per-chunk progress is kept in
 * a tiny sidecar file so a crash, process death, or network drop resumes at
 * chunk granularity — this file must stay free of Android imports so it runs
 * on the JVM test gate.
 */
object ChunkPlanner {

    data class Chunk(val index: Int, val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    /** Default parallel streams. 4 balances throughput vs per-connection CDN throttling. */
    const val DEFAULT_MAX_CHUNKS: Int = 4

    /** Files smaller than this never bother with multiple connections. */
    const val MIN_CHUNK_BYTES: Long = 16L * 1024 * 1024

    /** Sidecar flush granularity: persist per-chunk progress every ~16 MB. */
    const val SIDECAR_FLUSH_BYTES: Long = 16L * 1024 * 1024

    fun plan(
        totalBytes: Long,
        maxChunks: Int = DEFAULT_MAX_CHUNKS,
        minChunkBytes: Long = MIN_CHUNK_BYTES,
    ): List<Chunk> {
        if (totalBytes <= 0) return emptyList()
        if (maxChunks <= 1 || totalBytes <= minChunkBytes) {
            return listOf(Chunk(0, 0, totalBytes - 1))
        }
        val count = minOf(maxChunks.toLong(), (totalBytes + minChunkBytes - 1) / minChunkBytes).toInt()
        val base = totalBytes / count
        val extra = (totalBytes % count).toInt()
        val chunks = ArrayList<Chunk>(count)
        var offset = 0L
        for (i in 0 until count) {
            val len = base + if (i < extra) 1 else 0
            chunks.add(Chunk(i, offset, offset + len - 1))
            offset += len
        }
        return chunks
    }

    /** HTTP Range header for resuming [c] after [done] bytes of the chunk. */
    fun rangeHeader(c: Chunk, done: Long): String {
        // clamp to the last valid byte offset so a completed chunk can never
        // produce an inverted range (bytes=200-199)
        val from = c.start + done.coerceIn(0, (c.length - 1).coerceAtLeast(0))
        return "bytes=$from-${c.endInclusive}"
    }

    /**
     * Sidecar format: one line per chunk "index downloadedBytes". Plain text —
     * trivially parseable, zero dependencies, atomic enough (worst case a lost
     * update re-downloads the tail of one chunk; never corrupts the file since
     * chunk ranges are disjoint and writes are sequential within a chunk).
     */
    fun serializeSidecar(progress: Map<Int, Long>): String =
        progress.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" }

    fun parseSidecar(text: String): Map<Int, Long> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 2) return@mapNotNull null
            val idx = parts[0].toIntOrNull() ?: return@mapNotNull null
            val off = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (idx < 0 || off < 0) null else idx to off
        }.toMap()
}
