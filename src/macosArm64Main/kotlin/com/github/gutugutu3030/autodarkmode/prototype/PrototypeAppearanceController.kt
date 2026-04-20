@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.gutugutu3030.autodarkmode.prototype

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen

internal interface PrototypeAppearanceController {
    fun currentAppearance(): PrototypeAppearance?
    fun setAppearance(target: PrototypeAppearance): String?
}

internal data class PrototypeAppleScriptResult(
    val exitCode: Int,
    val output: String,
)

internal fun interface PrototypeAppleScriptRunner {
    fun run(script: String): PrototypeAppleScriptResult
}

internal class PrototypeSystemAppearanceController(
    private val appleScriptRunner: PrototypeAppleScriptRunner = PrototypeOsascriptRunner(),
) : PrototypeAppearanceController {
    override fun currentAppearance(): PrototypeAppearance? {
        val result = appleScriptRunner.run(
            "tell application \"System Events\" to tell appearance preferences to get dark mode",
        )
        if (result.exitCode != 0) {
            return null
        }

        return when (result.output.trim().lowercase()) {
            "true" -> PrototypeAppearance.Dark
            "false" -> PrototypeAppearance.Light
            else -> null
        }
    }

    override fun setAppearance(target: PrototypeAppearance): String? {
        val darkModeValue = when (target) {
            PrototypeAppearance.Light -> "false"
            PrototypeAppearance.Dark -> "true"
        }
        val result = appleScriptRunner.run(
            "tell application \"System Events\" to tell appearance preferences to set dark mode to ${darkModeValue}",
        )
        if (result.exitCode == 0) {
            return null
        }

        val output = result.output.trim().ifEmpty { "unknown osascript error" }
        return "osascript failed: ${output}"
    }
}

internal class PrototypeOsascriptRunner : PrototypeAppleScriptRunner {
    override fun run(script: String): PrototypeAppleScriptResult {
        val command = "/usr/bin/osascript -e '${shellEscape(script)}' 2>&1"
        val pipe = popen(command, "r") ?: return PrototypeAppleScriptResult(
            exitCode = 1,
            output = "failed to open osascript pipe",
        )

        val output = readAll(pipe)
        val status = pclose(pipe)
        return PrototypeAppleScriptResult(exitCode = status, output = output)
    }

    private fun readAll(pipe: CPointer<FILE>): String {
        val output = StringBuilder()
        val buffer = ByteArray(1024)

        while (true) {
            val readCount = buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), 1uL, buffer.size.toULong(), pipe)
            }
            if (readCount == 0UL) {
                break
            }
            output.append(buffer.decodeToString(endIndex = readCount.toInt()))
        }

        return output.toString()
    }

    private fun shellEscape(value: String): String {
        return value.replace("'", "'\\''")
    }
}

internal class PrototypeInMemoryAppearanceController(
    initialAppearance: PrototypeAppearance = PrototypeAppearance.Light,
) : PrototypeAppearanceController {
    private var appearance: PrototypeAppearance = initialAppearance

    override fun currentAppearance(): PrototypeAppearance {
        return appearance
    }

    override fun setAppearance(target: PrototypeAppearance): String? {
        appearance = target
        return null
    }
}