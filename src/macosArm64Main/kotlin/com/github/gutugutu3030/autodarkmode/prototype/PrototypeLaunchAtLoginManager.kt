package com.github.gutugutu3030.autodarkmode.prototype

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

internal data class PrototypeLaunchAtLoginSnapshot(
    val canManageLaunchAgent: Boolean,
    val isEnabled: Boolean,
    val statusMessage: String,
    val supportMessage: String,
)

internal interface PrototypeLaunchAtLoginRuntimeInfo {
    val bundlePath: String?
    val executablePath: String?
}

internal interface PrototypeLaunchAtLoginFileSystem {
    val homeDirectoryPath: String
    fun createDirectory(path: String)
    fun readText(path: String): String?
    fun writeText(path: String, text: String)
    fun removeFile(path: String)
}

internal class PrototypeLaunchAtLoginManager(
    private val runtimeInfo: PrototypeLaunchAtLoginRuntimeInfo = FoundationPrototypeLaunchAtLoginRuntimeInfo(),
    private val fileSystem: PrototypeLaunchAtLoginFileSystem = FoundationPrototypeLaunchAtLoginFileSystem(),
) {
    private object Constants {
        const val launchAgentLabel = "com.gutugutu3030.autoDarkMode.prototype"
    }

    private var snapshot = PrototypeLaunchAtLoginSnapshot(
        canManageLaunchAgent = false,
        isEnabled = false,
        statusMessage = "Launch at login is disabled.",
        supportMessage = unsupportedMessage,
    )

    fun snapshot(): PrototypeLaunchAtLoginSnapshot = snapshot

    fun refresh(): PrototypeLaunchAtLoginSnapshot {
        val executablePath = runtimeInfo.executablePath
        val canManage = canManageLaunchAgent()

        if (executablePath == null) {
            snapshot = PrototypeLaunchAtLoginSnapshot(
                canManageLaunchAgent = false,
                isEnabled = false,
                statusMessage = "Launch at login is disabled.",
                supportMessage = unsupportedMessage,
            )
            return snapshot
        }

        val plist = fileSystem.readText(launchAgentPath())
        if (plist == null) {
            snapshot = PrototypeLaunchAtLoginSnapshot(
                canManageLaunchAgent = canManage,
                isEnabled = false,
                statusMessage = if (canManage) "Launch at login is disabled." else "Launch at login is disabled.",
                supportMessage = supportMessage(canManage, "Launch at login is disabled."),
            )
            return snapshot
        }

        val configuredExecutable = configuredExecutablePath(plist)
        snapshot = if (configuredExecutable == executablePath) {
            PrototypeLaunchAtLoginSnapshot(
                canManageLaunchAgent = canManage,
                isEnabled = true,
                statusMessage = "Launch at login enabled for this app bundle.",
                supportMessage = supportMessage(canManage, "Launch at login enabled for this app bundle."),
            )
        } else {
            PrototypeLaunchAtLoginSnapshot(
                canManageLaunchAgent = canManage,
                isEnabled = false,
                statusMessage = "A launch agent exists but points to a different app bundle. Re-enable the checkbox to update it.",
                supportMessage = supportMessage(canManage, "A launch agent exists but points to a different app bundle. Re-enable the checkbox to update it."),
            )
        }

        return snapshot
    }

    fun setEnabled(enabled: Boolean): PrototypeLaunchAtLoginSnapshot {
        if (!canManageLaunchAgent()) {
            return refresh()
        }

        val executablePath = runtimeInfo.executablePath
            ?: return fail("Launch at login requires the app to be running from a bundled .app.")

        return try {
            if (enabled) {
                fileSystem.createDirectory(launchAgentDirectoryPath())
                fileSystem.writeText(launchAgentPath(), launchAgentPlist(executablePath))
                snapshot = PrototypeLaunchAtLoginSnapshot(
                    canManageLaunchAgent = true,
                    isEnabled = true,
                    statusMessage = "Launch at login enabled. The new setting takes effect on the next login.",
                    supportMessage = "Launch at login enabled. The new setting takes effect on the next login.",
                )
            } else {
                fileSystem.removeFile(launchAgentPath())
                snapshot = PrototypeLaunchAtLoginSnapshot(
                    canManageLaunchAgent = true,
                    isEnabled = false,
                    statusMessage = "Launch at login disabled.",
                    supportMessage = "Launch at login disabled.",
                )
            }

            refresh()
        } catch (error: Throwable) {
            fail(error.message ?: "Failed to update launch-at-login state.")
        }
    }

    private fun fail(message: String): PrototypeLaunchAtLoginSnapshot {
        snapshot = PrototypeLaunchAtLoginSnapshot(
            canManageLaunchAgent = canManageLaunchAgent(),
            isEnabled = false,
            statusMessage = message,
            supportMessage = supportMessage(canManageLaunchAgent(), message),
        )
        return snapshot
    }

    private fun canManageLaunchAgent(): Boolean {
        val executablePath = runtimeInfo.executablePath
        val bundlePath = runtimeInfo.bundlePath
        return executablePath != null && bundlePath?.endsWith(".app") == true
    }

    private fun launchAgentDirectoryPath(): String {
        return "${fileSystem.homeDirectoryPath}/Library/LaunchAgents"
    }

    private fun launchAgentPath(): String {
        return "${launchAgentDirectoryPath()}/${Constants.launchAgentLabel}.plist"
    }

    private fun supportMessage(canManage: Boolean, statusMessage: String): String {
        return if (canManage) {
            statusMessage
        } else {
            unsupportedMessage
        }
    }

    private fun configuredExecutablePath(plistText: String): String? {
        val regex = Regex("<key>ProgramArguments</key>\\s*<array>\\s*<string>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(plistText) ?: return null
        return xmlUnescape(match.groupValues[1])
    }

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
                    <string>${escapedExecutablePath}</string>
                </array>
                <key>RunAtLoad</key>
                <true/>
                <key>KeepAlive</key>
                <false/>
                <key>ProcessType</key>
                <string>Interactive</string>
                <key>WorkingDirectory</key>
                <string>${escapedWorkingDirectory}</string>
            </dict>
            </plist>
        """.trimIndent()
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun xmlUnescape(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
    }

    private companion object {
        const val unsupportedMessage = "Launch at login is only available when running from a bundled .app. The prototype .kexe keeps the toggle disabled until the app-bundle shell exists."
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class FoundationPrototypeLaunchAtLoginRuntimeInfo : PrototypeLaunchAtLoginRuntimeInfo {
    override val bundlePath: String?
        get() = NSBundle.mainBundle.bundleURL?.path

    override val executablePath: String?
        get() = NSBundle.mainBundle.executableURL?.path
}

@OptIn(ExperimentalForeignApi::class)
internal class FoundationPrototypeLaunchAtLoginFileSystem : PrototypeLaunchAtLoginFileSystem {
    private val fileManager = NSFileManager.defaultManager

    override val homeDirectoryPath: String
        get() = NSHomeDirectory()

    override fun createDirectory(path: String) {
        fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override fun readText(path: String): String? {
        val file = fopen(path, "r") ?: return null
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            if (size <= 0L) {
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

    override fun removeFile(path: String) {
        if (fileManager.fileExistsAtPath(path)) {
            fileManager.removeItemAtPath(path, error = null)
        }
    }
}