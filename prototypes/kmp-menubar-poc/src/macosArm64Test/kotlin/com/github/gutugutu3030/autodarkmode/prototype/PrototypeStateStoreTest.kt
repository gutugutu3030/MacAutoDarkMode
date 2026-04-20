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

    @Test
    fun burstMutationsStayCoalescedUntilFlush() {
        val persistedSettings = FakePersistedSettings()
        val stateStore = PrototypeStateStore(persistedSettings)

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.selectMode(PrototypeMode.Manual)
        stateStore.forceAppearance(PrototypeAppearance.Dark)
        stateStore.sampleNow()

        val beforeFlush = stateStore.snapshot()
        assertEquals(0, beforeFlush.stats.presentationFlushCount)
        assertEquals(3, beforeFlush.stats.pendingMutationsSinceLastFlush)

        val afterFlush = stateStore.recordFlush()
        assertEquals(1, afterFlush.stats.presentationFlushCount)
        assertEquals(3, afterFlush.stats.coalescedMutationCount)
        assertEquals(3, afterFlush.stats.maxMutationsPerFlush)
        assertEquals(0, afterFlush.stats.pendingMutationsSinceLastFlush)
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