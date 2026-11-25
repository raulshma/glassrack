package com.example.glassrack.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual class DatabasePlatformContext

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(url?.path)
}

actual fun createGlassrackDatabaseBuilder(
    platformContext: DatabasePlatformContext,
): RoomDatabase.Builder<GlassrackDatabase> {
    val dbPath = "${documentDirectory()}/glassrack.db"
    return Room.databaseBuilder<GlassrackDatabase>(name = dbPath)
}

actual fun createPreferencesDataStore(platformContext: DatabasePlatformContext): DataStore<Preferences> {
    val path = "${documentDirectory()}/glassrack_prefs.preferences_pb".toPath()
    return PreferenceDataStoreFactory.createWithPath(produceFile = { path })
}
