@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.gutugutu3030.autodarkmode.app

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fread
import platform.posix.pclose
import platform.posix.popen

/**
 * 現在の macOS 外観を読み書きするための抽象です。
 */
internal interface AppearanceController {
    /**
     * 現在の外観を取得します。
     *
     * @return 現在の外観。取得できない場合は `null` です。
     */
    fun currentAppearance(): Appearance?

    /**
     * 指定した外観へ切り替えます。
     *
     * @param target 切り替え先の外観です。
     * @return エラー時は説明文、成功時は `null` です。
     */
    fun setAppearance(target: Appearance): String?
}

/**
 * AppleScript 実行結果です。
 *
 * @property exitCode プロセスの終了コードです。
 * @property output 標準出力と標準エラーの結合結果です。
 */
internal data class AppleScriptResult(
    val exitCode: Int,
    val output: String,
)

internal fun normalizeProcessExitCode(status: Int): Int {
    if (status < 0) {
        return -1
    }

    return if ((status and 0x7f) == 0) {
        (status ushr 8) and 0xff
    } else {
        -1
    }
}

/**
 * AppleScript を実行する関数型インターフェースです。
 */
internal fun interface AppleScriptRunner {
    /**
     * 指定した AppleScript を実行します。
     *
     * @param script 実行する AppleScript です。
     * @return 実行結果です。
     */
    fun run(script: String): AppleScriptResult
}

/**
 * `osascript` を使って macOS の外観を操作します。
 */
internal class SystemAppearanceController(
    private val appleScriptRunner: AppleScriptRunner = OsascriptRunner(),
) : AppearanceController {
    /**
     * 現在の外観を読み出します。
     *
     * @return 現在の外観。失敗時は `null` です。
     */
    override fun currentAppearance(): Appearance? {
        val result = appleScriptRunner.run(
            "tell application \"System Events\" to tell appearance preferences to get dark mode",
        )
        if (result.exitCode != 0) {
            return null
        }

        return when (result.output.trim().lowercase()) {
            "true" -> Appearance.Dark
            "false" -> Appearance.Light
            else -> null
        }
    }

    /**
     * 指定した外観へ切り替えます。
     *
     * @param target 切り替え先です。
     * @return 失敗時は説明文、成功時は `null` です。
     */
    override fun setAppearance(target: Appearance): String? {
        val darkModeValue = when (target) {
            Appearance.Light -> "false"
            Appearance.Dark -> "true"
        }
        val result = appleScriptRunner.run(
            "tell application \"System Events\" to tell appearance preferences to set dark mode to $darkModeValue",
        )
        if (result.exitCode == 0) {
            return null
        }

        val output = result.output.trim().ifEmpty { "unknown osascript error" }
        return "osascript failed: $output"
    }
}

/**
 * `osascript` コマンドをシェル経由で実行します。
 */
internal class OsascriptRunner : AppleScriptRunner {
    /**
     * AppleScript を実行します。
     *
     * @param script 実行するスクリプトです。
     * @return 実行結果です。
     */
    override fun run(script: String): AppleScriptResult {
        val command = "/usr/bin/osascript -e '${shellEscape(script)}' 2>&1"
        val pipe = popen(command, "r") ?: return AppleScriptResult(
            exitCode = 1,
            output = "failed to open osascript pipe",
        )

        val output = readAll(pipe)
        val status = pclose(pipe)
        return AppleScriptResult(exitCode = normalizeProcessExitCode(status), output = output)
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
internal class InMemoryAppearanceController(
    initialAppearance: Appearance = Appearance.Light,
) : AppearanceController {
    private var appearance: Appearance = initialAppearance

    /**
     * 現在の外観を返します。
     *
     * @return 現在の外観です。
     */
    override fun currentAppearance(): Appearance {
        return appearance
    }

    /**
     * 外観を更新します。
     *
     * @param target 切り替え先です。
     * @return 常に `null` です。
     */
    override fun setAppearance(target: Appearance): String? {
        appearance = target
        return null
    }
}
