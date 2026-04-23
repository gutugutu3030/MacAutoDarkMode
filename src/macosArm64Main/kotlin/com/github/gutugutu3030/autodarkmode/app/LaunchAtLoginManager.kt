package com.github.gutugutu3030.autodarkmode.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rewind

/**
 * Launch at login の管理状態を表します。
 *
 * @property canManageLaunchAgent LaunchAgent を操作できるかどうかです。
 * @property isEnabled 現在有効かどうかです。
 * @property statusMessage UI に出す状態メッセージです。
 * @property supportMessage サポート文言です。
 */
internal data class LaunchAtLoginSnapshot(
    val canManageLaunchAgent: Boolean,
    val isEnabled: Boolean,
    val statusMessage: String,
    val supportMessage: String,
)

/**
 * 実行環境のパス情報を抽象化します。
 */
internal interface LaunchAtLoginRuntimeInfo {
    /**
     * バンドルパスです。
     */
    val bundlePath: String?

    /**
     * 実行ファイルのパスです。
     */
    val executablePath: String?
}

/**
 * ファイルシステム操作を抽象化します。
 */
internal interface LaunchAtLoginFileSystem {
    /**
     * ホームディレクトリのパスです。
     */
    val homeDirectoryPath: String

    /**
     * ディレクトリを作成します。
     *
     * @param path 作成先のパスです。
     */
    fun createDirectory(path: String)

    /**
     * テキストを読み出します。
     *
     * @param path 読み出し対象のパスです。
     * @return 読み出した文字列。失敗時は `null` です。
     */
    fun readText(path: String): String?

    /**
     * テキストを書き込みます。
     *
     * @param path 書き込み先のパスです。
     * @param text 書き込む文字列です。
     */
    fun writeText(path: String, text: String)

    /**
     * ファイルを削除します。
     *
     * @param path 削除対象のパスです。
     */
    fun removeFile(path: String)
}

/**
 * LaunchAgent plist を管理します。
 *
 * @param runtimeInfo 実行環境の情報です。
 * @param fileSystem ファイルシステム操作です。
 */
internal class LaunchAtLoginManager(
    private val runtimeInfo: LaunchAtLoginRuntimeInfo = FoundationLaunchAtLoginRuntimeInfo(),
    private val fileSystem: LaunchAtLoginFileSystem = FoundationLaunchAtLoginFileSystem(),
) {
    private object Constants {
        const val launchAgentLabel = "com.gutugutu3030.autoDarkMode"
    }

    private var snapshot = LaunchAtLoginSnapshot(
        canManageLaunchAgent = false,
        isEnabled = false,
        statusMessage = "Launch at login is disabled.",
        supportMessage = unsupportedMessage,
    )

    /**
     * 現在のスナップショットを返します。
     *
     * @return 現在状態です。
     */
    fun snapshot(): LaunchAtLoginSnapshot = snapshot

    /**
     * ファイルシステム上の状態を再評価します。
     *
     * @return 再評価後のスナップショットです。
     */
    fun refresh(): LaunchAtLoginSnapshot {
        val executablePath = runtimeInfo.executablePath
        val canManage = canManageLaunchAgent()

        if (executablePath == null) {
            snapshot = LaunchAtLoginSnapshot(
                canManageLaunchAgent = false,
                isEnabled = false,
                statusMessage = "Launch at login is disabled.",
                supportMessage = unsupportedMessage,
            )
            return snapshot
        }

        // 現在の LaunchAgent が、このアプリを指しているか確認します。
        val plist = fileSystem.readText(launchAgentPath())
        if (plist == null) {
            snapshot = LaunchAtLoginSnapshot(
                canManageLaunchAgent = canManage,
                isEnabled = false,
                statusMessage = "Launch at login is disabled.",
                supportMessage = supportMessage(canManage, "Launch at login is disabled."),
            )
            return snapshot
        }

        val configuredExecutable = configuredExecutablePath(plist)
        snapshot = if (configuredExecutable == executablePath) {
            LaunchAtLoginSnapshot(
                canManageLaunchAgent = canManage,
                isEnabled = true,
                statusMessage = "Launch at login enabled for this app bundle.",
                supportMessage = supportMessage(canManage, "Launch at login enabled for this app bundle."),
            )
        } else {
            LaunchAtLoginSnapshot(
                canManageLaunchAgent = canManage,
                isEnabled = false,
                statusMessage = "A launch agent exists but points to a different app bundle. Re-enable the checkbox to update it.",
                supportMessage = supportMessage(canManage, "A launch agent exists but points to a different app bundle. Re-enable the checkbox to update it."),
            )
        }

        return snapshot
    }

    /**
     * Launch at login の有効・無効を切り替えます。
     *
     * @param enabled 有効化する場合は `true` です。
     * @return 更新後のスナップショットです。
     */
    fun setEnabled(enabled: Boolean): LaunchAtLoginSnapshot {
        if (!canManageLaunchAgent()) {
            return refresh()
        }

        val executablePath = runtimeInfo.executablePath
            ?: return fail("Launch at login requires the app to be running from a bundled .app.")

        return try {
            if (enabled) {
                // 有効化時は LaunchAgents ディレクトリを作成して plist を書き込みます。
                fileSystem.createDirectory(launchAgentDirectoryPath())
                fileSystem.writeText(launchAgentPath(), launchAgentPlist(executablePath))
                snapshot = LaunchAtLoginSnapshot(
                    canManageLaunchAgent = true,
                    isEnabled = true,
                    statusMessage = "Launch at login enabled. The new setting takes effect on the next login.",
                    supportMessage = "Launch at login enabled. The new setting takes effect on the next login.",
                )
            } else {
                // 無効化時は plist を削除して、次回起動時の読み込み対象から外します。
                fileSystem.removeFile(launchAgentPath())
                snapshot = LaunchAtLoginSnapshot(
                    canManageLaunchAgent = true,
                    isEnabled = false,
                    statusMessage = "Launch at login disabled.",
                    supportMessage = "Launch at login disabled.",
                )
            }

            snapshot
        } catch (error: Throwable) {
            fail(error.message ?: "Failed to update launch-at-login state.")
        }
    }

    /**
     * 失敗状態のスナップショットを作ります。
     *
     * @param message 表示する失敗メッセージです。
     * @return 失敗状態です。
     */
    private fun fail(message: String): LaunchAtLoginSnapshot {
        snapshot = LaunchAtLoginSnapshot(
            canManageLaunchAgent = canManageLaunchAgent(),
            isEnabled = false,
            statusMessage = message,
            supportMessage = supportMessage(canManageLaunchAgent(), message),
        )
        return snapshot
    }

    /**
     * LaunchAgent を管理できる環境かどうかを判定します。
     *
     * @return 管理可能なら `true` です。
     */
    private fun canManageLaunchAgent(): Boolean {
        val executablePath = runtimeInfo.executablePath
        val bundlePath = runtimeInfo.bundlePath
        return executablePath != null && bundlePath?.endsWith(".app") == true
    }

    /**
     * LaunchAgent ディレクトリのパスを返します。
     *
     * @return ディレクトリパスです。
     */
    private fun launchAgentDirectoryPath(): String {
        return "${fileSystem.homeDirectoryPath}/Library/LaunchAgents"
    }

    /**
     * LaunchAgent plist の保存先パスを返します。
     *
     * @return plist のパスです。
     */
    private fun launchAgentPath(): String {
        return "${launchAgentDirectoryPath()}/${Constants.launchAgentLabel}.plist"
    }

    /**
     * UI 用の補足メッセージを作ります。
     *
     * @param canManage 管理可能かどうかです。
     * @param statusMessage 現在の状態メッセージです。
     * @return 補足メッセージです。
     */
    private fun supportMessage(canManage: Boolean, statusMessage: String): String {
        return if (canManage) {
            statusMessage
        } else {
            unsupportedMessage
        }
    }

    /**
     * plist から設定済みの実行ファイルパスを抜き出します。
     *
     * @param plistText plist の内容です。
     * @return 解析結果。見つからない場合は `null` です。
     */
    private fun configuredExecutablePath(plistText: String): String? {
        val regex = Regex("<key>ProgramArguments</key>\\s*<array>\\s*<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(plistText) ?: return null
        return xmlUnescape(match.groupValues[1])
    }

    /**
     * LaunchAgent plist を生成します。
     *
     * @param executablePath 実行ファイルパスです。
     * @return 生成した plist 文字列です。
     */
    private fun launchAgentPlist(executablePath: String): String {
        val escapedExecutablePath = xmlEscape(executablePath)
        val escapedWorkingDirectory = xmlEscape(executablePath.substringBeforeLast('/', executablePath))

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>${Constants.launchAgentLabel}</string>
                <key>ProgramArguments</key>
                <array>
                    <string>$escapedExecutablePath</string>
                </array>
                <key>RunAtLoad</key>
                <true/>
                <key>KeepAlive</key>
                <false/>
                <key>ProcessType</key>
                <string>Interactive</string>
                <key>WorkingDirectory</key>
                <string>$escapedWorkingDirectory</string>
            </dict>
            </plist>
        """.trimIndent()
    }

    /**
     * XML 向けに文字列をエスケープします。
     *
     * @param value エスケープ対象です。
     * @return エスケープ済み文字列です。
     */
    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * XML 文字列を復号します。
     *
     * @param value 復号対象です。
     * @return 復号済み文字列です。
     */
    private fun xmlUnescape(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
    }

    private companion object {
        const val unsupportedMessage = "Launch at login is only available when running from a bundled .app. The standalone .kexe keeps the toggle disabled until the app bundle exists."
    }
}

/**
 * Foundation から実行環境情報を取得します。
 */
@OptIn(ExperimentalForeignApi::class)
internal class FoundationLaunchAtLoginRuntimeInfo : LaunchAtLoginRuntimeInfo {
    /**
     * バンドルパスを返します。
     *
     * @return バンドルパスです。
     */
    override val bundlePath: String?
        get() = NSBundle.mainBundle.bundleURL?.path

    /**
     * 実行ファイルのパスを返します。
     *
     * @return 実行ファイルパスです。
     */
    override val executablePath: String?
        get() = NSBundle.mainBundle.executableURL?.path
}

/**
 * Foundation のファイル API を使うファイルシステム実装です。
 */
@OptIn(ExperimentalForeignApi::class)
internal class FoundationLaunchAtLoginFileSystem : LaunchAtLoginFileSystem {
    private val fileManager = NSFileManager.defaultManager

    /**
     * ホームディレクトリのパスを返します。
     *
     * @return ホームディレクトリです。
     */
    override val homeDirectoryPath: String
        get() = NSHomeDirectory()

    /**
     * ディレクトリを作成します。
     *
     * @param path 作成先です。
     */
    override fun createDirectory(path: String) {
        fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    /**
     * テキストファイルを読み出します。
     *
     * @param path 読み出し対象です。
     * @return 読み出した文字列。失敗時は `null` です。
     */
    override fun readText(path: String): String? {
        val file = fopen(path, "r") ?: return null
        try {
            // ファイルサイズを先に求め、必要なバッファサイズを確保します。
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            if (size < 0L || size > Int.MAX_VALUE.toLong()) {
                return null
            }
            if (size == 0L) {
                return ""
            }

            rewind(file)
            val buffer = ByteArray(size.toInt())
            val readCount = buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), 1uL, size.toULong(), file)
            }
            return buffer.decodeToString(endIndex = readCount.toInt())
        } finally {
            fclose(file)
        }
    }

    /**
     * テキストファイルを書き込みます。
     *
     * @param path 書き込み先です。
     * @param text 書き込む文字列です。
     */
    override fun writeText(path: String, text: String) {
        val file = fopen(path, "w") ?: error("Failed to open launch agent plist for writing: $path")
        try {
            val bytes = text.encodeToByteArray()
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
            }
        } finally {
            fclose(file)
        }
    }

    /**
     * ファイルを削除します。
     *
     * @param path 削除対象です。
     */
    override fun removeFile(path: String) {
        if (fileManager.fileExistsAtPath(path)) {
            fileManager.removeItemAtPath(path, error = null)
        }
    }
}
