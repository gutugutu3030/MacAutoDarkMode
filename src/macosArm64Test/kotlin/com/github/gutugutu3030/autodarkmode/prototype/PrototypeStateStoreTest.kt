package com.github.gutugutu3030.autodarkmode.prototype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * プロトタイプの状態遷移と切り替えルールを検証します。
 */
class PrototypeStateStoreTest {
    /**
     * メニュー選択が通知なしで状態に反映されることを確認します。
     */
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

    /**
     * 永続化設定の再読込が差分ありのときだけ反映されることを確認します。
     */
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

    /**
     * しきい値プリセットが単一の設定元へ反映されることを確認します。
     */
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

    /**
     * 設定ウィンドウ由来のしきい値変更が共有ロジックを経由して戻ることを確認します。
     */
    @Test
    fun settingsWindowThresholdUpdatesRoundTripThroughSharedLogic() {
        val persistedSettings = FakePersistedSettings(darkThresholdLux = 500.0, lightThresholdLux = 1500.0)
        val stateStore = PrototypeStateStore(persistedSettings)

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.updateDarkThresholdLux(900.0)
        stateStore.updateLightThresholdLux(600.0)

        val snapshot = stateStore.snapshot()
        assertEquals(900.0, snapshot.status.darkThresholdLux)
        assertEquals(900.0, snapshot.status.lightThresholdLux)
    }

    /**
     * 現在の lux をしきい値へ取り込めることを確認します。
     */
    @Test
    fun currentLuxCanBeCapturedIntoThresholds() {
        val persistedSettings = FakePersistedSettings(darkThresholdLux = 500.0, lightThresholdLux = 1500.0)
        val stateStore = PrototypeStateStore(persistedSettings)

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.sampleNow()
        val sampledLux = stateStore.snapshot().status.lux

        stateStore.useCurrentLuxAsDarkThreshold()
        stateStore.useCurrentLuxAsLightThreshold()

        val snapshot = stateStore.snapshot()
        assertEquals(sampledLux, snapshot.status.darkThresholdLux)
        assertEquals(sampledLux, snapshot.status.lightThresholdLux)
    }

    /**
     * 連続した変更がフラッシュまでまとめられることを確認します。
     */
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

    /**
     * Auto モードは連続サンプル数を満たすまで切り替えないことを確認します。
     */
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

    /**
     * Auto モードのクールダウンが再切り替えを抑制することを確認します。
     */
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

    /**
     * 外観切り替え失敗時にエラーが残ることを確認します。
     */
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

    /**
     * 権限不足でも手動モードの輝度追跡が続くことを確認します。
     */
    @Test
    fun manualModeReportsPermissionRequiredButStillTracksBrightness() {
        val persistedSettings = FakePersistedSettings(mode = PrototypeMode.Manual)
        val appearanceController = FakeAppearanceController(initialAppearance = PrototypeAppearance.Dark)
        val stateStore = PrototypeStateStore(persistedSettings, appearanceController = appearanceController)

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.reportManualBrightnessKeyMonitoringPermissionRequired()

        val permissionSnapshot = stateStore.snapshot()
        assertTrue(permissionSnapshot.status.manualBrightnessPermissionRequired)
        assertFalse(permissionSnapshot.status.manualBrightnessKeyMonitoringEnabled)

        stateStore.onBrightnessEvent(
            PrototypeBrightnessEvent(
                direction = PrototypeBrightnessDirection.Up,
                phase = PrototypeBrightnessPhase.Down,
                brightnessAfterSampling = 1.0,
            ),
        )
        val eventSnapshot = stateStore.snapshot()
        assertFalse(eventSnapshot.status.manualBrightnessHoldArmed)
        assertFalse(eventSnapshot.status.manualBrightnessRequiresReleaseAfterMax)
        assertEquals(PrototypeAppearance.Light, eventSnapshot.status.appearance)
    }

    /**
     * 最大到達後は一度離してから再度長押しする必要があることを確認します。
     */
    @Test
    fun manualModeRequiresReleaseBeforeSecondHoldAtMaximum() {
        val persistedSettings = FakePersistedSettings(mode = PrototypeMode.Manual)
        val appearanceController = FakeAppearanceController(initialAppearance = PrototypeAppearance.Dark)
        val stateStore = PrototypeStateStore(persistedSettings, appearanceController = appearanceController)

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.setManualBrightnessKeyMonitoringEnabled(true)

        stateStore.onBrightnessEvent(
            PrototypeBrightnessEvent(
                direction = PrototypeBrightnessDirection.Up,
                phase = PrototypeBrightnessPhase.Down,
                brightnessAfterSampling = 1.0,
            ),
        )

        val snapshot = stateStore.snapshot()
        assertTrue(snapshot.status.manualBrightnessRequiresReleaseAfterMax)
        assertFalse(snapshot.status.manualBrightnessHoldArmed)
        assertEquals(
            "Brightness at or near maximum (100%). Release Brightness Up once, then hold it again to switch to Light mode.",
            snapshot.status.message,
        )
    }

    /**
     * 長押し完了で Light 外観へ切り替わることを確認します。
     */
    @Test
    fun manualModeArmsHoldAfterReleaseAndSwitchesLightWhenHoldCompletes() {
        val persistedSettings = FakePersistedSettings(mode = PrototypeMode.Manual)
        val appearanceController = FakeAppearanceController(initialAppearance = PrototypeAppearance.Dark)
        val stateStore = PrototypeStateStore(persistedSettings, appearanceController = appearanceController)

        stateStore.bootstrap(sensorAvailable = true)
        stateStore.setManualBrightnessKeyMonitoringEnabled(true)

        stateStore.onBrightnessEvent(
            PrototypeBrightnessEvent(
                direction = PrototypeBrightnessDirection.Up,
                phase = PrototypeBrightnessPhase.Down,
                brightnessAfterSampling = 1.0,
            ),
        )
        stateStore.onBrightnessEvent(
            PrototypeBrightnessEvent(
                direction = PrototypeBrightnessDirection.Up,
                phase = PrototypeBrightnessPhase.Up,
                brightnessAfterSampling = 1.0,
            ),
        )
        stateStore.onBrightnessEvent(
            PrototypeBrightnessEvent(
                direction = PrototypeBrightnessDirection.Up,
                phase = PrototypeBrightnessPhase.Down,
                brightnessAfterSampling = 1.0,
            ),
        )

        val armedSnapshot = stateStore.snapshot()
        assertTrue(armedSnapshot.status.manualBrightnessHoldArmed)
        assertFalse(armedSnapshot.status.manualBrightnessRequiresReleaseAfterMax)
        assertEquals(
            "Brightness at or near maximum. Keep holding Brightness Up to switch to Light mode.",
            armedSnapshot.status.message,
        )

        stateStore.onManualBrightnessHoldTimerFired()
        val completedSnapshot = stateStore.snapshot()
        assertEquals(PrototypeAppearance.Light, completedSnapshot.status.appearance)
        assertFalse(completedSnapshot.status.manualBrightnessHoldArmed)
        assertEquals(
            "Held Brightness Up while display brightness was already at or near maximum.",
            completedSnapshot.status.message,
        )
    }
}

/**
 * テスト用の永続化設定クライアントです。
 */
private class FakePersistedSettings(
    var mode: PrototypeMode = PrototypeMode.Auto,
    var darkThresholdLux: Double = 180.0,
    var lightThresholdLux: Double = 420.0,
    var requiredConsecutiveSamples: Int = 3,
    var cooldownSeconds: Double = 30.0,
) : PrototypePersistedSettingsClient {
    var lastPreset: PrototypeThresholdPreset? = null

    /**
     * 現在のスナップショットを返します。
     *
     * @return 現在値です。
     */
    override fun currentSnapshot(): PrototypePersistedSettingsSnapshot {
        return PrototypePersistedSettingsSnapshot(
            mode = mode,
            darkThresholdLux = darkThresholdLux,
            lightThresholdLux = lightThresholdLux,
            requiredConsecutiveSamples = requiredConsecutiveSamples,
            cooldownSeconds = cooldownSeconds,
        )
    }

    /**
     * モードを保存します。
     *
     * @param mode 保存するモードです。
     */
    override fun persistMode(mode: PrototypeMode) {
        this.mode = mode
    }

    /**
     * しきい値を保存します。
     *
     * @param darkThresholdLux 暗い側しきい値です。
     * @param lightThresholdLux 明るい側しきい値です。
     */
    override fun persistThresholds(darkThresholdLux: Double, lightThresholdLux: Double) {
        this.darkThresholdLux = darkThresholdLux.coerceIn(0.0, 120000.0)
        this.lightThresholdLux = maxOf(lightThresholdLux.coerceIn(0.0, 120000.0), this.darkThresholdLux)
    }

    /**
     * プリセットを保存します。
     *
     * @param preset 保存するプリセットです。
     */
    override fun persistThresholdPreset(preset: PrototypeThresholdPreset) {
        lastPreset = preset
        persistThresholds(preset.darkThresholdLux, preset.lightThresholdLux)
    }
}

/**
 * テスト用の外観コントローラです。
 *
 * @param initialAppearance 初期外観です。
 * @param nextError 次の切り替え時に返すエラーです。
 */
private class FakeAppearanceController(
    initialAppearance: PrototypeAppearance,
    private var nextError: String? = null,
) : PrototypeAppearanceController {
    private var appearance = initialAppearance

    /**
     * 現在の外観を返します。
     *
     * @return 現在の外観です。
     */
    override fun currentAppearance(): PrototypeAppearance {
        return appearance
    }

    /**
     * 外観を切り替えます。
     *
     * @param target 切り替え先です。
     * @return エラーまたは `null` です。
     */
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