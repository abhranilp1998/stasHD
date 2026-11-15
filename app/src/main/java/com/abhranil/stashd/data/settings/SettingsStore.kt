package com.abhranil.stashd.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val accountEmail: String? = null,
    val lastDestTreeUri: String? = null,
    val wifiOnly: Boolean = true,
    val keepStructure: Boolean = true,
    val skipExisting: Boolean = false,
    val parallelism: Int = 2,
    val theme: String = "dark",
)

@Singleton
class SettingsStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private object Keys {
        val account = stringPreferencesKey("accountEmail")
        val dest = stringPreferencesKey("lastDestTreeUri")
        val wifiOnly = booleanPreferencesKey("wifiOnly")
        val keepStructure = booleanPreferencesKey("keepStructure")
        val skipExisting = booleanPreferencesKey("skipExisting")
        val parallelism = intPreferencesKey("parallelism")
        val theme = stringPreferencesKey("theme")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            accountEmail = p[Keys.account],
            lastDestTreeUri = p[Keys.dest],
            wifiOnly = p[Keys.wifiOnly] ?: true,
            keepStructure = p[Keys.keepStructure] ?: true,
            skipExisting = p[Keys.skipExisting] ?: false,
            parallelism = p[Keys.parallelism] ?: 2,
            theme = p[Keys.theme] ?: "dark",
        )
    }

    suspend fun setAccount(email: String?) = context.dataStore.edit { if (email == null) it.remove(Keys.account) else it[Keys.account] = email }
    suspend fun setDestination(uri: String) = context.dataStore.edit { it[Keys.dest] = uri }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.wifiOnly] = v }
    suspend fun setKeepStructure(v: Boolean) = context.dataStore.edit { it[Keys.keepStructure] = v }
    suspend fun setSkipExisting(v: Boolean) = context.dataStore.edit { it[Keys.skipExisting] = v }
    suspend fun setParallelism(v: Int) = context.dataStore.edit { it[Keys.parallelism] = v.coerceIn(1, 3) }
}
