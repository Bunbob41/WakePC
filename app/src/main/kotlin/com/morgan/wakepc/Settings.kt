package com.morgan.wakepc

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

data class WakeSettings(
    val baseUrl: String = "",
    val token: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    suspend fun current(): WakeSettings {
        val prefs = context.dataStore.data.first()
        return WakeSettings(
            baseUrl = prefs[BASE_URL].orEmpty(),
            token = prefs[TOKEN].orEmpty(),
        )
    }

    suspend fun save(settings: WakeSettings) {
        context.dataStore.edit { prefs ->
            prefs[BASE_URL] = settings.baseUrl.trim().trimEnd('/')
            prefs[TOKEN] = settings.token.trim()
        }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN = stringPreferencesKey("token")
    }
}
