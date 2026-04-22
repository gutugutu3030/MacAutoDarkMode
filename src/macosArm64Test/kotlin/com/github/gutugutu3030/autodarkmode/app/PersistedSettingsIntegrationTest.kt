package com.github.gutugutu3030.autodarkmode.app

import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 永続化設定が共有ロジックと同じキーを使うことを確認します。
 */
class PersistedSettingsIntegrationTest {
    /**
     * プリセット名としきい値の強さが一致していることを確認します。
     */
    @Test
    fun threshold_presets_match_room_brightness_intent() {
        assertEquals(40.0, ThresholdPreset.DimRoom.darkThresholdLux)
        assertEquals(80.0, ThresholdPreset.DimRoom.lightThresholdLux)
        assertEquals(140.0, ThresholdPreset.BrightRoom.darkThresholdLux)
        assertEquals(260.0, ThresholdPreset.BrightRoom.lightThresholdLux)
        assertTrue(ThresholdPreset.BrightRoom.darkThresholdLux > ThresholdPreset.DimRoom.darkThresholdLux)
        assertTrue(ThresholdPreset.BrightRoom.lightThresholdLux > ThresholdPreset.DimRoom.lightThresholdLux)
    }

    /**
     * 共有設定の raw 値とプリセット反映を確認します。
     */
    @Test
    fun uses_production_userdefaults_keys_and_raw_values() {
        withIsolatedDefaults { defaults ->
            val persistedSettings = PersistedSettings(defaults)

            persistedSettings.persistMode(Mode.Manual)
            persistedSettings.persistThresholdPreset(ThresholdPreset.BrightRoom)

            assertEquals("manual", defaults.stringForKey("switchMode"))
            assertEquals(ThresholdPreset.BrightRoom.darkThresholdLux, defaults.doubleForKey("darkThresholdLux"))
            assertEquals(ThresholdPreset.BrightRoom.lightThresholdLux, defaults.doubleForKey("lightThresholdLux"))

            val snapshot = persistedSettings.currentSnapshot()
            assertEquals(Mode.Manual, snapshot.mode)
            assertEquals(ThresholdPreset.BrightRoom.darkThresholdLux, snapshot.darkThresholdLux)
            assertEquals(ThresholdPreset.BrightRoom.lightThresholdLux, snapshot.lightThresholdLux)
            assertEquals(3, snapshot.requiredConsecutiveSamples)
            assertEquals(30.0, snapshot.cooldownSeconds)
        }
    }

    /**
     * 共有ロジックの制約と既定値がそのまま反映されることを確認します。
     */
    @Test
    fun current_snapshot_honors_shared_logic_clamping_and_defaults() {
        withIsolatedDefaults { defaults ->
            defaults.setDouble(2000.0, forKey = "darkThresholdLux")
            defaults.setDouble(1000.0, forKey = "lightThresholdLux")

            val snapshot = PersistedSettings(defaults).currentSnapshot()

            assertEquals(Mode.Auto, snapshot.mode)
            assertEquals(2000.0, snapshot.darkThresholdLux)
            assertEquals(2000.0, snapshot.lightThresholdLux)
            assertTrue(snapshot.lightThresholdLux >= snapshot.darkThresholdLux)
        }
    }

    /**
     * 各テストを独立した `NSUserDefaults` で実行します。
     *
     * @param block テスト本体です。
     */
    private fun withIsolatedDefaults(block: (NSUserDefaults) -> Unit) {
        val suiteName = "PersistedSettingsIntegrationTest.${NSUUIDString.next()}"
        val defaults = NSUserDefaults(suiteName = suiteName)
            ?: error("Failed to create isolated NSUserDefaults suite: $suiteName")

        try {
            block(defaults)
        } finally {
            defaults.removePersistentDomainForName(suiteName)
        }
    }
}

/**
 * テスト用の一時 ID を発行します。
 */
private object NSUUIDString {
    private var nextValue = 0

    /**
     * 次の ID を返します。
     *
     * @return 増分済み ID です。
     */
    fun next(): String {
        nextValue += 1
        return nextValue.toString()
    }
}
