package com.github.gutugutu3030.autodarkmode.prototype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrototypeAppearanceControllerTest {
    @Test
    fun currentAppearanceParsesDarkMode() {
        val controller = PrototypeSystemAppearanceController(
            appleScriptRunner = FakeAppleScriptRunner(
                PrototypeAppleScriptResult(exitCode = 0, output = "true\n"),
            ),
        )

        assertEquals(PrototypeAppearance.Dark, controller.currentAppearance())
    }

    @Test
    fun currentAppearanceReturnsNullOnUnexpectedResponse() {
        val controller = PrototypeSystemAppearanceController(
            appleScriptRunner = FakeAppleScriptRunner(
                PrototypeAppleScriptResult(exitCode = 0, output = "maybe\n"),
            ),
        )

        assertNull(controller.currentAppearance())
    }

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

private class FakeAppleScriptRunner(
    private val result: PrototypeAppleScriptResult,
) : PrototypeAppleScriptRunner {
    override fun run(script: String): PrototypeAppleScriptResult {
        return result
    }
}

private class RecordingAppleScriptRunner(
    private val result: PrototypeAppleScriptResult,
) : PrototypeAppleScriptRunner {
    val scripts = mutableListOf<String>()

    override fun run(script: String): PrototypeAppleScriptResult {
        scripts += script
        return result
    }
}