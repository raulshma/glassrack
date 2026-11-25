package com.example.glassrack.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.glassrack.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

expect fun createPreferencesDataStore(platformContext: DatabasePlatformContext): DataStore<Preferences>

class PreferencesStore(private val dataStore: DataStore<Preferences>) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val prefsKey = stringPreferencesKey("user_preferences_json")

    val preferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        val raw = prefs[prefsKey]
        if (raw.isNullOrBlank()) {
            UserPreferences()
        } else {
            runCatching { json.decodeFromString<UserPreferences>(raw) }
                .getOrDefault(UserPreferences())
        }
    }

    suspend fun current(): UserPreferences = preferences.first()

    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        dataStore.edit { prefs ->
            val currentVal = prefs[prefsKey]?.let {
                runCatching { json.decodeFromString<UserPreferences>(it) }.getOrNull()
            } ?: UserPreferences()
            val updated = transform(currentVal)
            prefs[prefsKey] = json.encodeToString(updated)
        }
    }
}
