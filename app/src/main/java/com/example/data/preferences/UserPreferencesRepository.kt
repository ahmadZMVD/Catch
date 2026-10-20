package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.catchSongDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "catch_song_preferences"
)

enum class AppThemeMode(val storageKey: String, val displayLabel: String) {
    LIGHT_BONE("LIGHT_BONE", "Bone Light"),
    DARK_OBSIDIAN("DARK_OBSIDIAN", "Obsidian Dark"),
    SYSTEM_AUTO("SYSTEM_AUTO", "System");

    companion object {
        fun fromKey(key: String?): AppThemeMode {
            return entries.firstOrNull { it.storageKey == key } ?: LIGHT_BONE
        }
    }
}

data class UserPreferencesState(
    val themeMode: AppThemeMode = AppThemeMode.LIGHT_BONE,
    val mergeDuplicateCaptures: Boolean = false,
    val treeDebuggerEnabled: Boolean = true,
    val activeWindowTimeoutSeconds: Int = 18,
    val isTileAddedToQuickSettings: Boolean = false,
    val hasVisitedAccessibilitySettings: Boolean = false,
    val hasVisitedAppInfo: Boolean = false
) {
    val hasVisitedAppInfoSettings: Boolean
        get() = hasVisitedAppInfo
}

class UserPreferencesRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val MERGE_DUPLICATES = booleanPreferencesKey("merge_duplicate_captures")
        val TREE_DEBUGGER_ENABLED = booleanPreferencesKey("tree_debugger_enabled")
        val TIMEOUT_SECONDS = intPreferencesKey("active_window_timeout_seconds")
        val TILE_ADDED = booleanPreferencesKey("is_tile_added")
        val VISITED_ACCESSIBILITY = booleanPreferencesKey("has_visited_accessibility")
        val VISITED_APP_INFO = booleanPreferencesKey("has_visited_app_info")
    }

    val preferencesFlow: Flow<UserPreferencesState> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserPreferencesState(
                themeMode = AppThemeMode.fromKey(prefs[Keys.THEME_MODE]),
                mergeDuplicateCaptures = prefs[Keys.MERGE_DUPLICATES] ?: false,
                treeDebuggerEnabled = prefs[Keys.TREE_DEBUGGER_ENABLED] ?: true,
                activeWindowTimeoutSeconds = (prefs[Keys.TIMEOUT_SECONDS] ?: 18).coerceIn(8, 30),
                isTileAddedToQuickSettings = prefs[Keys.TILE_ADDED] ?: false,
                hasVisitedAccessibilitySettings = prefs[Keys.VISITED_ACCESSIBILITY] ?: false,
                hasVisitedAppInfo = prefs[Keys.VISITED_APP_INFO] ?: false
            )
        }

    suspend fun setThemeMode(mode: AppThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.storageKey }
    }

    suspend fun setMergeDuplicateCaptures(merge: Boolean) {
        dataStore.edit { it[Keys.MERGE_DUPLICATES] = merge }
    }

    suspend fun setTreeDebuggerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TREE_DEBUGGER_ENABLED] = enabled }
    }

    suspend fun setActiveWindowTimeoutSeconds(seconds: Int) {
        dataStore.edit { it[Keys.TIMEOUT_SECONDS] = seconds.coerceIn(8, 30) }
    }

    suspend fun setTileAddedToQuickSettings(added: Boolean) {
        dataStore.edit { it[Keys.TILE_ADDED] = added }
    }

    suspend fun setHasVisitedAccessibilitySettings(visited: Boolean) {
        dataStore.edit { it[Keys.VISITED_ACCESSIBILITY] = visited }
    }

    suspend fun setHasVisitedAppInfo(visited: Boolean) {
        dataStore.edit { it[Keys.VISITED_APP_INFO] = visited }
    }

    suspend fun setHasVisitedAppInfoSettings(visited: Boolean) = setHasVisitedAppInfo(visited)
}
