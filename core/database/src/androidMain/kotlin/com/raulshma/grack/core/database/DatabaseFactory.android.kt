package com.raulshma.grack.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.room3.RoomDatabase
import okio.Path.Companion.toPath

actual class DatabasePlatformContext(val context: Context)

actual fun createGlassrackDatabaseBuilder(
    platformContext: DatabasePlatformContext,
): RoomDatabase.Builder<GlassrackDatabase> {
    val appContext = platformContext.context.applicationContext
    val dbFile = appContext.getDatabasePath("glassrack.db")
    return Room.databaseBuilder<GlassrackDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    )
}

actual fun createPreferencesDataStore(platformContext: DatabasePlatformContext): DataStore<Preferences> {
    val appContext = platformContext.context.applicationContext
    val path = appContext.filesDir.resolve("glassrack_prefs.preferences_pb").absolutePath.toPath()
    return PreferenceDataStoreFactory.createWithPath(produceFile = { path })
}
