package com.androidcompiler.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidcompiler.core.common.model.AppSettings
import com.androidcompiler.core.common.model.CoreType
import com.androidcompiler.core.common.model.NetworkType
import com.androidcompiler.core.common.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CORE_TYPES = stringSetPreferencesKey("core_types")
        val NETWORK_TYPES = stringSetPreferencesKey("network_types")
        val DEFAULT_INPUT_FOLDER = stringPreferencesKey("default_input_folder")
        val DEFAULT_OUTPUT_FOLDER = stringPreferencesKey("default_output_folder")
        val INCREMENTAL_FILE_NAMES = booleanPreferencesKey("incremental_file_names")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { ThemeMode.valueOf(it) } ?: ThemeMode.OLED,
            coreTypes = prefs[Keys.CORE_TYPES]?.map { CoreType.valueOf(it) }?.toSet()
                ?: CoreType.entries.toSet(),
            networkTypes = prefs[Keys.NETWORK_TYPES]?.map { NetworkType.valueOf(it) }?.toSet()
                ?: NetworkType.entries.toSet(),
            defaultInputFolder = prefs[Keys.DEFAULT_INPUT_FOLDER],
            defaultOutputFolder = prefs[Keys.DEFAULT_OUTPUT_FOLDER],
            incrementalFileNames = prefs[Keys.INCREMENTAL_FILE_NAMES] ?: true
        )
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setCoreTypes(types: Set<CoreType>) {
        context.dataStore.edit { it[Keys.CORE_TYPES] = types.map { t -> t.name }.toSet() }
    }

    override suspend fun setNetworkTypes(types: Set<NetworkType>) {
        context.dataStore.edit { it[Keys.NETWORK_TYPES] = types.map { t -> t.name }.toSet() }
    }

    override suspend fun setDefaultInputFolder(path: String?) {
        context.dataStore.edit {
            if (path != null) it[Keys.DEFAULT_INPUT_FOLDER] = path
            else it.remove(Keys.DEFAULT_INPUT_FOLDER)
        }
    }

    override suspend fun setDefaultOutputFolder(path: String?) {
        context.dataStore.edit {
            if (path != null) it[Keys.DEFAULT_OUTPUT_FOLDER] = path
            else it.remove(Keys.DEFAULT_OUTPUT_FOLDER)
        }
    }

    override suspend fun setIncrementalFileNames(enabled: Boolean) {
        context.dataStore.edit { it[Keys.INCREMENTAL_FILE_NAMES] = enabled }
    }
}
