package com.chargenap.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 설정 저장소. DataStore 파일 이름은 `settings`.
 *
 * 읽을 때와 쓸 때 모두 [normalized]를 거치므로 [settings]는 항상 허용 범위 안의 값이다.
 * 파일을 읽지 못하면 기본값을 낸다.
 */
class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val settings: Flow<AppSettings> = store.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> read(prefs) }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs)).normalized()) }
    }

    private fun read(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            reclineLevel = prefs[RECLINE] ?: defaults.reclineLevel,
            temperatureC = prefs[TEMPERATURE] ?: defaults.temperatureC,
            fanLevel = prefs[FAN] ?: defaults.fanLevel,
            targetPercent = prefs[TARGET] ?: defaults.targetPercent,
            seatControlEnabled = prefs[SEAT_CONTROL] ?: defaults.seatControlEnabled,
            demoMode = prefs[DEMO_MODE] ?: defaults.demoMode,
            voiceEnabled = prefs[VOICE] ?: defaults.voiceEnabled,
        ).normalized()
    }

    private fun write(prefs: MutablePreferences, settings: AppSettings) {
        prefs[RECLINE] = settings.reclineLevel
        prefs[TEMPERATURE] = settings.temperatureC
        prefs[FAN] = settings.fanLevel
        prefs[TARGET] = settings.targetPercent
        prefs[SEAT_CONTROL] = settings.seatControlEnabled
        prefs[DEMO_MODE] = settings.demoMode
        prefs[VOICE] = settings.voiceEnabled
    }

    private companion object {
        val RECLINE = intPreferencesKey("recline_level")
        val TEMPERATURE = floatPreferencesKey("temperature_c")
        val FAN = intPreferencesKey("fan_level")
        val TARGET = intPreferencesKey("target_percent")
        val SEAT_CONTROL = booleanPreferencesKey("seat_control_enabled")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val VOICE = booleanPreferencesKey("voice_enabled")
    }
}
