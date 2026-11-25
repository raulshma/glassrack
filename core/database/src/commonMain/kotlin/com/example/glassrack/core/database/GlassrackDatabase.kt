package com.example.glassrack.core.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
    version = 1,
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

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object GlassrackDatabaseConstructor : RoomDatabaseConstructor<GlassrackDatabase>

expect class DatabasePlatformContext

expect fun createGlassrackDatabaseBuilder(platformContext: DatabasePlatformContext): RoomDatabase.Builder<GlassrackDatabase>

fun buildGlassrackDatabase(platformContext: DatabasePlatformContext): GlassrackDatabase {
    return createGlassrackDatabaseBuilder(platformContext)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(true)
        .build()
}
