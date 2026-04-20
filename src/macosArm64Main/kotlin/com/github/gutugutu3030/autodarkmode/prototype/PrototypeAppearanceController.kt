@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.gutugutu3030.autodarkmode.prototype

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen

/**
 * 現在の macOS 外観を読み書きするための抽象です。
 */
internal interface PrototypeAppearanceController {
    /**
     * 現在の外観を取得します。
     *
     * @return 現在の外観。取得できない場合は `null` です。
     */
    fun currentAppearance(): PrototypeAppearance?

    /**
     * 指定した外観へ切り替えます。
     *
     * @param target 切り替え先の外観です。
     * @return エラー時は説明文、成功時は `null` です。
     */
    fun setAppearance(target: PrototypeAppearance): String?
}

/**
 * AppleScript 実行結果です。
 *
 * @property exitCode プロセスの終了コードです。
 * @property output 標準出力と標準エラーの結合結果です。
 */
internal data class PrototypeAppleScriptResult(
    val exitCode: Int,
    val output: String,
)

/**
 * AppleScript を実行する関数型インターフェースです。
 */
internal fun interface PrototypeAppleScriptRunner {
    /**
     * 指定した AppleScript を実行します。
     *
     * @param script 実行する AppleScript です。
     * @return 実行結果です。
     */
    fun run(script: String): PrototypeAppleScriptResult
}

/**
 * `osascript` を使って macOS の外観を操作します。
 */
internal class PrototypeSystemAppearanceController(
    private val appleScriptRunner: PrototypeAppleScriptRunner = PrototypeOsascriptRunner(),
) : PrototypeAppearanceController {
    /**
     * 現在の外観を読み出します。
     *
     * @return 現在の外観。失敗時は `null` です。
     */
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

    /**
     * 指定した外観へ切り替えます。
     *
     * @param target 切り替え先です。
     * @return 失敗時は説明文、成功時は `null` です。
     */
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

/**
 * `osascript` コマンドをシェル経由で実行します。
 */
internal class PrototypeOsascriptRunner : PrototypeAppleScriptRunner {
    /**
     * AppleScript を実行します。
     *
     * @param script 実行するスクリプトです。
     * @return 実行結果です。
     */
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

    /**
     * パイプから全出力を読み取ります。
     *
     * @param pipe 読み取り元です。
     * @return 読み取った文字列です。
     */
    private fun readAll(pipe: CPointer<FILE>): String {
        val output = StringBuilder()
        val buffer = ByteArray(1024)

        while (true) {
            // 1024 バイトずつ読み出して、途中で途切れても順番を保ちます。
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

    /**
     * シェル文字列として安全に埋め込めるようにクォートします。
     *
     * @param value エスケープ対象の文字列です。
     * @return エスケープ済み文字列です。
     */
    private fun shellEscape(value: String): String {
        return value.replace("'", "'\\''")
    }
}

/**
 * テストやメモリ内状態用の外観コントローラです。
 *
 * @param initialAppearance 初期外観です。
 */
internal class PrototypeInMemoryAppearanceController(
    initialAppearance: PrototypeAppearance = PrototypeAppearance.Light,
) : PrototypeAppearanceController {
    private var appearance: PrototypeAppearance = initialAppearance

    /**
     * 現在の外観を返します。
     *
     * @return 現在の外観です。
     */
    override fun currentAppearance(): PrototypeAppearance {
        return appearance
    }

    /**
     * 外観を更新します。
     *
     * @param target 切り替え先です。
     * @return 常に `null` です。
     */
    override fun setAppearance(target: PrototypeAppearance): String? {
        appearance = target
        return null
    }
}