package com.github.gutugutu3030.autodarkmode.prototype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Launch at login の plist 管理を検証します。
 */
class PrototypeLaunchAtLoginManagerTest {
    /**
     * App Bundle 外では管理不可になることを確認します。
     */
    @Test
    fun disabledWhenNotRunningFromAppBundle() {
        val manager = PrototypeLaunchAtLoginManager(
            runtimeInfo = FakeRuntimeInfo(
                bundlePath = "/tmp/autoDarkMode.kexe",
                executablePath = "/tmp/autoDarkMode.kexe",
            ),
            fileSystem = FakeLaunchAtLoginFileSystem(),
        )

        val snapshot = manager.refresh()
        assertFalse(snapshot.canManageLaunchAgent)
        assertFalse(snapshot.isEnabled)
    }

    /**
     * 有効化時に LaunchAgent plist が書き込まれることを確認します。
     */
    @Test
    fun enablingWritesLaunchAgentForCurrentExecutable() {
        val fileSystem = FakeLaunchAtLoginFileSystem()
        val manager = PrototypeLaunchAtLoginManager(
            runtimeInfo = FakeRuntimeInfo(
                bundlePath = "/Applications/autoDarkMode Prototype.app",
                executablePath = "/Applications/autoDarkMode Prototype.app/Contents/MacOS/autoDarkMode",
            ),
            fileSystem = fileSystem,
        )

        val snapshot = manager.setEnabled(true)
        assertTrue(snapshot.canManageLaunchAgent)
        assertTrue(snapshot.isEnabled)
        val plist = fileSystem.files[fileSystem.expectedLaunchAgentPath()] ?: error("missing plist")
        assertTrue(plist.contains("/Applications/autoDarkMode Prototype.app/Contents/MacOS/autoDarkMode"))
    }

    /**
     * 別バンドルを指す plist を検出できることを確認します。
     */
    @Test
    fun refreshDetectsDifferentBundleTarget() {
        val fileSystem = FakeLaunchAtLoginFileSystem()
        fileSystem.files[fileSystem.expectedLaunchAgentPath()] = """
            <plist version="1.0"><dict><key>ProgramArguments</key><array><string>/Applications/Other.app/Contents/MacOS/Other</string></array></dict></plist>
        """.trimIndent()
        val manager = PrototypeLaunchAtLoginManager(
            runtimeInfo = FakeRuntimeInfo(
                bundlePath = "/Applications/autoDarkMode Prototype.app",
                executablePath = "/Applications/autoDarkMode Prototype.app/Contents/MacOS/autoDarkMode",
            ),
            fileSystem = fileSystem,
        )

        val snapshot = manager.refresh()
        assertTrue(snapshot.canManageLaunchAgent)
        assertFalse(snapshot.isEnabled)
        assertEquals(
            "A launch agent exists but points to a different app bundle. Re-enable the checkbox to update it.",
            snapshot.statusMessage,
        )
    }

    /**
     * 無効化時に LaunchAgent が削除されることを確認します。
     */
    @Test
    fun disablingRemovesLaunchAgent() {
        val fileSystem = FakeLaunchAtLoginFileSystem()
        fileSystem.files[fileSystem.expectedLaunchAgentPath()] = "existing"
        val manager = PrototypeLaunchAtLoginManager(
            runtimeInfo = FakeRuntimeInfo(
                bundlePath = "/Applications/autoDarkMode Prototype.app",
                executablePath = "/Applications/autoDarkMode Prototype.app/Contents/MacOS/autoDarkMode",
            ),
            fileSystem = fileSystem,
        )

        val snapshot = manager.setEnabled(false)
        assertFalse(snapshot.isEnabled)
        assertFalse(fileSystem.files.containsKey(fileSystem.expectedLaunchAgentPath()))
    }
}

/**
 * テスト用の実行環境情報です。
 *
 * @property bundlePath バンドルパスです。
 * @property executablePath 実行ファイルパスです。
 */
private data class FakeRuntimeInfo(
    override val bundlePath: String?,
    override val executablePath: String?,
) : PrototypeLaunchAtLoginRuntimeInfo

/**
 * テスト用のメモリ内ファイルシステムです。
 */
private class FakeLaunchAtLoginFileSystem : PrototypeLaunchAtLoginFileSystem {
    override val homeDirectoryPath: String = "/Users/tester"
    val files = mutableMapOf<String, String>()
    val createdDirectories = mutableListOf<String>()

    /**
     * ディレクトリ作成を記録します。
     *
     * @param path 作成先です。
     */
    override fun createDirectory(path: String) {
        createdDirectories += path
    }

    /**
     * 保存済みテキストを読み出します。
     *
     * @param path 読み出し対象です。
     * @return 保存済み文字列です。
     */
    override fun readText(path: String): String? = files[path]

    /**
     * テキストを保存します。
     *
     * @param path 保存先です。
     * @param text 保存する文字列です。
     */
    override fun writeText(path: String, text: String) {
        files[path] = text
    }

    /**
     * 保存済みファイルを削除します。
     *
     * @param path 削除対象です。
     */
    override fun removeFile(path: String) {
        files.remove(path)
    }

    /**
     * 期待される LaunchAgent の保存先を返します。
     *
     * @return plist のパスです。
     */
    fun expectedLaunchAgentPath(): String {
        return "$homeDirectoryPath/Library/LaunchAgents/com.gutugutu3030.autoDarkMode.prototype.plist"
    }
}