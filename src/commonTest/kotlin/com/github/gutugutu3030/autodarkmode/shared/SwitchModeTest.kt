package com.github.gutugutu3030.autodarkmode.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchModeTest {
    @Test
    fun raw_values_are_stable_for_persistence() {
        assertEquals("off", SwitchMode.Off.rawValue)
        assertEquals("auto", SwitchMode.Auto.rawValue)
        assertEquals("manual", SwitchMode.Manual.rawValue)
    }

    @Test
    fun round_trip_through_raw_value() {
        SwitchMode.entries.forEach { mode ->
            assertEquals(mode, SwitchMode.fromRawValue(mode.rawValue))
        }
    }

    @Test
    fun invalid_raw_value_returns_null() {
        assertNull(SwitchMode.fromRawValue("unknown"))
        assertNull(SwitchMode.fromRawValue(""))
    }

    @Test
    fun all_modes_have_non_empty_display_name() {
        SwitchMode.entries.forEach { mode ->
            assertTrue(mode.displayName.isNotEmpty())
            assertNotNull(mode.menuDescription)
        }
    }
}