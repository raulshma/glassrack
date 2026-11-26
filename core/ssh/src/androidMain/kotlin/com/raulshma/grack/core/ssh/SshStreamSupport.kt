package com.raulshma.grack.core.ssh

/**
 * Stateful UTF-8 decoder for SSH output chunks. A multi-byte character split across two
 * reads is held back in [carry] instead of being mangled into replacement characters,
 * which is what a stateless `ByteArray.decodeToString(0, read)` does on chunk boundaries.
 */
internal class Utf8ChunkDecoder {
    private var carry: ByteArray = ByteArray(0)

    fun decode(bytes: ByteArray, length: Int = bytes.size): String {
        if (length <= 0) return ""
        val input = if (carry.isEmpty() && length == bytes.size) {
            bytes
        } else {
            val merged = ByteArray(carry.size + length)
            carry.copyInto(merged)
            bytes.copyInto(merged, carry.size, startIndex = 0, endIndex = length)
            merged
        }
        val completeBytes = completeByteCount(input)
        carry = if (completeBytes == input.size) ByteArray(0) else input.copyOfRange(completeBytes, input.size)
        return input.decodeToString(0, completeBytes)
    }

    /** Flushes any held-back bytes at end of stream; invalid bytes decode as U+FFFD. */
    fun flush(): String {
        val rest = carry
        carry = ByteArray(0)
        return if (rest.isEmpty()) "" else rest.decodeToString()
    }

    private fun completeByteCount(bytes: ByteArray): Int {
        // Walk back over up to 3 continuation bytes to find a possible trailing start byte.
        var index = bytes.size - 1
        var continuationCount = 0
        while (index >= 0 && continuationCount < 3 && bytes[index].toInt() and 0xC0 == 0x80) {
            continuationCount++
            index--
        }
        if (index < 0) {
            // Nothing but continuation bytes; too long to be a valid sequence, let the
            // decoder emit replacement characters instead of holding bytes forever.
            return bytes.size
        }
        val sequenceLength = sequenceLengthAt(bytes[index].toInt() and 0xFF)
        if (sequenceLength > 1 && bytes.size - index < sequenceLength) {
            return index // incomplete trailing sequence: hold back for the next chunk
        }
        return bytes.size
    }

    private fun sequenceLengthAt(startByte: Int): Int = when {
        startByte and 0xE0 == 0xC0 -> 2
        startByte and 0xF0 == 0xE0 -> 3
        startByte and 0xF8 == 0xF0 -> 4
        else -> -1 // ASCII or invalid start byte: nothing to hold back
    }
}

/**
 * [ByteArrayOutputStream] that silently drops bytes past [maxBytes] so a chatty command
 * cannot exhaust memory while the caller only ever keeps a bounded capture.
 */
internal class CappedByteArrayOutputStream(private val maxBytes: Int) : java.io.ByteArrayOutputStream() {
    override fun write(b: Int) {
        if (count < maxBytes) super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = maxBytes - count
        if (room <= 0) return
        super.write(b, off, len.coerceAtMost(room))
    }
}
