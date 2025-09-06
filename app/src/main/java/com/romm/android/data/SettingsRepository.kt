package com.romm.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val HOST = stringPreferencesKey("host")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val DOWNLOAD_DIRECTORY = stringPreferencesKey("download_directory")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val SAVE_FILES_DIRECTORY = stringPreferencesKey("save_files_directory")
        val SAVE_STATES_DIRECTORY = stringPreferencesKey("save_states_directory")
    }
    
    val settings: Flow<AppSettings> = context.dataStore.data
        .map { preferences ->
            AppSettings(
                host = preferences[PreferencesKeys.HOST] ?: "",
                username = preferences[PreferencesKeys.USERNAME] ?: "",
                password = preferences[PreferencesKeys.PASSWORD] ?: "",
                downloadDirectory = preferences[PreferencesKeys.DOWNLOAD_DIRECTORY] ?: "",
                maxConcurrentDownloads = preferences[PreferencesKeys.MAX_CONCURRENT_DOWNLOADS] ?: 3,
                saveFilesDirectory = preferences[PreferencesKeys.SAVE_FILES_DIRECTORY] ?: "",
                saveStatesDirectory = preferences[PreferencesKeys.SAVE_STATES_DIRECTORY] ?: ""
            )
        }
    
    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HOST] = settings.host
            preferences[PreferencesKeys.USERNAME] = settings.username
            preferences[PreferencesKeys.PASSWORD] = settings.password
            preferences[PreferencesKeys.DOWNLOAD_DIRECTORY] = settings.downloadDirectory
            preferences[PreferencesKeys.MAX_CONCURRENT_DOWNLOADS] = settings.maxConcurrentDownloads
            preferences[PreferencesKeys.SAVE_FILES_DIRECTORY] = settings.saveFilesDirectory
            preferences[PreferencesKeys.SAVE_STATES_DIRECTORY] = settings.saveStatesDirectory
        }
    }
    
    suspend fun getCurrentSettings(): AppSettings {
        val preferences = context.dataStore.data.first()
        return AppSettings(
            host = preferences[PreferencesKeys.HOST] ?: "",
            username = preferences[PreferencesKeys.USERNAME] ?: "",
            password = preferences[PreferencesKeys.PASSWORD] ?: "",
            downloadDirectory = preferences[PreferencesKeys.DOWNLOAD_DIRECTORY] ?: "",
            maxConcurrentDownloads = preferences[PreferencesKeys.MAX_CONCURRENT_DOWNLOADS] ?: 3,
            saveFilesDirectory = preferences[PreferencesKeys.SAVE_FILES_DIRECTORY] ?: "",
            saveStatesDirectory = preferences[PreferencesKeys.SAVE_STATES_DIRECTORY] ?: ""
        )
    }
}
