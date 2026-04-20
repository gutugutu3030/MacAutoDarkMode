package com.github.gutugutu3030.autodarkmode.prototype

import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrototypePersistedSettingsIntegrationTest {
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
        }
    }

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

private object NSUUIDString {
    private var nextValue = 0

    fun next(): String {
        nextValue += 1
        return nextValue.toString()
    }
}