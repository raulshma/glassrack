package com.raulshma.grack.feature.servers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.raulshma.grack.core.ssh.TransferSink
import com.raulshma.grack.core.ssh.TransferSource
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.InputStream
import java.io.OutputStream

/**
 * Streams FileKit-picked files through the Android `ContentResolver`: SAF destinations
 * picked in the saver/picker dialogs arrive as content `Uri`s and are read/written
 * chunk-wise by the SFTP transport, so neither side ever holds the whole file in memory.
 */
class ContentResolverTransferStreams(private val context: Context) : TransferStreams {

    override fun displayName(file: PlatformFile): String = when (val wrapped = file.androidFile) {
        is AndroidFile.FileWrapper -> wrapped.file.name
        is AndroidFile.UriWrapper -> queryDisplayName(wrapped.uri) ?: wrapped.uri.lastPathSegment.orEmpty()
    }

    override fun sizeOf(file: PlatformFile): Long = when (val wrapped = file.androidFile) {
        is AndroidFile.FileWrapper -> wrapped.file.length()
        is AndroidFile.UriWrapper -> querySize(wrapped.uri)
    }

    override fun openSink(destination: PlatformFile): TransferSink = when (val wrapped = destination.androidFile) {
        is AndroidFile.FileWrapper -> StreamSink(wrapped.file.outputStream())
        is AndroidFile.UriWrapper -> StreamSink(
            requireNotNull(context.contentResolver.openOutputStream(wrapped.uri, "wt")) {
                "ContentResolver refused to open an output stream for $destination"
            },
        )
    }

    override fun openSource(source: PlatformFile): TransferSource = when (val wrapped = source.androidFile) {
        is AndroidFile.FileWrapper -> StreamSource(wrapped.file.inputStream())
        is AndroidFile.UriWrapper -> StreamSource(
            requireNotNull(context.contentResolver.openInputStream(wrapped.uri)) {
                "ContentResolver refused to open an input stream for $source"
            },
        )
    }

    private fun queryDisplayName(uri: Uri): String? = query(uri, OpenableColumns.DISPLAY_NAME)

    private fun querySize(uri: Uri): Long =
        query(uri, OpenableColumns.SIZE)?.let { raw -> raw.toLongOrNull() ?: -1L } ?: -1L

    private fun query(uri: Uri, column: String): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(column)
            if (index < 0) null else cursor.getString(index)
        }
    }.getOrNull()

    /** [TransferSink] over a plain stream; close exceptions never mask transfer failures. */
    private class StreamSink(private val stream: OutputStream) : TransferSink {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            stream.write(buffer, offset, length)
        }

        override fun flush() {
            stream.flush()
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }

    private class StreamSource(private val stream: InputStream) : TransferSource {
        override fun read(buffer: ByteArray): Int = stream.read(buffer, 0, buffer.size)

        override fun close() {
            runCatching { stream.close() }
        }
    }
}
