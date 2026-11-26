package com.raulshma.grack.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.AuditCategory
import com.raulshma.grack.core.model.AuditOutcome
import com.raulshma.grack.core.model.TriggerSource
import com.raulshma.grack.core.model.currentTimeEpochMs
import com.raulshma.grack.core.security.SecureVault
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Automatic audit retention wiring (Phase 1C item 2): the coordinator reads
 * `auditRetentionDays` from preferences and drives the repository's existing
 * `pruneAuditLogs` (which re-anchors the hash chain).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class AuditRetentionCoordinatorTest {

    private fun repository(scope: TestScope): GlassrackRepository {
        val queryContext = scope.coroutineContext.minusKey(Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-retention-test-${UUID.randomUUID()}.preferences_pb")
            .absolutePath
            .toPath()
        return GlassrackRepository(
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                GlassrackDatabase::class.java,
            ).setQueryCoroutineContext(queryContext).build(),
            vault = SecureVault(ApplicationProvider.getApplicationContext()),
            preferencesStore = PreferencesStore(
                PreferenceDataStoreFactory.createWithPath(produceFile = { prefsPath }),
            ),
        )
    }

    private suspend fun GlassrackRepository.insertAuditAt(ageDays: Long, title: String) {
        recordAudit(
            timestampEpochMs = currentTimeEpochMs() - ageDays * 86_400_000L,
            serverId = null,
            serverNameSnapshot = "cluster",
            category = AuditCategory.SSH,
            actionTitle = title,
            rawCommand = "ls -la",
            triggerSource = TriggerSource.UI,
            outcome = AuditOutcome.SUCCESS,
        )
    }

    @Test
    fun pruneNowRemovesOnlyEntriesOlderThanTheStoredRetention() = runTest {
        val repo = repository(this)
        repo.updatePreferences { it.copy(auditRetentionDays = 30) }
        repo.insertAuditAt(ageDays = 60, title = "stale entry")
        repo.insertAuditAt(ageDays = 1, title = "fresh entry")

        val coordinator = AuditRetentionCoordinator(
            scope = this,
            preferences = repo.preferences,
            prune = repo::pruneAuditLogs,
        )
        val removed = coordinator.pruneNow()

        assertEquals(1, removed)
        val remaining = repo.auditEntries.first().map { it.actionTitle }
        assertEquals(listOf("fresh entry"), remaining)
        // The hash chain must still verify after the prune re-anchored it.
        assertTrue(repo.verifyAuditChain().isValid)
    }

    @Test
    fun recurringLoopPrunesAtStartAndEveryInterval() = runTest {
        val repo = repository(this)
        repo.updatePreferences { it.copy(auditRetentionDays = 7) }
        repo.insertAuditAt(ageDays = 10, title = "old")

        val coordinator = AuditRetentionCoordinator(
            scope = this,
            preferences = repo.preferences,
            prune = repo::pruneAuditLogs,
            pruneIntervalMs = 1_000L,
        )
        val loop = coordinator.start()
        runCurrent()

        // Startup prune already removed the stale entry.
        assertEquals(0, repo.auditEntries.first().size)

        // Subsequent cycles keep running on the virtual clock.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, repo.auditEntries.first().size)
        assertEquals(7, repo.preferences.first().auditRetentionDays)

        loop.cancel()
    }
}
