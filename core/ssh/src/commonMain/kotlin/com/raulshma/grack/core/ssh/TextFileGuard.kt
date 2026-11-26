package com.raulshma.grack.core.ssh

/**
 * Guards the remote text editor against binary payloads. Any file larger than
 * [MAX_TEXT_FILE_BYTES] or containing NUL bytes is refused on both the open and the
 * save path, so a binary file can never be decoded as lossy text and written back
 * corrupted.
 */
object TextFileGuard {

    /** Largest payload the text editor will open or save (1 MiB). */
    const val MAX_TEXT_FILE_BYTES: Int = 1 shl 20

    fun isTooLarge(byteCount: Int): Boolean = byteCount > MAX_TEXT_FILE_BYTES

    fun containsNulBytes(bytes: ByteArray): Boolean = bytes.contains(0.toByte())

    /**
     * Throws [TextFileGuardException] if [bytes] cannot round-trip through the text editor.
     * [label] (e.g. the remote path) is included in the error message when provided.
     */
    fun validate(bytes: ByteArray, label: String? = null) {
        if (isTooLarge(bytes.size)) {
            throw TextFileGuardException(
                "${label.orEmpty().ifBlank { "File" }} is ${bytes.size} bytes, over the " +
                    "$MAX_TEXT_FILE_BYTES-byte text editor limit; refusing to open or save it as text",
            )
        }
        if (containsNulBytes(bytes)) {
            throw TextFileGuardException(
                "${label.orEmpty().ifBlank { "File" }} contains NUL bytes and looks binary; " +
                    "refusing to open or save it as text",
            )
        }
    }

    /** Convenience for content that is still a String (the save path). */
    fun validateText(content: String, label: String? = null) {
        validate(content.encodeToByteArray(), label)
    }
}

class TextFileGuardException(message: String) : Exception(message)
