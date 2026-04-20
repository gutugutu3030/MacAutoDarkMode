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

    @Test
    fun autoModeRequiresConsecutiveSamplesBeforeSwitch() {
        val persistedSettings = FakePersistedSettings(
            darkThresholdLux = 100.0,
            lightThresholdLux = 300.0,
            requiredConsecutiveSamples = 2,
        )
        val appearanceController = FakeAppearanceController(initialAppearance = PrototypeAppearance.Light)
        val stateStore = PrototypeStateStore(persistedSettings, appearanceController = appearanceController)

        stateStore.bootstrap(sensorAvailable = true)

        stateStore.onEngineTimerTick(
            reading = NativeAmbientLightReading(90.0, NativeAmbientLightSource.HID),
            sensorAvailable = true,
        )
        val firstSnapshot = stateStore.snapshot()
        assertEquals(PrototypeAppearance.Light, firstSnapshot.status.appearance)
        assertEquals("Dark candidate 1/2 at 90 lx.", firstSnapshot.status.message)

        stateStore.onEngineTimerTick(
            reading = NativeAmbientLightReading(80.0, NativeAmbientLightSource.HID),
            sensorAvailable = true,
        )
        val secondSnapshot = stateStore.snapshot()
        assertEquals(PrototypeAppearance.Dark, secondSnapshot.status.appearance)
        assertEquals("Ambient light dropped to 80 lx.", secondSnapshot.status.message)
    }

    @Test
    fun autoModeRespectsCooldownBeforeSwitchingBack() {
        val persistedSettings = FakePersistedSettings(
            darkThresholdLux = 100.0,
            lightThresholdLux = 300.0,
            requiredConsecutiveSamples = 1,
            cooldownSeconds = 30.0,
        )
        val appearanceController = FakeAppearanceController(initialAppearance = PrototypeAppearance.Light)
        var now = 100.0
        val stateStore = PrototypeStateStore(
            persistedSettings,
            appearanceController = appearanceController,
            nowProvider = { now },
        )

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.onEngineTimerTick(
            reading = NativeAmbientLightReading(90.0, NativeAmbientLightSource.HID),
            sensorAvailable = true,
        )

        now = 110.0
        stateStore.onEngineTimerTick(
            reading = NativeAmbientLightReading(400.0, NativeAmbientLightSource.HID),
            sensorAvailable = true,
        )

        val snapshot = stateStore.snapshot()
        assertEquals(PrototypeAppearance.Dark, snapshot.status.appearance)
        assertEquals("Cooldown active. Next change allowed in 20s.", snapshot.status.message)
    }

    @Test
    fun autoModeReportsAppearanceControllerFailure() {
        val persistedSettings = FakePersistedSettings(
            darkThresholdLux = 100.0,
            lightThresholdLux = 300.0,
            requiredConsecutiveSamples = 1,
        )
        val appearanceController = FakeAppearanceController(
            initialAppearance = PrototypeAppearance.Light,
            nextError = "osascript failed",
        )
        val stateStore = PrototypeStateStore(persistedSettings, appearanceController = appearanceController)

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.onEngineTimerTick(
            reading = NativeAmbientLightReading(80.0, NativeAmbientLightSource.HID),
            sensorAvailable = true,
        )

        val snapshot = stateStore.snapshot()
        assertEquals(PrototypeAppearance.Light, snapshot.status.appearance)
        assertEquals("Failed to change appearance.", snapshot.status.message)
        assertEquals("osascript failed", snapshot.status.lastError)
    }
}

private class FakePersistedSettings(
    var mode: PrototypeMode = PrototypeMode.Auto,
    var darkThresholdLux: Double = 180.0,
    var lightThresholdLux: Double = 420.0,
    var requiredConsecutiveSamples: Int = 3,
    var cooldownSeconds: Double = 30.0,
) : PrototypePersistedSettingsClient {
    var lastPreset: PrototypeThresholdPreset? = null

    override fun currentSnapshot(): PrototypePersistedSettingsSnapshot {
        return PrototypePersistedSettingsSnapshot(
            mode = mode,
            darkThresholdLux = darkThresholdLux,
            lightThresholdLux = lightThresholdLux,
            requiredConsecutiveSamples = requiredConsecutiveSamples,
            cooldownSeconds = cooldownSeconds,
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

private class FakeAppearanceController(
    initialAppearance: PrototypeAppearance,
    private var nextError: String? = null,
) : PrototypeAppearanceController {
    private var appearance = initialAppearance

    override fun currentAppearance(): PrototypeAppearance {
        return appearance
    }

    override fun setAppearance(target: PrototypeAppearance): String? {
        val error = nextError
        nextError = null
        if (error != null) {
            return error
        }

        appearance = target
        return null
    }
}