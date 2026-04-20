package com.github.gutugutu3030.autodarkmode.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreLogicTest {
    @Test
    fun default_switch_mode_is_auto() {
        val keyValueStore = InMemoryKeyValueStore()

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Auto, store.switchMode)
    }

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

    @Test
    fun switch_mode_restores_from_store_on_init() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setString("switchMode", "manual")
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Manual, store.switchMode)
    }

    @Test
    fun invalid_switch_mode_string_falls_back_to_auto() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setString("switchMode", "invalid")
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Auto, store.switchMode)
    }

    @Test
    fun migrates_legacy_automation_enabled_true_to_auto() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setBoolean("automationEnabled", true)
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Auto, store.switchMode)
        assertNull(keyValueStore.getBoolean("automationEnabled"))
    }

    @Test
    fun migrates_legacy_automation_enabled_false_to_off() {
        val keyValueStore = InMemoryKeyValueStore().apply {
            setBoolean("automationEnabled", false)
        }

        val store = SettingsStoreLogic(keyValueStore)

        assertEquals(SwitchMode.Off, store.switchMode)
        assertNull(keyValueStore.getBoolean("automationEnabled"))
    }

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