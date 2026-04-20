package com.github.gutugutu3030.autodarkmode.prototype

import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * プロトタイプ永続化設定が共有ロジックと同じキーを使うことを確認します。
 */
class PrototypePersistedSettingsIntegrationTest {
    /**
     * 共有設定の raw 値とプリセット反映を確認します。
     */
    @Test
    fun uses_production_userdefaults_keys_and_raw_values() {
        withIsolatedDefaults { defaults ->
            val persistedSettings = PrototypePersistedSettings(defaults)

            persistedSettings.persistMode(PrototypeMode.Manual)
            persistedSettings.persistThresholdPreset(PrototypeThresholdPreset.BrightRoom)

            assertEquals("manual", defaults.stringForKey("switchMode"))
            assertEquals(PrototypeThresholdPreset.BrightRoom.darkThresholdLux, defaults.doubleForKey("darkThresholdLux"))
            assertEquals(PrototypeThresholdPreset.BrightRoom.lightThresholdLux, defaults.doubleForKey("lightThresholdLux"))

            val snapshot = persistedSettings.currentSnapshot()
            assertEquals(PrototypeMode.Manual, snapshot.mode)
            assertEquals(PrototypeThresholdPreset.BrightRoom.darkThresholdLux, snapshot.darkThresholdLux)
            assertEquals(PrototypeThresholdPreset.BrightRoom.lightThresholdLux, snapshot.lightThresholdLux)
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

            val snapshot = PrototypePersistedSettings(defaults).currentSnapshot()

            assertEquals(PrototypeMode.Auto, snapshot.mode)
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
        val suiteName = "PrototypePersistedSettingsIntegrationTest.${NSUUIDString.next()}"
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