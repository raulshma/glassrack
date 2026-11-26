package com.raulshma.grack.core.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 6 binary transfers on the ledger: audited as CONFIG with the real duration and
 * outcome (SUCCESS/FAILED/TIMEOUT/CANCELLED — cancellation recorded under NonCancellable
 * then rethrown), uploads blocked under Safe Read-Only Mode, and permission failures NOT
 * escalated to the privileged save path (that is text-editor-only). Hermetic:
 * FakeSshSessionClient only. Every test stops the controller in `finally` so a failed
 * assertion cannot leave a delay loop pumping on the test scheduler.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class FileTransferAuditTest {

    private val server = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(probeVersion = TelemetryParsers.CAPABILITIES_PROBE_VERSION),
    )

    private class RecordingSink : TransferSink {
        val collected = ByteArrayOutputStream()
        var closed = false

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            collected.write(buffer, offset, length)
        }

        override fun flush() = Unit

        override fun close() {
            closed = true
        }
    }

    private class RecordingSource(private val payload: ByteArray) : TransferSource {
        var closed = false
        override fun read(buffer: ByteArray): Int = -1 // empty: zero-byte upload is fine for audit tests
        override fun close() {
            closed = true
        }
    }

    private inline fun <T> withController(scope: kotlinx.coroutines.test.TestScope, block: (ControllerHarness) -> T): T {
        val h = ControllerHarness(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            testScope = scope,
        )
        try {
            return block(h)
        } finally {
            h.controller.stop()
        }
    }

    @Test
    fun downloadSuccessIsAuditedAsConfigWithRealOutcomeAndDuration() = runTest {
        withController(this) { h ->
            h.fake.downloadFileHandler = { request ->
                assertEquals(DEFAULT_TRANSFER_TIMEOUT_MS, request.timeoutMs)
                request.destination.write(ByteArray(3000), 0, 3000)
                request.onProgress(FileTransferProgress(3000, 3000))
                3000L
            }
            val sink = RecordingSink()
            val progress = mutableListOf<FileTransferProgress>()

            val bytes = h.controller.downloadFile(server, "/var/lib/backup.tar", sink) { progress += it }

            assertEquals(3000L, bytes)
            assertEquals(3000, sink.collected.size())
            assertEquals(listOf(FileTransferProgress(3000, 3000)), progress)

            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.single()
            assertEquals(AuditCategory.CONFIG, audit.category)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Downloaded File: /var/lib/backup.tar", audit.actionTitle)
            assertTrue(audit.commandSanitized.contains("sftp get /var/lib/backup.tar"), audit.commandSanitized)
            assertTrue(audit.durationMs >= 0, "duration ${audit.durationMs}")
            assertTrue(audit.outputSnippet.contains("3000"), audit.outputSnippet)
        }
    }

    @Test
    fun uploadSuccessIsAuditedAsConfigWithRealOutcomeAndDuration() = runTest {
        withController(this) { h ->
            h.fake.uploadFileHandlerBinary = { request ->
                assertEquals(4096L, request.totalBytes)
                request.onProgress(FileTransferProgress(4096, 4096))
                4096L
            }
            val progress = mutableListOf<FileTransferProgress>()

            val bytes = h.controller.uploadFile(server, RecordingSource(ByteArray(4096)), "/srv/app.iso", 4096L) {
                progress += it
            }

            assertEquals(4096L, bytes)
            assertEquals(listOf(FileTransferProgress(4096, 4096)), progress)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.single()
            assertEquals(AuditCategory.CONFIG, audit.category)
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
            assertEquals("Uploaded File: /srv/app.iso", audit.actionTitle)
            assertTrue(audit.commandSanitized.contains("sftp put /srv/app.iso"), audit.commandSanitized)
            assertTrue(audit.durationMs >= 0, "duration ${audit.durationMs}")
        }
    }

    @Test
    fun downloadTimeoutRecordsTimeoutOutcomeAndRethrows() = runTest {
        withController(this) { h ->
            h.fake.downloadFileHandler = { request ->
                // Virtual time: the handler overruns the budget and the withTimeout wins.
                delay((request.timeoutMs ?: DEFAULT_TRANSFER_TIMEOUT_MS) + 1_000)
                0L
            }

            val sink = RecordingSink()
            assertFailsWith<TimeoutCancellationException> {
                h.controller.downloadFile(server, "/var/lib/big.tar", sink, timeoutMs = 5_000L)
            }

            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.TIMEOUT, audit.outcome)
            assertEquals("Downloaded File: /var/lib/big.tar", audit.actionTitle)
            assertTrue(audit.durationMs >= 0)
        }
    }

    @Test
    fun cancelMidTransferRecordsCancelledAuditAndEndsTheJob() = runTest {
        withController(this) { h ->
            h.fake.downloadFileHandler = { request ->
                request.onProgress(FileTransferProgress(1024, 4096))
                awaitCancellation() // in-flight read: only the channel disconnect unblocks it
            }

            val sink = RecordingSink()
            val job = launch { h.controller.downloadFile(server, "/var/lib/stream.bin", sink) }
            runCurrent()

            job.cancel()
            runCurrent()

            assertTrue(job.isCancelled)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.CANCELLED, audit.outcome)
            assertEquals("Downloaded File: /var/lib/stream.bin", audit.actionTitle)
        }
    }

    @Test
    fun failedUploadRecordsFailedOutcomeAndRethrows() = runTest {
        withController(this) { h ->
            h.fake.uploadFileHandlerBinary = { throw java.io.IOException("disk full") }

            val error = assertFailsWith<java.io.IOException> {
                h.controller.uploadFile(server, RecordingSource(ByteArray(10)), "/srv/app.iso", 10L)
            }
            assertEquals("disk full", error.message)

            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertEquals("Uploaded File: /srv/app.iso", audit.actionTitle)
            assertTrue(audit.outputSnippet.contains("disk full"), audit.outputSnippet)
        }
    }

    @Test
    fun permissionDeniedUploadRecordsFailedWithoutPrivilegedEscalation() = runTest {
        withController(this) { h ->
            h.fake.uploadFileHandlerBinary = {
                throw FilePermissionDeniedException("Permission denied backing up /etc/app.conf; upload aborted")
            }

            assertFailsWith<FilePermissionDeniedException> {
                h.controller.uploadFile(server, RecordingSource(ByteArray(10)), "/etc/app.conf", 10L)
            }

            // The privileged save path is text-editor-only: no temp upload, no sudo command.
            assertTrue(h.fake.uploadRequests.isEmpty())
            assertTrue(h.fake.executeRequests.isEmpty())
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.FAILED, audit.outcome)
            assertEquals("Uploaded File: /etc/app.conf", audit.actionTitle)
        }
    }

    @Test
    fun safeReadOnlyModeBlocksUploadsBeforeAnyTransfer() = runTest {
        withController(this) { h ->
            h.repository.updatePreferences { it.copy(safeReadOnlyMode = true) }
            h.fake.uploadFileHandlerBinary = { 1L }

            val error = assertFailsWith<IllegalStateException> {
                h.controller.uploadFile(server, RecordingSource(ByteArray(10)), "/srv/app.iso", 10L)
            }
            assertTrue(error.message!!.contains("Safe Read-Only Mode"), error.message)

            assertTrue(h.fake.binaryUploadRequests.isEmpty())
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
            assertEquals(AuditOutcome.BLOCKED, audit.outcome)
            assertEquals(0L, audit.durationMs)
        }
    }

    @Test
    fun safeReadOnlyModeDoesNotBlockDownloads() = runTest {
        withController(this) { h ->
            h.repository.updatePreferences { it.copy(safeReadOnlyMode = true) }
            h.fake.downloadFileHandler = { request ->
                request.destination.write(ByteArray(8) { 1 }, 0, 8)
                8L
            }

            val bytes = h.controller.downloadFile(server, "/etc/hostname", RecordingSink())

            assertEquals(8L, bytes)
            val audit = h.repository.auditEntries.first { it.isNotEmpty() }.single()
            assertEquals(AuditOutcome.SUCCESS, audit.outcome)
        }
    }
}
