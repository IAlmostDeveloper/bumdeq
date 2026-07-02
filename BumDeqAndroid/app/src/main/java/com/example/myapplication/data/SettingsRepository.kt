package com.example.Bumdeq.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Один DataStore на процесс, привязан к application context.
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Персистентные настройки приложения. Пока хранит только MAC последнего выбранного
 * устройства — этим обеспечивается обещание UI «★ сохранено» (переживает перезапуск).
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    /** MAC сохранённого устройства, null — если ничего не сохранено. */
    val savedAddress: Flow<String?> = dataStore.data.map { prefs -> prefs[KEY_SAVED_ADDRESS] }

    suspend fun setSavedAddress(address: String) {
        dataStore.edit { prefs -> prefs[KEY_SAVED_ADDRESS] = address }
    }

    private companion object {
        val KEY_SAVED_ADDRESS = stringPreferencesKey("saved_device_address")
    }
}
