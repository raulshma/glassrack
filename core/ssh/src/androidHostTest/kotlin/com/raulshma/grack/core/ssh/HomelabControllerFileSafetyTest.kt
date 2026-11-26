package com.raulshma.grack.core.ssh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.OsCapabilities
import com.raulshma.grack.core.model.Server
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class HomelabControllerFileSafetyTest {

    private val server = Server(
        id = "srv-1",
        name = "node-a",
        primaryHost = "10.0.0.5",
        capabilities = OsCapabilities(distro = "Debian GNU/Linux 12", probeVersion = TelemetryParsers.CAPABILITIES_PROBE_VERSION),
    )

    private fun harness(scope: TestScope) = ControllerHarness(
        context = ApplicationProvider.getApplicationContext(),
        testScope = scope,
    )

    @Test
    fun successfulSaveIsAuditedWithRealDurationAndBackupNote() = runTest {
        val h = harness(this)
        h.fake.writeFileHandler = { true }

        h.controller.saveFileWithBackup(server, "/etc/caddy/Caddyfile", "new content")

        val write = h.fake.writeRequests.single()
        assertEquals("/etc/caddy/Caddyfile", write.remotePath)
        assertEquals("new content", write.newContent)

        val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
        assertEquals(AuditOutcome.SUCCESS, audit.outcome)
        assertTrue(audit.durationMs >= 0L)
        assertTrue(audit.outputSnippet.contains(".bak snapshot."))
        h.controller.stop()
    }

    @Test
    fun failedSaveIsAuditedAsFailedAndRethrown() = runTest {
        val h = harness(this)
        h.fake.writeFileHandler = { throw IOException("backup write failed") }

        val error = assertFailsWith<IOException> {
            h.controller.saveFileWithBackup(server, "/etc/fstab", "content")
        }
        assertEquals("backup write failed", error.message)

        val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
        assertEquals(AuditOutcome.FAILED, audit.outcome)
        assertTrue(audit.outputSnippet.contains("backup write failed"))
        h.controller.stop()
    }

    @Test
    fun timedOutSaveIsAuditedAsTimeout() = runTest {
        val h = harness(this)
        h.fake.writeFileHandler = { request ->
            withTimeout(request.timeoutMs) {
                delay(request.timeoutMs + 5_000)
                true
            }
        }

        assertFailsWith<TimeoutCancellationException> {
            h.controller.saveFileWithBackup(server, "/etc/fstab", "content")
        }

        val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
        assertEquals(AuditOutcome.TIMEOUT, audit.outcome)
        h.controller.stop()
    }

    @Test
    fun safeReadOnlyModeBlocksTheSaveBeforeAnyWrite() = runTest {
        val h = harness(this)
        h.repository.updatePreferences { it.copy(safeReadOnlyMode = true) }

        val error = assertFailsWith<IllegalStateException> {
            h.controller.saveFileWithBackup(server, "/etc/fstab", "content")
        }
        assertTrue(error.message!!.contains("Safe Read-Only Mode"))

        assertTrue(h.fake.writeRequests.isEmpty())
        val audit = h.repository.auditEntries.first { it.isNotEmpty() }.last()
        assertEquals(AuditOutcome.BLOCKED, audit.outcome)
        h.controller.stop()
    }
}
