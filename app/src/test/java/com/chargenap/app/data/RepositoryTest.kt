package com.chargenap.app.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chargenap.app.session.SessionMode
import com.chargenap.app.session.WakeReason
import com.chargenap.app.vehicle.NapPreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryTest {

    private fun record(index: Int) = SessionRecord(
        startedAtMillis = index * 1_000L,
        endedAtMillis = index * 1_000L + 60_000L,
        mode = SessionMode.NAP,
        plannedMinutes = 20,
        sleptMinutes = 1,
        wakeReason = WakeReason.TIMER,
    )

    @Test
    fun settings_emptyStore_returnsDefaults() = runTest {
        val repository = SettingsRepository(InMemoryPreferencesStore())

        assertEquals(AppSettings(), repository.settings.first())
    }

    @Test
    fun settings_update_persistsAllFields() = runTest {
        val store = InMemoryPreferencesStore()
        val changed = AppSettings(
            reclineLevel = 40,
            temperatureC = 24.5f,
            fanLevel = 4,
            targetPercent = 90,
            seatControlEnabled = false,
            demoMode = true,
            voiceEnabled = false,
        )

        SettingsRepository(store).update { changed }

        // 같은 저장소를 보는 새 인스턴스(앱 재시작)에서도 같은 값이 나온다.
        assertEquals(changed, SettingsRepository(store).settings.first())
    }

    @Test
    fun settings_update_clampsAndSnapsToStep() = runTest {
        val repository = SettingsRepository(InMemoryPreferencesStore())

        repository.update { it.copy(reclineLevel = 97, temperatureC = 23.3f, fanLevel = 9, targetPercent = 52) }
        assertEquals(
            AppSettings(reclineLevel = 100, temperatureC = 23.5f, fanLevel = 5, targetPercent = 50),
            repository.settings.first(),
        )

        repository.update { it.copy(reclineLevel = 0, temperatureC = 10f, fanLevel = 0, targetPercent = 130) }
        assertEquals(
            AppSettings(reclineLevel = 20, temperatureC = 18f, fanLevel = 1, targetPercent = 100),
            repository.settings.first(),
        )
    }

    @Test
    fun settings_toSessionConfig_mapsPreset() {
        val config = AppSettings(reclineLevel = 40, temperatureC = 22f, fanLevel = 3, targetPercent = 90, seatControlEnabled = false)
            .toSessionConfig()

        assertEquals(NapPreset(40, 22f, 3), config.preset)
        assertEquals(90, config.targetPercent)
        assertEquals(false, config.seatControlEnabled)
    }

    @Test
    fun history_add_appendsInOrder() = runTest {
        val store = InMemoryPreferencesStore()
        val repository = SessionHistoryRepository(store)
        assertTrue(repository.records.first().isEmpty())

        repository.add(record(1))
        repository.add(record(2))

        assertEquals(listOf(record(1), record(2)), SessionHistoryRepository(store).records.first())
    }

    @Test
    fun history_keepsOnlyLatest200() = runTest {
        val repository = SessionHistoryRepository(InMemoryPreferencesStore())

        repeat(205) { repository.add(record(it)) }

        val records = repository.records.first()
        assertEquals(SessionHistoryRepository.MAX_RECORDS, records.size)
        assertEquals(record(5), records.first())
        assertEquals(record(204), records.last())
    }

    @Test
    fun history_brokenStoredValue_startsOverWithoutCrash() = runTest {
        val store = InMemoryPreferencesStore(preferencesOf(stringPreferencesKey("records") to "{broken"))
        val repository = SessionHistoryRepository(store)
        assertTrue(repository.records.first().isEmpty())

        repository.add(record(1))

        assertEquals(listOf(record(1)), repository.records.first())
    }

    @Test
    fun settings_writeFailure_returnsFalseWithoutCrash() = runTest {
        val repository = SettingsRepository(FailingWritePreferencesStore())
        assertFalse(repository.update { it.copy(fanLevel = 4) })
        assertEquals(AppSettings(), repository.settings.first())
    }

    @Test
    fun history_writeFailure_returnsFalseWithoutCrash() = runTest {
        val repository = SessionHistoryRepository(FailingWritePreferencesStore())
        assertFalse(repository.add(record(1)))
        assertTrue(repository.records.first().isEmpty())
    }
}
