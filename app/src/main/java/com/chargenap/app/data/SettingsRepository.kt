package com.chargenap.app.data

import android.content.Context
import android.util.Log
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

    /**
     * 설정을 바꾼다. 저장에 실패하면(디스크 부족 등) 예외를 던지지 않고 false를 돌려준다.
     * 앱이 죽는 것보다 설정 한 번이 저장되지 않는 편이 낫다.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings): Boolean = try {
        store.edit { prefs -> write(prefs, transform(read(prefs)).normalized()) }
        true
    } catch (e: IOException) {
        Log.w(TAG, "설정을 저장하지 못했습니다", e)
        false
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
        const val TAG = "ChargeNapSettings"
        val RECLINE = intPreferencesKey("recline_level")
        val TEMPERATURE = floatPreferencesKey("temperature_c")
        val FAN = intPreferencesKey("fan_level")
        val TARGET = intPreferencesKey("target_percent")
        val SEAT_CONTROL = booleanPreferencesKey("seat_control_enabled")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val VOICE = booleanPreferencesKey("voice_enabled")
    }
}
