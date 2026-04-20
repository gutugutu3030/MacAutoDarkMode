package com.github.gutugutu3030.autodarkmode.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SwitchMode` の raw 値と表示情報を検証します。
 */
class SwitchModeTest {
    /**
     * raw 値が永続化向けに安定していることを確認します。
     */
    @Test
    fun raw_values_are_stable_for_persistence() {
        assertEquals("off", SwitchMode.Off.rawValue)
        assertEquals("auto", SwitchMode.Auto.rawValue)
        assertEquals("manual", SwitchMode.Manual.rawValue)
    }

    /**
     * raw 値からの往復変換ができることを確認します。
     */
    @Test
    fun round_trip_through_raw_value() {
        SwitchMode.entries.forEach { mode ->
            assertEquals(mode, SwitchMode.fromRawValue(mode.rawValue))
        }
    }

    /**
     * 無効な raw 値は `null` になることを確認します。
     */
    @Test
    fun invalid_raw_value_returns_null() {
        assertNull(SwitchMode.fromRawValue("unknown"))
        assertNull(SwitchMode.fromRawValue(""))
    }

    /**
     * すべてのモードが表示用情報を持つことを確認します。
     */
    @Test
    fun all_modes_have_non_empty_display_name() {
        SwitchMode.entries.forEach { mode ->
            assertTrue(mode.displayName.isNotEmpty())
            assertNotNull(mode.menuDescription)
        }
    }
}