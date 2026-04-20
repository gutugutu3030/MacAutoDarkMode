package com.github.gutugutu3030.autodarkmode.shared

import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `NSUserDefaultsKeyValueStore` と共有設定ロジックの永続化を検証します。
 */
class NSUserDefaultsKeyValueStoreTest {
    /**
     * 各値型の往復保存を確認します。
     */
    @Test
    fun round_trip_string_double_int_and_boolean_values() {
        withIsolatedDefaults { defaults, keyValueStore ->
            keyValueStore.setString("switchMode", "manual")
            keyValueStore.setDouble("darkThresholdLux", 1234.0)
            keyValueStore.setInt("requiredConsecutiveSamples", 4)
            keyValueStore.setBoolean("automationEnabled", true)

            assertEquals("manual", keyValueStore.getString("switchMode"))
            assertEquals(1234.0, keyValueStore.getDouble("darkThresholdLux"))
            assertEquals(4, keyValueStore.getInt("requiredConsecutiveSamples"))
            assertEquals(true, keyValueStore.getBoolean("automationEnabled"))

            assertEquals("manual", defaults.stringForKey("switchMode"))
            assertEquals(1234.0, defaults.doubleForKey("darkThresholdLux"))
            assertEquals(4, defaults.integerForKey("requiredConsecutiveSamples").toInt())
            assertEquals(true, defaults.boolForKey("automationEnabled"))
        }
    }

    /**
     * 値削除と欠損時の `null` を確認します。
     */
    @Test
    fun remove_clears_value_and_absence_stays_nullable() {
        withIsolatedDefaults { _, keyValueStore ->
            keyValueStore.setBoolean("automationEnabled", true)
            assertNotNull(keyValueStore.getBoolean("automationEnabled"))

            keyValueStore.remove("automationEnabled")

            assertNull(keyValueStore.getBoolean("automationEnabled"))
            assertNull(keyValueStore.getString("missingString"))
            assertNull(keyValueStore.getDouble("missingDouble"))
            assertNull(keyValueStore.getInt("missingInt"))
        }
    }

    /**
     * 共有ロジックが `NSUserDefaults` へそのまま書き戻せることを確認します。
     */
    @Test
    fun settings_store_logic_round_trips_through_nsuserdefaults_backed_store() {
        withIsolatedDefaults { _, keyValueStore ->
            val logic = SettingsStoreLogic(keyValueStore)

            logic.setSwitchMode(SwitchMode.Manual)
            logic.updateDarkThresholdLux(2500.0)
            logic.updateLightThresholdLux(9000.0)
            logic.updateRequiredConsecutiveSamples(5)
            logic.updateCooldownSeconds(42.0)

            val reloaded = SettingsStoreLogic(keyValueStore)

            assertEquals(SwitchMode.Manual, reloaded.switchMode)
            assertEquals(2500.0, reloaded.darkThresholdLux)
            assertEquals(9000.0, reloaded.lightThresholdLux)
            assertEquals(5, reloaded.requiredConsecutiveSamples)
            assertEquals(42.0, reloaded.cooldownSeconds)
            assertTrue(reloaded.state.value.effectiveLightThresholdLux > reloaded.state.value.effectiveDarkThresholdLux)
        }
    }

    /**
     * 各テストを独立した `NSUserDefaults` で実行します。
     *
     * @param block テスト本体です。
     */
    private fun withIsolatedDefaults(block: (NSUserDefaults, NSUserDefaultsKeyValueStore) -> Unit) {
        val suiteName = "test.autoDarkMode.kmp.${Random.nextLong().toString().replace('-', '0')}"
        val defaults = NSUserDefaults(suiteName = suiteName)
            ?: error("Failed to create isolated NSUserDefaults suite: $suiteName")
        val keyValueStore = NSUserDefaultsKeyValueStore(defaults)

        try {
            block(defaults, keyValueStore)
        } finally {
            defaults.removePersistentDomainForName(suiteName)
        }
    }
}