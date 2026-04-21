package com.github.gutugutu3030.autodarkmode.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * AppleScript ベースの外観コントローラを検証します。
 */
class PrototypeAppearanceControllerTest {
    /**
     * `true` の応答が Dark に解釈されることを確認します。
     */
    @Test
    fun currentAppearanceParsesDarkMode() {
        val controller = PrototypeSystemAppearanceController(
            appleScriptRunner = FakeAppleScriptRunner(
                PrototypeAppleScriptResult(exitCode = 0, output = "true\n"),
            ),
        )

        assertEquals(PrototypeAppearance.Dark, controller.currentAppearance())
    }

    /**
     * 想定外の応答は `null` になることを確認します。
     */
    @Test
    fun currentAppearanceReturnsNullOnUnexpectedResponse() {
        val controller = PrototypeSystemAppearanceController(
            appleScriptRunner = FakeAppleScriptRunner(
                PrototypeAppleScriptResult(exitCode = 0, output = "maybe\n"),
            ),
        )

        assertNull(controller.currentAppearance())
    }

    /**
     * 外観切り替え失敗時のエラー整形を確認します。
     */
    @Test
    fun setAppearanceReturnsOsascriptFailure() {
        val controller = PrototypeSystemAppearanceController(
            appleScriptRunner = FakeAppleScriptRunner(
                PrototypeAppleScriptResult(exitCode = 1, output = "execution error"),
            ),
        )

        assertEquals(
            "osascript failed: execution error",
            controller.setAppearance(PrototypeAppearance.Dark),
        )
    }

    /**
     * Dark 切り替え時の AppleScript 文字列を確認します。
     */
    @Test
    fun setAppearanceSendsDarkModeScript() {
        val runner = RecordingAppleScriptRunner(
            PrototypeAppleScriptResult(exitCode = 0, output = ""),
        )
        val controller = PrototypeSystemAppearanceController(runner)

        assertNull(controller.setAppearance(PrototypeAppearance.Dark))
        assertEquals(
            "tell application \"System Events\" to tell appearance preferences to set dark mode to true",
            runner.scripts.single(),
        )
    }
}

/**
 * 固定結果を返す AppleScript ランナーです。
 *
 * @param result 返却する結果です。
 */
private class FakeAppleScriptRunner(
    private val result: PrototypeAppleScriptResult,
) : PrototypeAppleScriptRunner {
    /**
     * 固定結果を返します。
     *
     * @param script 実行されたスクリプトです。
     * @return 事前定義した結果です。
     */
    override fun run(script: String): PrototypeAppleScriptResult {
        return result
    }
}

/**
 * 実行された AppleScript を記録するランナーです。
 *
 * @param result 返却する結果です。
 */
private class RecordingAppleScriptRunner(
    private val result: PrototypeAppleScriptResult,
) : PrototypeAppleScriptRunner {
    val scripts = mutableListOf<String>()

    /**
     * スクリプトを記録してから結果を返します。
     *
     * @param script 実行されたスクリプトです。
     * @return 事前定義した結果です。
     */
    override fun run(script: String): PrototypeAppleScriptResult {
        scripts += script
        return result
    }
}