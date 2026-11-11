package com.chargenap.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "history")

/**
 * 세션 기록 저장소. DataStore 파일 이름은 `history`. 기록 전체를 JSON 문자열 하나로 저장한다.
 *
 * [records]는 저장한 순서(오래된 것이 먼저)다. 최근 [MAX_RECORDS]건만 남긴다.
 */
class SessionHistoryRepository internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.historyDataStore)

    val records: Flow<List<SessionRecord>> = store.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> SessionRecordCodec.decode(prefs[RECORDS]) }
        .distinctUntilChanged()

    suspend fun add(record: SessionRecord) {
        store.edit { prefs ->
            val next = SessionRecordCodec.decode(prefs[RECORDS]) + record
            prefs[RECORDS] = SessionRecordCodec.encode(next.takeLast(MAX_RECORDS))
        }
    }

    companion object {
        const val MAX_RECORDS = 200
        private val RECORDS = stringPreferencesKey("records")
    }
}
