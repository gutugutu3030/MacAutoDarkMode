package com.github.gutugutu3030.autodarkmode.prototype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrototypeStateStoreTest {
    @Test
    fun menuSelectionMutatesStateWithoutNotification() {
        val persistedSettings = FakePersistedSettings()
        val stateStore = PrototypeStateStore(persistedSettings)

        stateStore.bootstrap(sensorAvailable = true)

        stateStore.selectMode(PrototypeMode.Manual)
        val snapshot = stateStore.snapshot()

        assertEquals(PrototypeMode.Manual, snapshot.status.mode)
        assertEquals(PrototypeMode.Manual, persistedSettings.mode)
        assertEquals(1, snapshot.stats.settingsEventCount)
    }

    @Test
    fun persistedSettingsReloadIsSupplementalWhenNoDiff() {
        val persistedSettings = FakePersistedSettings()
        val stateStore = PrototypeStateStore(persistedSettings)

        stateStore.bootstrap(sensorAvailable = true)

        assertFalse(stateStore.reloadPersistedSettings(trigger = "test-notification"))

        persistedSettings.mode = PrototypeMode.Off
        assertTrue(stateStore.reloadPersistedSettings(trigger = "test-notification"))

        val snapshot = stateStore.snapshot()
        assertEquals(PrototypeMode.Off, snapshot.status.mode)
        assertEquals(1, snapshot.stats.settingsEventCount)
    }

    @Test
    fun thresholdPresetDirectlyUpdatesSingleOwnedState() {
        val persistedSettings = FakePersistedSettings()
        val stateStore = PrototypeStateStore(persistedSettings)

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.applyThresholdPreset(PrototypeThresholdPreset.BrightRoom)

        val snapshot = stateStore.snapshot()
        assertEquals(PrototypeThresholdPreset.BrightRoom.darkThresholdLux, snapshot.status.darkThresholdLux)
        assertEquals(PrototypeThresholdPreset.BrightRoom.lightThresholdLux, snapshot.status.lightThresholdLux)
        assertEquals(PrototypeThresholdPreset.BrightRoom, persistedSettings.lastPreset)
    }
}

private class FakePersistedSettings : PrototypePersistedSettingsClient {
    var mode: PrototypeMode = PrototypeMode.Auto
    var darkThresholdLux: Double = 180.0
    var lightThresholdLux: Double = 420.0
    var lastPreset: PrototypeThresholdPreset? = null

    override fun currentSnapshot(): PrototypePersistedSettingsSnapshot {
        return PrototypePersistedSettingsSnapshot(
            mode = mode,
            darkThresholdLux = darkThresholdLux,
            lightThresholdLux = lightThresholdLux,
        )
    }

    override fun persistMode(mode: PrototypeMode) {
        this.mode = mode
    }

    override fun persistThresholdPreset(preset: PrototypeThresholdPreset) {
        lastPreset = preset
        darkThresholdLux = preset.darkThresholdLux
        lightThresholdLux = preset.lightThresholdLux
    }
}