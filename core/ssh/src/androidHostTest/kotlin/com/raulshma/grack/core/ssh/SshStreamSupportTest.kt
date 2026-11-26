package com.raulshma.grack.core.ssh

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SshStreamSupportTest {

    @Test
    fun utf8ChunkDecoderReassemblesSplitFourByteSequence() {
        val decoder = Utf8ChunkDecoder()
        val emoji = "😀" // F0 9F 98 80
        val bytes = emoji.encodeToByteArray()

        val firstHalf = decoder.decode(byteArrayOf(bytes[0], bytes[1]))
        val secondHalf = decoder.decode(byteArrayOf(bytes[2], bytes[3]))

        assertEquals("", firstHalf, "incomplete sequence must be held back")
        assertEquals(emoji, secondHalf)
        assertEquals("", decoder.flush())
    }

    @Test
    fun utf8ChunkDecoderReassemblesSplitThreeByteSequence() {
        val decoder = Utf8ChunkDecoder()
        val bytes = "a€z".encodeToByteArray() // 61 E2 82 AC 7A

        val one = decoder.decode(byteArrayOf(bytes[0], bytes[1]))
        val two = decoder.decode(byteArrayOf(bytes[2], bytes[3]))
        val three = decoder.decode(byteArrayOf(bytes[4]))

        assertEquals("a", one)
        assertEquals("€", two)
        assertEquals("z", three)
        assertEquals("", decoder.flush())
    }

    @Test
    fun utf8ChunkDecoderPassesThroughPlainAscii() {
        val decoder = Utf8ChunkDecoder()
        assertEquals("docker ps -a", decoder.decode("docker ps -a".encodeToByteArray()))
        assertEquals("", decoder.flush())
    }

    @Test
    fun utf8ChunkDecoderFlushReplacesTruncatedTailWithReplacementChars() {
        val decoder = Utf8ChunkDecoder()
        val held = decoder.decode(byteArrayOf(0xF0.toByte(), 0x9F.toByte()))
        assertEquals("", held)
        assertTrue(decoder.flush().isNotEmpty())
    }

    @Test
    fun cappedStreamDropsBytesPastTheLimit() {
        val capped = CappedByteArrayOutputStream(16)
        val big = ByteArray(64) { 'x'.code.toByte() }
        capped.write(big, 0, big.size)
        assertEquals(16, capped.size())
        assertEquals(16, capped.toByteArray().size)
    }

    @Test
    fun cappedStreamKeepsSmallWrites() {
        val capped = CappedByteArrayOutputStream(64)
        capped.write("small".encodeToByteArray(), 0, 5)
        assertEquals(5, capped.size())
        assertTrue(capped is ByteArrayOutputStream)
    }
}
