package com.raulshma.grack.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.ContainerUpdateStatus
import com.raulshma.grack.core.model.DistroFamily
import com.raulshma.grack.core.model.NodeUpdateReport
import com.raulshma.grack.core.model.OsPackageUpdate
import com.raulshma.grack.core.model.OsScanStatus
import com.raulshma.grack.core.model.OsUpdateSection
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.security.SecureVault
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3B scan-result persistence: the merged [NodeUpdateReport] round-trips through the
 * schema-v2 `servers.updateReportJson` column (entity mapping + the column-scoped
 * [GlassrackRepository.updateUpdateReport] writer that must not clobber other columns).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class UpdateReportPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun repository(scope: TestScope): GlassrackRepository {
        val queryContext = scope.coroutineContext.minusKey(Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-report-test-${UUID.randomUUID()}.preferences_pb")
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

    private val server = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5")

    private fun report(scannedAt: Long = 1_000L) = NodeUpdateReport(
        scannedAtEpochMs = scannedAt,
        os = OsUpdateSection(
            family = DistroFamily.DEBIAN,
            status = OsScanStatus.OK,
            packages = listOf(
                OsPackageUpdate("curl", currentVersion = "7.81.0-1", candidateVersion = "7.81.0-2"),
                OsPackageUpdate("openssl", currentVersion = "3.0.2", candidateVersion = "3.0.3"),
            ),
        ),
        containers = listOf(
            com.raulshma.grack.core.model.ContainerUpdateEntry(
                containerName = "web",
                image = "nginx:1.27",
                composeProject = "webstack",
                composeWorkingDir = "/opt/webstack",
                composeConfigFiles = "/opt/webstack/compose.yaml",
                status = ContainerUpdateStatus.UPDATE_AVAILABLE,
                localRepoDigest = "sha256:local",
                remoteDigest = "sha256:remote",
            ),
            com.raulshma.grack.core.model.ContainerUpdateEntry(
                containerName = "pinned",
                image = "app@sha256:deadbeef",
                status = ContainerUpdateStatus.PINNED,
            ),
        ),
        rebootRecommended = false,
    )

    @Test
    fun updateReportJsonSurvivesTheEntityRoundTrip() = runTest {
        val repo = repository(this)
        val report = report()

        repo.upsertServer(server.copy(updateReportJson = json.encodeToString(report)))

        val stored = repo.getServersOnce().single()
        assertEquals(server.id, stored.id)
        assertNotNull(stored.updateReportJson)
        val parsed = NodeUpdateReport.fromJson(stored.updateReportJson)
        assertNotNull(parsed)
        assertEquals(report, parsed)
        assertEquals(2, parsed.osUpdateCount)
        assertEquals(1, parsed.containerUpdateCount)
        assertEquals(DistroFamily.DEBIAN, parsed.os?.family)
    }

    @Test
    fun updateUpdateReportWritesOnlyThatColumn() = runTest {
        val repo = repository(this)
        val renamed = server.copy(name = "renamed-node", notes = "keep me")
        repo.upsertServer(renamed)

        repo.updateUpdateReport(server.id, json.encodeToString(report()))

        val stored = repo.getServersOnce().single()
        assertEquals("renamed-node", stored.name, "a report write must not reset other columns")
        assertEquals("keep me", stored.notes)
        assertNotNull(NodeUpdateReport.fromJson(stored.updateReportJson))
    }

    @Test
    fun updateUpdateReportClearsTheReportWithNull() = runTest {
        val repo = repository(this)
        repo.upsertServer(server)
        repo.updateUpdateReport(server.id, json.encodeToString(report()))
        repo.updateUpdateReport(server.id, null)

        val stored = repo.getServersOnce().single()
        assertNull(stored.updateReportJson)
        assertNull(NodeUpdateReport.fromJson(stored.updateReportJson))
    }

    @Test
    fun aNeverScannedServerHasNoReportAndUnknownIdsAreIgnored() = runTest {
        val repo = repository(this)
        repo.upsertServer(server)

        assertNull(repo.getServersOnce().single().updateReportJson)
        // A stale scan for a deleted node must not resurrect a row.
        repo.updateUpdateReport("srv-gone", json.encodeToString(report()))
        assertEquals(1, repo.getServersOnce().size)
        assertTrue(NodeUpdateReport.fromJson(null) == null)
        assertTrue(NodeUpdateReport.fromJson("not json") == null)
    }
}
