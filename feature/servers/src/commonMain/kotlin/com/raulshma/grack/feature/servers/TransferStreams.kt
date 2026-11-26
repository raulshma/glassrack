package com.raulshma.grack.feature.servers

import com.raulshma.grack.core.ssh.TransferSink
import com.raulshma.grack.core.ssh.TransferSource
import io.github.vinceglb.filekit.PlatformFile

/**
 * Platform bridge between the FileKit-picked [PlatformFile]s and the streamed binary
 * SFTP transfers. The Android implementation streams through `ContentResolver` (SAF
 * destinations are content URIs); no binding exists on iOS (and in tests), where file
 * transfers are reported as unsupported instead. Bound in Koin via `getOrNull` so the
 * ViewModel stays KMP-clean without platform expect/actual members.
 */
interface TransferStreams {
    /** Human-readable file name of [file] (SAF display name when content-resolver-backed). */
    fun displayName(file: PlatformFile): String

    /** Size in bytes, or `-1` when it cannot be determined before the transfer starts. */
    fun sizeOf(file: PlatformFile): Long

    /** Opens the local streaming destination for a download; caller (transport) closes. */
    fun openSink(destination: PlatformFile): TransferSink

    /** Opens the local streaming source for an upload; caller (transport) closes. */
    fun openSource(source: PlatformFile): TransferSource
}
