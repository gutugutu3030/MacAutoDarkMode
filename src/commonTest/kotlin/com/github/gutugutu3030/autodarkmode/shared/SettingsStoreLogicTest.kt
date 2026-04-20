package com.github.gutugutu3030.autodarkmode.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SettingsStoreLogic` の永続化と制約ルールを検証します。
 */
class SettingsStoreLogicTest {
    /**
     * 既定値が Auto になることを確認します。
     */
    @Test
    fun default_switch_mode_is_auto() {
        val keyValueStore = InMemoryKeyValueStore()

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Auto, store.switchMode)
    }

    /**
     * モード変更が保存先へ反映されることを確認します。
     */
    @Test
    fun switch_mode_persists_to_store() {
        val keyValueStore = InMemoryKeyValueStore()
        val store = SettingsStoreLogic(keyValueStore)

        store.setSwitchMode(SwitchMode.Manual)
        assertEquals("manual", keyValueStore.getString("switchMode"))

        store.setSwitchMode(SwitchMode.Off)
        assertEquals("off", keyValueStore.getString("switchMode"))

        store.setSwitchMode(SwitchMode.Auto)
        assertEquals("auto", keyValueStore.getString("switchMode"))
    }

    /**
     * 保存済みモードが初期化時に復元されることを確認します。
     */
    @Test
    fun switch_mode_restores_from_store_on_init() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setString("switchMode", "manual")
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Manual, store.switchMode)
    }

    /**
     * 無効なモード文字列は Auto に戻ることを確認します。
     */
    @Test
    fun invalid_switch_mode_string_falls_back_to_auto() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setString("switchMode", "invalid")
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Auto, store.switchMode)
    }

    /**
     * 旧 `automationEnabled=true` を Auto へ移行することを確認します。
     */
    @Test
    fun migrates_legacy_automation_enabled_true_to_auto() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setBoolean("automationEnabled", true)
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Auto, store.switchMode)
        assertNull(keyValueStore.getBoolean("automationEnabled"))
    }

    /**
     * 旧 `automationEnabled=false` を Off へ移行することを確認します。
     */
    @Test
    fun migrates_legacy_automation_enabled_false_to_off() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setBoolean("automationEnabled", false)
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Off, store.switchMode)
        assertNull(keyValueStore.getBoolean("automationEnabled"))
    }

    /**
     * 既に新しいキーがある場合は旧フラグを移行しないことを確認します。
     */
    @Test
    fun does_not_migrate_when_switch_mode_already_exists() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setString("switchMode", "manual")
            setBoolean("automationEnabled", false)
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Manual, store.switchMode)
        assertNotNull(keyValueStore.getBoolean("automationEnabled"))
    }

    /**
     * しきい値やクールダウンの既定値が妥当であることを確認します。
     */
    @Test
    fun threshold_defaults_are_reasonable() {
        val keyValueStore = InMemoryKeyValueStore()

        val store = SettingsStoreLogic(keyValueStore)

        assertTrue(store.state.value.effectiveDarkThresholdLux > 0.0)
        assertTrue(store.state.value.effectiveLightThresholdLux > store.state.value.effectiveDarkThresholdLux)
        assertTrue(store.state.value.effectiveRequiredConsecutiveSamples >= 1)
        assertTrue(store.state.value.effectiveCooldownSeconds >= 5.0)
    }
}