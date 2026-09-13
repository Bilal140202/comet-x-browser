package com.cometx.browser

import com.cometx.browser.ai.local.ChunkPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7.0 background download math: chunk planning, resume Range headers, and
 * sidecar (de)serialization. Pure JVM — no Android.
 */
class ChunkPlannerTest {

    @Test fun `plan splits a large file into contiguous covering chunks`() {
        val total = 1024L * 1024 * 1024 // 1 GiB → 4 chunks of 256 MiB
        val chunks = ChunkPlanner.plan(total)
        assertEquals(4, chunks.size)
        assertEquals(0L, chunks.first().start)
        assertEquals(total - 1, chunks.last().endInclusive)
        assertEquals(total, chunks.sumOf { it.length })
        // contiguity: next.start == prev.end + 1
        for (i in 1 until chunks.size) {
            assertEquals(chunks[i - 1].endInclusive + 1, chunks[i].start)
        }
        // indices ordered 0..n-1
        assertEquals((0 until chunks.size).toList(), chunks.map { it.index })
    }

    @Test fun `plan keeps small files single-stream`() {
        val small = 15L * 1024 * 1024 // below MIN_CHUNK_BYTES
        val chunks = ChunkPlanner.plan(small)
        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].start)
        assertEquals(small - 1, chunks[0].endInclusive)
    }

    @Test fun `plan returns empty for zero and negative totals`() {
        assertTrue(ChunkPlanner.plan(0).isEmpty())
        assertTrue(ChunkPlanner.plan(-5).isEmpty())
    }

    @Test fun `plan distributes the remainder without losing bytes`() {
        // 100 bytes, min chunk 16 → count = min(4, ceil(100/16)=7) = 4 → 25+25+25+25
        val chunks = ChunkPlanner.plan(100, maxChunks = 4, minChunkBytes = 16)
        assertEquals(4, chunks.size)
        assertEquals(100L, chunks.sumOf { it.length })
        assertEquals(99L, chunks.last().endInclusive)
        // odd remainder: 101 bytes → 26+25+25+25
        val odd = ChunkPlanner.plan(101, maxChunks = 4, minChunkBytes = 16)
        assertEquals(101L, odd.sumOf { it.length })
        assertEquals(1L, odd[0].length - odd[1].length)
    }

    @Test fun `plan respects maxChunks for huge files`() {
        val total = 2L * 1024 * 1024 * 1024
        assertEquals(ChunkPlanner.DEFAULT_MAX_CHUNKS, ChunkPlanner.plan(total, maxChunks = 4).size)
        assertEquals(2, ChunkPlanner.plan(total, maxChunks = 2).size)
        // maxChunks <= 1 → single chunk even for huge files
        val one = ChunkPlanner.plan(total, maxChunks = 1)
        assertEquals(1, one.size)
        assertEquals(total, one[0].length)
    }

    @Test fun `rangeHeader resumes at start plus done and never past the chunk`() {
        val c = ChunkPlanner.Chunk(1, 100, 199) // length 100
        assertEquals("bytes=100-199", ChunkPlanner.rangeHeader(c, 0))
        assertEquals("bytes=160-199", ChunkPlanner.rangeHeader(c, 60))
        // clamped: done beyond chunk length can't run past the end
        assertEquals("bytes=199-199", ChunkPlanner.rangeHeader(c, 150))
        assertEquals("bytes=100-199", ChunkPlanner.rangeHeader(c, -3))
    }

    @Test fun `sidecar round trip preserves chunk progress`() {
        val original = mapOf(0 to 10_000L, 1 to 0L, 2 to 999_999L, 3 to 16L * 1024 * 1024)
        val text = ChunkPlanner.serializeSidecar(original)
        assertEquals(original, ChunkPlanner.parseSidecar(text))
        // sorted output for stable files
        assertTrue(text.indexOf("0 10000") < text.indexOf("2 999999"))
    }

    @Test fun `sidecar parser ignores malformed lines and negatives`() {
        val parsed = ChunkPlanner.parseSidecar(
            """
            0 500
            garbage
            1
            -2 100
            3 -7
            x9 10

            2  300
            4 abc
            """.trimIndent()
        )
        assertEquals(mapOf(0 to 500L, 2 to 300L), parsed)
    }

    @Test fun `empty sidecar parses to empty map`() {
        assertTrue(ChunkPlanner.parseSidecar("").isEmpty())
        assertTrue(ChunkPlanner.parseSidecar("\n\n  \n").isEmpty())
    }
}
