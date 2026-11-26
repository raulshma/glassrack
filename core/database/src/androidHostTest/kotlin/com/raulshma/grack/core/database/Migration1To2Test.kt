package com.raulshma.grack.core.database

import androidx.room3.Room
import androidx.room3.migration.bundle.SchemaBundle
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MIGRATION_1_2 (schema v2) test. Builds a database at the exported v1 schema (1.json create
 * statements + v1 identity hash — exactly what Room's own migration-test helper seeds), with
 * a server and a snippet row from the pre-v2 app, then opens it through Room's production
 * path with [MIGRATION_1_2] registered. Room then runs the migration and validates the
 * migrated schema against the compiled v2 entities — any mismatch fails the open with
 * "Migration didn't correctly handle ...". Finally the data is read back through the DAOs:
 * rows must survive and the new columns must land on their documented defaults.
 * Hermetic: temp-file database under Robolectric, no network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class Migration1To2Test {

    @Test
    fun migration1To2PreservesRowsAppliesDefaultsAndSatisfiesRoomValidation() = runTest {
        val dbPath = Files.createTempFile("grack-migration", ".db")
        dbPath.toFile().deleteOnExit()
        val v1 = schemaBundle(1).database

        // 1. Create the database exactly as Room created it at version 1, and seed the
        //    identity hash so Room's open path treats it as a v1 install to be migrated.
        //    The framework driver is used for both steps (Robolectric implements it); the
        //    bundled driver's JVM native library is not on the android host-test classpath.
        val rawDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            dbPath.toAbsolutePath().toString(),
            null,
        )
        rawDb.use {
            // buildCreateQueries() also creates room_master_table and seeds the v1 identity
            // hash — exactly what Room's migration-test helper does when creating at v1.
            v1.buildCreateQueries().forEach(rawDb::execSQL)
            // The bundle's create statements do not set the pragma; without it Room would
            // take the onCreate path (version 0) instead of migrating from version 1.
            rawDb.version = 1
            rawDb.execSQL(
                """
                INSERT INTO servers (
                    id, name, groupTag, tagsJson, accentColorHex, primaryHost, fallbackHost, port,
                    username, authType, credentialVaultId, sshKeyId, jumpHostServerId, macAddress,
                    wolRelayServerId, hostKeyFingerprint, pollIntervalSec, preferredWifiSsidsJson,
                    notes, sortOrder, isSimulated, capabilitiesJson, customProbesJson
                ) VALUES (
                    'srv-1', 'node-a', 'Homelab', '["nas"]', 2139062143, '10.0.0.5', '', 22,
                    'root', 'KEY', NULL, NULL, NULL, 'aa:bb:cc:dd:ee:ff',
                    NULL, NULL, 5, '[]',
                    'notes', 0, 0, '{}', '[]'
                )
                """.trimIndent(),
            )
            rawDb.execSQL(
                """
                INSERT INTO snippets (
                    id, name, description, commandTemplate, targetTagsJson,
                    requiresConfirmation, sortOrder
                ) VALUES (
                    'snip-1', 'Uptime', 'check uptime', 'uptime', '[]',
                    0, 4
                )
                """.trimIndent(),
            )
            rawDb.execSQL(
                """
                INSERT INTO audit_entries (
                    id, timestampEpochMs, serverId, serverNameSnapshot, category, actionTitle,
                    commandSanitized, triggerSource, outcome, exitCode, durationMs, outputSnippet,
                    prevHash, entryHash
                ) VALUES (
                    'aud-1', 42, 'srv-1', 'node-a', 'SSH', 'Test', 'uptime', 'UI', 'SUCCESS',
                    0, 1, '', '${"x".repeat(8)}', '${"y".repeat(8)}'
                )
                """.trimIndent(),
            )
        }

        // 2. Open through Room with only MIGRATION_1_2 registered: Room runs the migration,
        //    then validates the migrated schema against the compiled v2 entities (a mismatch
        //    throws "Migration didn't correctly handle ..." and fails this test).
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GlassrackDatabase::class.java,
            dbPath.toAbsolutePath().toString(),
        )
            .setQueryCoroutineContext(testContext(this))
            .addMigrations(MIGRATION_1_2)
            .build()

        // 3. Data survived; the new columns carry their documented defaults (via the mappers).
        val server = db.serverDao().getAllOnce().single()
        assertEquals("srv-1", server.id)
        assertEquals("node-a", server.name)
        assertEquals("NONE", server.sudoAuth)
        assertEquals("MANUAL", server.updatePolicy)
        assertEquals(false, server.updateOs)
        assertEquals(true, server.updateContainers)
        assertNull(server.updateReportJson)

        val snippet = db.snippetDao().getAllOnce().single()
        assertEquals("snip-1", snippet.id)
        assertEquals("uptime", snippet.commandTemplate)
        assertNull(snippet.scheduleSpec)
        assertNull(snippet.scheduleTargetsJson)
        assertNull(snippet.lastRunEpochMs)
        assertNull(snippet.lastRunStatus)

        assertEquals(1, db.auditDao().getAllChronological().size, "audit chain must survive")
        db.close()
    }

    private fun schemaBundle(version: Int): SchemaBundle {
        val schemaDir = schemaDirectory()
        return SchemaBundle.deserialize(Files.newInputStream(schemaDir.resolve("$version.json")))
    }

    private fun schemaDirectory(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = dir?.resolve(SCHEMA_SUBDIR)
            if (candidate != null && Files.exists(candidate.resolve("1.json"))) return candidate
            dir = dir?.parent
        }
        error("Schema directory not found from ${System.getProperty("user.dir")}")
    }

    private fun testContext(scope: TestScope) = scope.coroutineContext.minusKey(Job)

    private companion object {
        const val SCHEMA_SUBDIR = "schemas/com.raulshma.grack.core.database.GlassrackDatabase"
    }
}
