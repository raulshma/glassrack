package com.raulshma.grack.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raulshma.grack.core.model.EncryptedBackupBundle
import com.raulshma.grack.core.model.Server
import com.raulshma.grack.core.model.SudoAuth
import com.raulshma.grack.core.model.UpdatePolicy
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A v1 (pre-tunnels, pre-schema-v2) backup payload must still import cleanly after the
 * database moved to version 2: restored rows land on the new columns' defaults, and a v2
 * export carries the new fields through a round trip unchanged (backup holds references
 * only — nothing to remap).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class V1BackupRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun repository(scope: TestScope): Pair<GlassrackRepository, SecureVault> {
        val queryContext = scope.coroutineContext.minusKey(Job)
        val prefsPath = File(System.getProperty("java.io.tmpdir"))
            .resolve("grack-v1-backup-test-${UUID.randomUUID()}.preferences_pb")
            .absolutePath
            .toPath()
        val vault = SecureVault(ApplicationProvider.getApplicationContext())
        val repo = GlassrackRepository(
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                GlassrackDatabase::class.java,
            ).setQueryCoroutineContext(queryContext).build(),
            vault = vault,
            preferencesStore = PreferencesStore(
                PreferenceDataStoreFactory.createWithPath(produceFile = { prefsPath }),
            ),
        )
        return repo to vault
    }

    /** Exactly what a Phase-1B-era (v1) export produced: no tunnels, no v2 server fields. */
    private fun v1PayloadJson(): String = """
        {
          "version": 1,
          "exportedAtEpochMs": 1700000000000,
          "servers": [
            {
              "id": "srv-v1",
              "name": "legacy-node",
              "groupTag": "Homelab",
              "tags": ["nas"],
              "primaryHost": "10.0.0.9",
              "port": 22,
              "username": "admin",
              "authType": "PASSWORD",
              "credentialVaultId": "srv_pw_srv-v1",
              "pollIntervalSec": 7,
              "notes": "imported from v1 backup"
            }
          ],
          "dashboards": [],
          "tiles": [],
          "snippets": [
            {
              "id": "snip-v1",
              "name": "Legacy snippet",
              "description": "",
              "commandTemplate": "uptime",
              "targetTags": ["nas"],
              "requiresConfirmation": true,
              "sortOrder": 3
            }
          ],
          "alertRules": [],
          "preferences": {},
          "sshKeyMetadata": []
        }
    """.trimIndent()

    @Test
    fun v1BackupStillImportsAndRestoredRowsLandOnV2Defaults() = runTest {
        val (repo, vault) = repository(this)

        val bundle = vault.encryptBackup(v1PayloadJson(), passphrase = "legacy pass")
        repo.importEncryptedBackup(json.encodeToString<EncryptedBackupBundle>(bundle), passphrase = "legacy pass")

        val server = repo.getServersOnce().single()
        assertEquals("srv-v1", server.id)
        assertEquals("legacy-node", server.name)
        // v2 columns fall back to their documented defaults for v1 payloads.
        assertEquals(SudoAuth.NONE, server.sudoAuth)
        assertEquals(UpdatePolicy.MANUAL, server.updatePolicy)
        assertTrue(!server.updateOs)
        assertTrue(server.updateContainers)
        assertNull(server.updateReportJson)

        val snippet = repo.snippets.first().single()
        assertEquals("uptime", snippet.commandTemplate)
        assertEquals(listOf("nas"), snippet.targetTags)
        assertNull(snippet.scheduleSpec)
        assertNull(snippet.scheduleTargets)
        assertNull(snippet.lastRunEpochMs)
        assertNull(snippet.lastRunStatus)

        assertTrue(repo.tunnels.first().isEmpty(), "v1 payloads have no tunnels")
    }

    @Test
    fun v2ExportCarriesSudoAndUpdateFieldsThroughARoundTrip() = runTest {
        val (source, _) = repository(this)
        source.upsertServer(
            Server(
                id = "srv-v2",
                name = "modern-node",
                primaryHost = "10.0.0.10",
                sudoAuth = SudoAuth.SEPARATE_PASSWORD,
                updatePolicy = UpdatePolicy.AUTO,
                updateOs = true,
                updateContainers = false,
                updateReportJson = "{\"osUpdates\":3}",
            ),
        )

        val bundleJson = source.exportEncryptedBackup(passphrase = "correct horse", nowEpochMs = 42L)
        val (target, _) = repository(this)
        target.importEncryptedBackup(bundleJson, passphrase = "correct horse")

        val restored = target.getServersOnce().single()
        assertEquals(SudoAuth.SEPARATE_PASSWORD, restored.sudoAuth)
        assertEquals(UpdatePolicy.AUTO, restored.updatePolicy)
        assertTrue(restored.updateOs)
        assertTrue(!restored.updateContainers)
        assertEquals("{\"osUpdates\":3}", restored.updateReportJson)
    }
}
