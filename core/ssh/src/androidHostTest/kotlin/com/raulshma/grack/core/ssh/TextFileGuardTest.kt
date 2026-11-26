package com.raulshma.grack.core.ssh

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFileGuardTest {

    @Test
    fun plainTextWithinTheLimitIsAccepted() {
        TextFileGuard.validate("hello = world\n".encodeToByteArray(), "/etc/nginx/nginx.conf")
        TextFileGuard.validateText("key: value", "/opt/app/config.yml")
    }

    @Test
    fun payloadAtExactlyOneMebibyteIsAccepted() {
        val bytes = ByteArray(TextFileGuard.MAX_TEXT_FILE_BYTES) { 'a'.code.toByte() }
        TextFileGuard.validate(bytes)
        assertFalse(TextFileGuard.isTooLarge(bytes.size))
    }

    @Test
    fun oversizedPayloadIsRefused() {
        val bytes = ByteArray(TextFileGuard.MAX_TEXT_FILE_BYTES + 1) { 'a'.code.toByte() }
        val ex = assertFailsWith<TextFileGuardException> { TextFileGuard.validate(bytes, "/var/log/big.log") }
        assertTrue(ex.message!!.contains("/var/log/big.log"))
        assertTrue(ex.message!!.contains("bytes"))
    }

    @Test
    fun nulBytePayloadIsRefusedAsBinary() {
        val bytes = "text".encodeToByteArray() + byteArrayOf(0) + "more".encodeToByteArray()
        val ex = assertFailsWith<TextFileGuardException> { TextFileGuard.validate(bytes, "/usr/bin/tool") }
        assertTrue(ex.message!!.contains("NUL"))
        assertTrue(TextFileGuard.containsNulBytes(bytes))
        assertFalse(TextFileGuard.containsNulBytes("clean text".encodeToByteArray()))
    }

    @Test
    fun aNulCodePointInTheStringFormIsCaughtOnTheSavePathToo() {
        // A NUL code point inside a String encodes to a 0x00 byte; the save path must refuse it.
        val withNul = "before" + Char(0) + "after"
        assertFailsWith<TextFileGuardException> { TextFileGuard.validateText(withNul) }
    }
}
