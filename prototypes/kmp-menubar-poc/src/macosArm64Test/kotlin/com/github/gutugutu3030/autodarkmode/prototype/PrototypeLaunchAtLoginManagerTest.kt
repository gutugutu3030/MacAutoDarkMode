package com.github.gutugutu3030.autodarkmode.prototype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrototypeLaunchAtLoginManagerTest {
    @Test
    fun disabledWhenNotRunningFromAppBundle() {
        val manager = PrototypeLaunchAtLoginManager(
            runtimeInfo = FakeRuntimeInfo(
                bundlePath = "/tmp/kmp-menubar-poc.kexe",
                executablePath = "/tmp/kmp-menubar-poc.kexe",
            ),
            fileSystem = FakeLaunchAtLoginFileSystem(),
        )

        val snapshot = manager.refresh()
        assertFalse(snapshot.canManageLaunchAgent)
        assertFalse(snapshot.isEnabled)
    }

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

private data class FakeRuntimeInfo(
    override val bundlePath: String?,
    override val executablePath: String?,
) : PrototypeLaunchAtLoginRuntimeInfo

private class FakeLaunchAtLoginFileSystem : PrototypeLaunchAtLoginFileSystem {
    override val homeDirectoryPath: String = "/Users/tester"
    val files = mutableMapOf<String, String>()
    val createdDirectories = mutableListOf<String>()

    override fun createDirectory(path: String) {
        createdDirectories += path
    }

    override fun readText(path: String): String? = files[path]

    override fun writeText(path: String, text: String) {
        files[path] = text
    }

    override fun removeFile(path: String) {
        files.remove(path)
    }

    fun expectedLaunchAgentPath(): String {
        return "$homeDirectoryPath/Library/LaunchAgents/com.gutugutu3030.autoDarkMode.prototype.plist"
    }
}