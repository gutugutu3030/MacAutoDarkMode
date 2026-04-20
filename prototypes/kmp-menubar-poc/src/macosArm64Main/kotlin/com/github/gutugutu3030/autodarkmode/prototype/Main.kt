@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.github.gutugutu3030.autodarkmode.prototype

import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSStatusBar
import platform.AppKit.NSVariableStatusItemLength
import platform.Foundation.NSSelectorFromString
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSImage
import platform.AppKit.NSStatusItem
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSTimer
import platform.Foundation.NSUserDefaultsDidChangeNotification
import platform.darwin.NSObject
import platform.posix.getenv
import kotlinx.cinterop.toKString

private lateinit var coordinator: PrototypeStatusBarCoordinator

fun main() {
    val application = NSApplication.sharedApplication()
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory)

    coordinator = PrototypeStatusBarCoordinator(application)
    coordinator.start()

    application.run()
}

enum class PrototypeMode(val displayName: String) {
    Off("Off"),
    Auto("Auto"),
    Manual("Manual"),
}

private enum class PrototypeAppearance(val displayName: String) {
    Light("Light"),
    Dark("Dark"),
}

private data class PrototypeStatusState(
    var lux: Double = 240.0,
    var source: String = "iohid-bezelservices",
    var mode: PrototypeMode = PrototypeMode.Auto,
    var appearance: PrototypeAppearance? = PrototypeAppearance.Light,
    var sensorAvailable: Boolean = true,
    var darkThresholdLux: Double = 180.0,
    var lightThresholdLux: Double = 420.0,
    var message: String = "Kotlin-side menu coordinator is active.",
    var tickCount: Int = 0,
)

private data class PrototypeAggregationStats(
    var brightnessEventCount: Int = 0,
    var engineEventCount: Int = 0,
    var settingsEventCount: Int = 0,
    var presentationFlushCount: Int = 0,
    var coalescedMutationCount: Int = 0,
    var maxMutationsPerFlush: Int = 0,
    var pendingMutationsSinceLastFlush: Int = 0,
    var lastFlushSummary: String = "No flush yet.",
)

private enum class PrototypeBrightnessDirection {
    Up,
    Down,
}

private enum class PrototypeBrightnessPhase {
    Down,
    Up,
}

private data class PrototypeBrightnessEvent(
    val direction: PrototypeBrightnessDirection,
    val phase: PrototypeBrightnessPhase,
    val brightnessAfterSampling: Double,
)

private data class PrototypeEngineEvent(
    val sensorAvailable: Boolean,
    val lux: Double,
    val appearance: PrototypeAppearance?,
    val description: String,
)

private class PrototypeStatusBarCoordinator(
    private val application: NSApplication,
) : NSObject() {
    private val statusItem: NSStatusItem = NSStatusBar.systemStatusBar.statusItemWithLength(NSVariableStatusItemLength)
    private val menu = NSMenu()

    private val luxItem = NSMenuItem()
    private val sourceItem = NSMenuItem()
    private val appearanceItem = NSMenuItem()
    private val modeOffItem = NSMenuItem()
    private val modeAutoItem = NSMenuItem()
    private val modeManualItem = NSMenuItem()
    private val thresholdItem = NSMenuItem()
    private val eventStatsItem = NSMenuItem()
    private val flushStatsItem = NSMenuItem()
    private val messageItem = NSMenuItem()

    private val state = PrototypeStatusState()
    private val stats = PrototypeAggregationStats()
    private val ambientLightReader = NativeAmbientLightReader()
    private val persistedSettings = PrototypePersistedSettings()

    private var brightnessEventTimer: NSTimer? = null
    private var engineEventTimer: NSTimer? = null
    private var pendingPresentationTimer: NSTimer? = null
    private var persistedSettingsProbeTimer: NSTimer? = null
    private var updateScheduled = false
    private var simulatedManualBrightness = 0.72

    fun start() {
        configureMenu()
        observePersistedSettings()
        applyPersistedSettings(trigger = "startup", countAsEvent = false)
        state.sensorAvailable = ambientLightReader.isSensorAvailable()
        println("[kmp-menubar-poc] NativeAmbientLightReader startup availability: ${state.sensorAvailable}")
        updatePresentation()
        brightnessEventTimer = NSTimer.scheduledTimerWithTimeInterval(
            0.55,
            target = this,
            selector = NSSelectorFromString("emitBrightnessEvent:"),
            userInfo = null,
            repeats = true,
        )
        engineEventTimer = NSTimer.scheduledTimerWithTimeInterval(
            1.35,
            target = this,
            selector = NSSelectorFromString("emitEngineEvent:"),
            userInfo = null,
            repeats = true,
        )
        schedulePersistedSettingsProbeIfNeeded()
    }

    private fun configureMenu() {
        luxItem.enabled = false
        sourceItem.enabled = false
        appearanceItem.enabled = false
        thresholdItem.enabled = false
        eventStatsItem.enabled = false
        flushStatsItem.enabled = false
        messageItem.enabled = false

        modeOffItem.title = "Mode: Off"
        modeOffItem.target = this
        modeOffItem.setAction(NSSelectorFromString("selectModeOff"))

        modeAutoItem.title = "Mode: Auto"
        modeAutoItem.target = this
        modeAutoItem.setAction(NSSelectorFromString("selectModeAuto"))

        modeManualItem.title = "Mode: Manual"
        modeManualItem.target = this
        modeManualItem.setAction(NSSelectorFromString("selectModeManual"))

        val dimRoomPresetItem = NSMenuItem(title = PrototypeThresholdPreset.DimRoom.menuTitle, action = NSSelectorFromString("persistDimRoomThresholds"), keyEquivalent = "")
        dimRoomPresetItem.target = this

        val brightRoomPresetItem = NSMenuItem(title = PrototypeThresholdPreset.BrightRoom.menuTitle, action = NSSelectorFromString("persistBrightRoomThresholds"), keyEquivalent = "")
        brightRoomPresetItem.target = this

        val sampleItem = NSMenuItem(title = "Sample Now", action = NSSelectorFromString("sampleNow"), keyEquivalent = "r")
        sampleItem.target = this

        val lightItem = NSMenuItem(title = "Switch Light", action = NSSelectorFromString("switchLight"), keyEquivalent = "l")
        lightItem.target = this

        val darkItem = NSMenuItem(title = "Switch Dark", action = NSSelectorFromString("switchDark"), keyEquivalent = "d")
        darkItem.target = this

        val quitItem = NSMenuItem(title = "Quit", action = NSSelectorFromString("quit"), keyEquivalent = "q")
        quitItem.target = this

        menu.addItem(luxItem)
        menu.addItem(sourceItem)
        menu.addItem(appearanceItem)
        menu.addItem(NSMenuItem.separatorItem())
        menu.addItem(modeOffItem)
        menu.addItem(modeAutoItem)
        menu.addItem(modeManualItem)
        menu.addItem(thresholdItem)
        menu.addItem(dimRoomPresetItem)
        menu.addItem(brightRoomPresetItem)
        menu.addItem(eventStatsItem)
        menu.addItem(flushStatsItem)
        menu.addItem(sampleItem)
        menu.addItem(lightItem)
        menu.addItem(darkItem)
        menu.addItem(NSMenuItem.separatorItem())
        menu.addItem(messageItem)
        menu.addItem(NSMenuItem.separatorItem())
        menu.addItem(quitItem)

        statusItem.menu = menu
    }

    private fun scheduleUpdatePresentation() {
        if (updateScheduled) {
            return
        }

        updateScheduled = true
        pendingPresentationTimer?.invalidate()
        pendingPresentationTimer = NSTimer.scheduledTimerWithTimeInterval(
            0.0,
            target = this,
            selector = NSSelectorFromString("flushScheduledPresentationUpdate"),
            userInfo = null,
            repeats = false,
        )
    }

    @ObjCAction
    fun flushScheduledPresentationUpdate() {
        val pendingMutationCount = stats.pendingMutationsSinceLastFlush
        stats.presentationFlushCount += 1
        stats.coalescedMutationCount += pendingMutationCount
        if (pendingMutationCount > stats.maxMutationsPerFlush) {
            stats.maxMutationsPerFlush = pendingMutationCount
        }
        stats.lastFlushSummary = "Flush ${stats.presentationFlushCount}: ${pendingMutationCount} mutation(s)"
        println(
            "[kmp-menubar-poc] ${stats.lastFlushSummary}; brightness=${stats.brightnessEventCount}, " +
                "engine=${stats.engineEventCount}, settings=${stats.settingsEventCount}, mode=${state.mode.displayName}"
        )
        stats.pendingMutationsSinceLastFlush = 0
        pendingPresentationTimer = null
        updateScheduled = false
        updatePresentation()
    }

    private fun updatePresentation() {
        luxItem.title = "Ambient light: ${formatLux(state.lux)}"
        sourceItem.title = "Sensor path: ${state.source}"
        appearanceItem.title = "Appearance: ${state.appearance?.displayName ?: "Unknown"}"

        modeOffItem.state = if (state.mode == PrototypeMode.Off) NSControlStateValueOn else NSControlStateValueOff
        modeAutoItem.state = if (state.mode == PrototypeMode.Auto) NSControlStateValueOn else NSControlStateValueOff
        modeManualItem.state = if (state.mode == PrototypeMode.Manual) NSControlStateValueOn else NSControlStateValueOff

        thresholdItem.hidden = state.mode != PrototypeMode.Auto
        thresholdItem.title = "Dark <= ${formatLux(state.darkThresholdLux)} / Light >= ${formatLux(state.lightThresholdLux)}"
        eventStatsItem.title = "Events: brightness ${stats.brightnessEventCount} / engine ${stats.engineEventCount} / settings ${stats.settingsEventCount}"
        flushStatsItem.title = "Flushes: ${stats.presentationFlushCount}, max batch ${stats.maxMutationsPerFlush}"
        messageItem.title = state.message

        statusItem.button?.image = NSImage.imageWithSystemSymbolName(symbolName(), accessibilityDescription = "kmp-menubar-poc")
        statusItem.button?.toolTip = "kmp-menubar-poc (${state.mode.displayName})"
    }

    private fun observePersistedSettings() {
        NSNotificationCenter.defaultCenter.addObserver(
            this,
            selector = NSSelectorFromString("persistedSettingsDidChange:"),
            name = NSUserDefaultsDidChangeNotification,
            `object` = null,
        )
    }

    private fun schedulePersistedSettingsProbeIfNeeded() {
        if (getenv("KMP_MENUBAR_POC_VALIDATE_DEFAULTS")?.toKString() != "1") {
            return
        }

        persistedSettingsProbeTimer = NSTimer.scheduledTimerWithTimeInterval(
            0.8,
            target = this,
            selector = NSSelectorFromString("runPersistedSettingsValidationProbe"),
            userInfo = null,
            repeats = false,
        )
    }

    private fun applyPersistedSettings(trigger: String, countAsEvent: Boolean = true) {
        val snapshot = persistedSettings.currentSnapshot()
        val changedFields = mutableListOf<String>()

        if (state.mode != snapshot.mode) {
            state.mode = snapshot.mode
            changedFields += "mode=${snapshot.mode.displayName}"
        }
        if (state.darkThresholdLux != snapshot.darkThresholdLux) {
            state.darkThresholdLux = snapshot.darkThresholdLux
            changedFields += "dark=${formatLux(snapshot.darkThresholdLux)}"
        }
        if (state.lightThresholdLux != snapshot.lightThresholdLux) {
            state.lightThresholdLux = snapshot.lightThresholdLux
            changedFields += "light=${formatLux(snapshot.lightThresholdLux)}"
        }

        if (countAsEvent) {
            stats.settingsEventCount += 1
        }

        val detail = if (changedFields.isEmpty()) {
            "no effective diff"
        } else {
            changedFields.joinToString(", ")
        }
        state.message = "NSUserDefaultsDidChangeNotification: ${detail}."
        println("[kmp-menubar-poc] Persisted settings applied from ${trigger}: ${detail}.")
        recordAndScheduleUpdate()
    }

    private fun recordMutation() {
        stats.pendingMutationsSinceLastFlush += 1
    }

    private fun recordAndScheduleUpdate() {
        recordMutation()
        scheduleUpdatePresentation()
    }

    private fun symbolName(): String = when {
        state.mode == PrototypeMode.Off -> "lightspectrum.horizontal"
        state.mode == PrototypeMode.Auto && !state.sensorAvailable -> "exclamationmark.triangle"
        state.appearance == PrototypeAppearance.Dark -> "moon.fill"
        state.appearance == PrototypeAppearance.Light -> "sun.max.fill"
        else -> "lightspectrum.horizontal"
    }

    @ObjCAction
    fun emitBrightnessEvent(sender: NSTimer) {
        sender
        state.tickCount += 1

        if (state.mode == PrototypeMode.Off) {
            state.message = "Brightness event ignored while mode is Off."
            recordAndScheduleUpdate()
            return
        }

        val brightnessEvent = nextBrightnessEvent()
        handleBrightnessEvent(brightnessEvent)
    }

    @ObjCAction
    fun emitEngineEvent(sender: NSTimer) {
        sender

        if (state.mode == PrototypeMode.Off) {
            state.message = "Engine event ignored while mode is Off."
            recordAndScheduleUpdate()
            return
        }

        val reading = ambientLightReader.currentReading()
        handleEngineReading(reading)

        if (state.tickCount % 4 == 0) {
            val burstBrightnessEvent = PrototypeBrightnessEvent(
                direction = PrototypeBrightnessDirection.Up,
                phase = PrototypeBrightnessPhase.Down,
                brightnessAfterSampling = 1.0,
            )
            handleBrightnessEvent(burstBrightnessEvent)
            state.message = "Engine and brightness events were coalesced into the same flush."
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun persistedSettingsDidChange(notification: NSNotification) {
        notification
        applyPersistedSettings(trigger = "NSUserDefaultsDidChangeNotification")
    }

    @ObjCAction
    fun runPersistedSettingsValidationProbe() {
        persistedSettingsProbeTimer = null
        println("[kmp-menubar-poc] Running persisted settings validation probe.")
        persistedSettings.persistThresholdPreset(PrototypeThresholdPreset.BrightRoom)
    }

    private fun nextBrightnessEvent(): PrototypeBrightnessEvent {
        val direction = if (state.tickCount % 3 == 0) {
            PrototypeBrightnessDirection.Down
        } else {
            PrototypeBrightnessDirection.Up
        }
        val phase = if (state.tickCount % 2 == 0) {
            PrototypeBrightnessPhase.Down
        } else {
            PrototypeBrightnessPhase.Up
        }

        simulatedManualBrightness = when {
            direction == PrototypeBrightnessDirection.Up && phase == PrototypeBrightnessPhase.Down -> minOf(1.0, simulatedManualBrightness + 0.12)
            direction == PrototypeBrightnessDirection.Down && phase == PrototypeBrightnessPhase.Down -> maxOf(0.22, simulatedManualBrightness - 0.18)
            else -> simulatedManualBrightness
        }

        return PrototypeBrightnessEvent(
            direction = direction,
            phase = phase,
            brightnessAfterSampling = simulatedManualBrightness,
        )
    }

    private fun handleBrightnessEvent(event: PrototypeBrightnessEvent) {
        stats.brightnessEventCount += 1

        if (state.mode != PrototypeMode.Manual) {
            state.message = "BrightnessKeyMonitor event arrived, but mode is ${state.mode.displayName}."
            recordAndScheduleUpdate()
            return
        }

        state.appearance = if (event.brightnessAfterSampling >= 0.99) {
            PrototypeAppearance.Light
        } else {
            PrototypeAppearance.Dark
        }
        state.message = "BrightnessKeyMonitor: ${event.direction.name.lowercase()} ${event.phase.name.lowercase()} at ${(event.brightnessAfterSampling * 100).toInt()}%."
        recordAndScheduleUpdate()
    }

    private fun handleEngineReading(reading: NativeAmbientLightReading?) {
        stats.engineEventCount += 1

        if (state.mode != PrototypeMode.Auto) {
            state.message = "AutoSwitchEngine event arrived, but mode is ${state.mode.displayName}."
            recordAndScheduleUpdate()
            return
        }

        if (reading == null) {
            state.sensorAvailable = ambientLightReader.isSensorAvailable()
            state.source = NativeAmbientLightSource.Unavailable.displayName
            state.message = if (state.sensorAvailable) {
                "NativeAmbientLightReader: sample failed."
            } else {
                "NativeAmbientLightReader: sensor unavailable on this Mac."
            }
            recordAndScheduleUpdate()
            return
        }

        state.sensorAvailable = true
        state.lux = reading.lux
        state.source = reading.source.displayName
        state.appearance = when {
            reading.lux <= state.darkThresholdLux -> PrototypeAppearance.Dark
            reading.lux >= state.lightThresholdLux -> PrototypeAppearance.Light
            else -> state.appearance
        }
        state.message = "NativeAmbientLightReader: ${reading.source.displayName} ${formatLux(reading.lux)}."
        recordAndScheduleUpdate()
    }

    @ObjCAction
    fun selectModeOff() {
        persistedSettings.persistMode(PrototypeMode.Off)
    }

    @ObjCAction
    fun selectModeAuto() {
        persistedSettings.persistMode(PrototypeMode.Auto)
    }

    @ObjCAction
    fun selectModeManual() {
        persistedSettings.persistMode(PrototypeMode.Manual)
    }

    @ObjCAction
    fun persistDimRoomThresholds() {
        persistedSettings.persistThresholdPreset(PrototypeThresholdPreset.DimRoom)
    }

    @ObjCAction
    fun persistBrightRoomThresholds() {
        persistedSettings.persistThresholdPreset(PrototypeThresholdPreset.BrightRoom)
    }

    @ObjCAction
    fun sampleNow() {
        state.lux += 55.0
        state.message = "Manual sample triggered on tick ${state.tickCount}."
        recordAndScheduleUpdate()
    }

    @ObjCAction
    fun switchLight() {
        state.appearance = PrototypeAppearance.Light
        state.message = "Forced Light from menu action."
        recordAndScheduleUpdate()
    }

    @ObjCAction
    fun switchDark() {
        state.appearance = PrototypeAppearance.Dark
        state.message = "Forced Dark from menu action."
        recordAndScheduleUpdate()
    }

    @ObjCAction
    fun quit() {
        pendingPresentationTimer?.invalidate()
        brightnessEventTimer?.invalidate()
        engineEventTimer?.invalidate()
        persistedSettingsProbeTimer?.invalidate()
        NSNotificationCenter.defaultCenter.removeObserver(this)
        ambientLightReader.close()
        application.terminate(null)
    }
}

private fun formatLux(value: Double): String {
    return if (value >= 1000.0) {
        "${((value / 100.0).toInt() / 10.0)} klx"
    } else {
        "${value.toInt()} lx"
    }
}