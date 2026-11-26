package com.raulshma.grack.core.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        ServerEntity::class,
        SshKeyEntity::class,
        DashboardProfileEntity::class,
        DashboardTileEntity::class,
        MetricSampleEntity::class,
        AuditEntryEntity::class,
        SnippetEntity::class,
        AlertRuleEntity::class,
        TunnelEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(GlassrackDatabaseConstructor::class)
abstract class GlassrackDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun dashboardDao(): DashboardDao
    abstract fun metricSampleDao(): MetricSampleDao
    abstract fun auditDao(): AuditDao
    abstract fun snippetDao(): SnippetDao
    abstract fun alertRuleDao(): AlertRuleDao
    abstract fun tunnelDao(): TunnelDao
}

/**
 * Schema v2 (one migration covering every later roadmap phase's needs): adds the sudo/update
 * columns to `servers` and the schedule columns to `snippets`. Each ALTER TABLE default must
 * stay identical to the matching entity `@ColumnInfo(defaultValue)` or Room's validation of
 * the migrated schema fails. Existing rows keep their data; the defaults apply only to them.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE servers ADD COLUMN sudoAuth TEXT NOT NULL DEFAULT 'NONE'")
        connection.execSQL("ALTER TABLE servers ADD COLUMN updatePolicy TEXT NOT NULL DEFAULT 'MANUAL'")
        connection.execSQL("ALTER TABLE servers ADD COLUMN updateOs INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE servers ADD COLUMN updateContainers INTEGER NOT NULL DEFAULT 1")
        connection.execSQL("ALTER TABLE servers ADD COLUMN updateReportJson TEXT")
        connection.execSQL("ALTER TABLE snippets ADD COLUMN scheduleSpec TEXT")
        connection.execSQL("ALTER TABLE snippets ADD COLUMN scheduleTargetsJson TEXT")
        connection.execSQL("ALTER TABLE snippets ADD COLUMN lastRunEpochMs INTEGER")
        connection.execSQL("ALTER TABLE snippets ADD COLUMN lastRunStatus TEXT")
    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object GlassrackDatabaseConstructor : RoomDatabaseConstructor<GlassrackDatabase>

expect class DatabasePlatformContext

expect fun createGlassrackDatabaseBuilder(platformContext: DatabasePlatformContext): RoomDatabase.Builder<GlassrackDatabase>

fun buildGlassrackDatabase(platformContext: DatabasePlatformContext): GlassrackDatabase {
    return createGlassrackDatabaseBuilder(platformContext)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // Destructive migration was removed deliberately: an upgrade must never wipe the
        // nodes, snippets or the audit chain. Every version change ships a real migration.
        .addMigrations(MIGRATION_1_2)
        .build()
}
