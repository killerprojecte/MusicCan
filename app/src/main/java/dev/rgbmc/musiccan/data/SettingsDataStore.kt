package dev.rgbmc.musiccan.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "mirror_settings")

object SettingsDataStoreKeys {
    val targetPackages: Preferences.Key<Set<String>> = stringSetPreferencesKey("target_packages")

    // 兼容旧版本的单值键，若存在则迁移一次
    val legacySingle: Preferences.Key<String> = stringPreferencesKey("target_package")
}

class SettingsRepository(private val context: Context) {
    val targetPackagesFlow: Flow<Set<String>> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            val set = prefs[SettingsDataStoreKeys.targetPackages]
            if (set != null) return@map set
            val legacy = prefs[SettingsDataStoreKeys.legacySingle]
            if (legacy != null) setOf(legacy) else emptySet()
        }

    suspend fun setTargetPackages(packages: Set<String>) {
        context.dataStore.edit { prefs ->
            if (packages.isEmpty()) {
                prefs.remove(SettingsDataStoreKeys.targetPackages)
            } else {
                prefs[SettingsDataStoreKeys.targetPackages] = packages
            }
        }
    }

    suspend fun toggleTargetPackage(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[SettingsDataStoreKeys.targetPackages] ?: emptySet()
            prefs[SettingsDataStoreKeys.targetPackages] =
                if (current.contains(packageName)) current - packageName else current + packageName
        }
    }
}



