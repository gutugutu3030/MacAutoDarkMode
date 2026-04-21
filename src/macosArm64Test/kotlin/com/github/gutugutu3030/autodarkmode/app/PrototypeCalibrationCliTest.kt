package com.github.gutugutu3030.autodarkmode.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * キャリブレーション CLI の引数解釈と推奨値を検証します。
 */
class PrototypeCalibrationCliTest {
    /**
     * コマンド判定が正しいことを確認します。
     */
    @Test
    fun recognizes_sample_watch_and_appearance_commands() {
        assertTrue(PrototypeCalibrationCli.canHandle(listOf("sample")))
        assertTrue(PrototypeCalibrationCli.canHandle(listOf("watch")))
        assertTrue(PrototypeCalibrationCli.canHandle(listOf("appearance")))
        assertFalse(PrototypeCalibrationCli.canHandle(listOf()))
        assertFalse(PrototypeCalibrationCli.canHandle(listOf("menu")))
    }

    /**
     * オプションの既定値と下限補正を確認します。
     */
    @Test
    fun parses_count_and_interval_with_clamping() {
        val options = PrototypeCalibrationCli.parseOptions(listOf("--count", "0", "--interval", "0.01"))

        assertEquals(1, options.count)
        assertEquals(0.1, options.intervalSeconds)
    }

    /**
     * 中央値から推奨しきい値が変わることを確認します。
     */
    @Test
    fun recommends_thresholds_from_median_lux() {
        assertEquals(
            PrototypeCalibrationCli.Recommendation(120.0, 1500.0, 2),
            PrototypeCalibrationCli.recommendedThresholdPreset(200.0),
        )
        assertEquals(
            PrototypeCalibrationCli.Recommendation(800.0, 5000.0, 2),
            PrototypeCalibrationCli.recommendedThresholdPreset(1200.0),
        )
        assertEquals(
            PrototypeCalibrationCli.Recommendation(3000.0, 12000.0, 3),
            PrototypeCalibrationCli.recommendedThresholdPreset(12000.0),
        )
    }
}