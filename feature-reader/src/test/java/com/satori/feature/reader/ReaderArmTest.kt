package com.satori.feature.reader

import com.satori.domain.model.ReaderModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1's arms as the debug selftest takes them, the session settings each gives the native reader,
 * and the room each load makes for the weights its sessions pack.
 */
class ReaderArmTest {

    private val limits = ReaderLimits(
        sourceVocabulary = 122_706, maxSourcePositions = 256, maxTargetPositions = 256, decoderStartId = 2, endId = 2, padId = 1
    )

    private fun arm(text: String?): ReaderArm = (ReaderArm.parse(text) as ReaderArm.Parsed.Arm).arm

    private fun wrong(text: String): String = (ReaderArm.parse(text) as ReaderArm.Parsed.Wrong).why

    @Test
    fun `no arm, an empty one and default are the design's sessions`() {
        for (text in listOf(null, "", "  ", "default")) assertEquals(text, ReaderArm.DEFAULT, arm(text))
        assertEquals("prepack=session,arena=on,threads=4", ReaderArm.DEFAULT.label)
    }

    @Test
    fun `the design's sessions prepack the encoder and decoder_with_past, keep the arena and shrink it`() {
        val options = ReaderArm.DEFAULT.options(limits)

        assertTrue(options.prepackEncoder)
        assertFalse(options.prepackDecoder)
        assertTrue(options.prepackDecoderWithPast)
        assertTrue(options.cpuArena && options.shrinkArenaAfterSegment)
        assertEquals(4, options.threads)
        // The model's own limits, which for the pinned build are ReaderOptions' defaults.
        assertEquals(com.satori.reader.ReaderOptions(), options)
    }

    @Test
    fun `each key changes its setting alone, in any order`() {
        assertEquals(ReaderArm(Prepacking.ALL), arm("prepack=all"))
        assertEquals(ReaderArm(Prepacking.OFF, arena = false, threads = 2), arm("threads=2, arena=off ,prepack=off"))
        assertEquals("prepack=off,arena=off,threads=2", arm("threads=2,arena=off,prepack=off").label)

        val all = arm("prepack=all").options(limits)
        assertTrue(all.prepackEncoder && all.prepackDecoder && all.prepackDecoderWithPast)
        val none = arm("prepack=off,arena=off").options(limits)
        assertFalse(none.prepackEncoder || none.prepackDecoder || none.prepackDecoderWithPast)
        assertFalse(none.cpuArena || none.shrinkArenaAfterSegment)
        assertEquals(2, arm("threads=2").options(limits).threads)
    }

    @Test
    fun `a key or value it doesn't know says why`() {
        assertEquals("prepack takes session, all or off, not half", wrong("prepack=half"))
        assertEquals("arena takes on or off, not yes", wrong("arena=yes"))
        assertEquals("threads takes 2 or 4, not 8", wrong("threads=8"))
        assertEquals("unknown key beam=4 (keys: prepack, arena, threads)", wrong("prepack=all,beam=4"))
        assertEquals("unknown key all (keys: prepack, arena, threads)", wrong("all"))
    }

    @Test
    fun `a toy model's limits go to the native reader as they are`() {
        val toy = ReaderLimits(sourceVocabulary = 64, maxSourcePositions = 256, maxTargetPositions = 256, decoderStartId = 2, endId = 2, padId = 1)

        assertEquals(64, ReaderArm.DEFAULT.options(toy).sourceVocabulary)
    }

    @Test
    fun `the int8 build's room is its packed weights, about 0,14 GB, and 0,23 GB all packed`() {
        val packed = PackedBytes.of(ReaderModelId.INT8)

        assertEquals(56_623_104L, packed.encoder)
        assertEquals(92_033_024L, packed.decoder)
        assertEquals(82_595_840L, packed.decoderWithPast)
        assertEquals(139_218_944L, packed.of(ReaderArm.DEFAULT))
        assertEquals(231_251_968L, packed.of(arm("prepack=all")))
        assertEquals(0L, packed.of(arm("prepack=off")))
    }

    @Test
    fun `the fp16 build is priced at its worst, every weight in fp32, whatever the arm`() {
        val packed = PackedBytes.of(ReaderModelId.FP16)

        for (text in listOf("default", "prepack=all", "prepack=off")) assertEquals(text, 4 * 231_251_968L, packed.of(arm(text)))
    }
}
