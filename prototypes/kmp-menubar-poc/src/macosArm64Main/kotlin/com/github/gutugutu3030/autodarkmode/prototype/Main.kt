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
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
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
    private val manualKeysItem = NSMenuItem()
    private val settingsItem = NSMenuItem()
    private val eventStatsItem = NSMenuItem()
    private val flushStatsItem = NSMenuItem()
    private val messageItem = NSMenuItem()

    private val ambientLightReader = NativeAmbientLightReader()
    private val persistedSettings = PrototypePersistedSettings()
    private val stateStore = PrototypeStateStore(persistedSettings)
    private val launchAtLoginManager = PrototypeLaunchAtLoginManager()

    private var brightnessEventTimer: NSTimer? = null
    private var engineEventTimer: NSTimer? = null
    private var pendingPresentationTimer: NSTimer? = null
    private var persistedSettingsProbeTimer: NSTimer? = null
    private var manualBrightnessHoldTimer: NSTimer? = null
    private var updateScheduled = false
    private var settingsWindowController: PrototypeSettingsWindowController? = null

    fun start() {
        configureMenu()
        observePersistedSettings()
        val sensorAvailable = ambientLightReader.isSensorAvailable()
        stateStore.bootstrap(sensorAvailable = sensorAvailable)
        configureManualBrightnessMonitoring()
        println("[kmp-menubar-poc] NativeAmbientLightReader startup availability: ${stateStore.snapshot().status.sensorAvailable}")
        updatePresentation()
        brightnessEventTimer = scheduleTimerInCommonModes(
            0.55,
            repeats = true,
            selectorName = "emitBrightnessEvent:",
        )
        engineEventTimer = scheduleTimerInCommonModes(
            1.35,
            repeats = true,
            selectorName = "emitEngineEvent:",
        )
        schedulePersistedSettingsProbeIfNeeded()
    }

    private fun configureMenu() {
        luxItem.enabled = false
        sourceItem.enabled = false
        appearanceItem.enabled = false
        thresholdItem.enabled = false
        manualKeysItem.enabled = false
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

        settingsItem.title = "Open Settings"
        settingsItem.target = this
        settingsItem.setAction(NSSelectorFromString("openSettings"))

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
        menu.addItem(manualKeysItem)
        menu.addItem(dimRoomPresetItem)
        menu.addItem(brightRoomPresetItem)
        menu.addItem(eventStatsItem)
        menu.addItem(flushStatsItem)
        menu.addItem(sampleItem)
        menu.addItem(lightItem)
        menu.addItem(darkItem)
        menu.addItem(NSMenuItem.separatorItem())
        menu.addItem(settingsItem)
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
        pendingPresentationTimer = scheduleTimerInCommonModes(
            0.0,
            repeats = false,
            selectorName = "flushScheduledPresentationUpdate",
        )
    }

    @ObjCAction
    fun flushScheduledPresentationUpdate() {
        val snapshot = stateStore.recordFlush()
        println(
            "[kmp-menubar-poc] ${snapshot.stats.lastFlushSummary}; brightness=${snapshot.stats.brightnessEventCount}, " +
                "engine=${snapshot.stats.engineEventCount}, settings=${snapshot.stats.settingsEventCount}, mode=${snapshot.status.mode.displayName}"
        )
        pendingPresentationTimer = null
        updateScheduled = false
        syncManualBrightnessHoldTimer(snapshot)
        updatePresentation(snapshot)
        syncSettingsWindow(snapshot)
    }

    private fun updatePresentation(snapshot: PrototypeCoordinatorSnapshot = stateStore.snapshot()) {
        val state = snapshot.status
        val stats = snapshot.stats

        luxItem.title = "Ambient light: ${formatLux(state.lux)}"
        sourceItem.title = "Sensor path: ${state.source}"
        appearanceItem.title = "Appearance: ${state.appearance?.displayName ?: "Unknown"}"

        modeOffItem.state = if (state.mode == PrototypeMode.Off) NSControlStateValueOn else NSControlStateValueOff
        modeAutoItem.state = if (state.mode == PrototypeMode.Auto) NSControlStateValueOn else NSControlStateValueOff
        modeManualItem.state = if (state.mode == PrototypeMode.Manual) NSControlStateValueOn else NSControlStateValueOff

        thresholdItem.hidden = state.mode != PrototypeMode.Auto
        thresholdItem.title = "Dark <= ${formatLux(state.darkThresholdLux)} / Light >= ${formatLux(state.lightThresholdLux)} / ${state.requiredConsecutiveSamples} samples / ${state.cooldownSeconds.toInt()}s"
        manualKeysItem.hidden = state.mode != PrototypeMode.Manual
        manualKeysItem.title = when {
            state.manualBrightnessPermissionRequired -> "Brightness keys: Accessibility permission required"
            state.manualBrightnessHoldArmed -> "Brightness keys: Hold armed (${PrototypeStateStore.manualLightLongPressSeconds}s)"
            state.manualBrightnessRequiresReleaseAfterMax -> "Brightness keys: Release required before next hold"
            state.manualBrightnessKeyMonitoringEnabled -> "Brightness keys: Active"
            else -> "Brightness keys: Follows display brightness only"
        }
        eventStatsItem.title = "Events: brightness ${stats.brightnessEventCount} / engine ${stats.engineEventCount} / settings ${stats.settingsEventCount}"
        flushStatsItem.title = "Flushes: ${stats.presentationFlushCount}, max batch ${stats.maxMutationsPerFlush}"
        messageItem.title = state.lastError ?: state.message

        statusItem.button?.image = NSImage.imageWithSystemSymbolName(stateStore.symbolName(), accessibilityDescription = "kmp-menubar-poc")
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

        persistedSettingsProbeTimer = scheduleTimerInCommonModes(
            0.8,
            repeats = false,
            selectorName = "runPersistedSettingsValidationProbe",
        )
    }

    private fun configureManualBrightnessMonitoring() {
        if (getenv("KMP_MENUBAR_POC_ACCESSIBILITY_DENIED")?.toKString() == "1") {
            if (stateStore.reportManualBrightnessKeyMonitoringPermissionRequired()) {
                recordAndScheduleUpdate()
            }
            return
        }

        if (stateStore.setManualBrightnessKeyMonitoringEnabled(enabled = true)) {
            recordAndScheduleUpdate()
        }
    }

    private fun syncManualBrightnessHoldTimer(snapshot: PrototypeCoordinatorSnapshot = stateStore.snapshot()) {
        if (!snapshot.status.manualBrightnessHoldArmed) {
            manualBrightnessHoldTimer?.invalidate()
            manualBrightnessHoldTimer = null
            return
        }

        if (manualBrightnessHoldTimer != null) {
            return
        }

        manualBrightnessHoldTimer = scheduleTimerInCommonModes(
            PrototypeStateStore.manualLightLongPressSeconds,
            repeats = false,
            selectorName = "completeManualBrightnessHold",
        )
    }

    private fun scheduleTimerInCommonModes(
        interval: Double,
        repeats: Boolean,
        selectorName: String,
    ): NSTimer {
        val timer = NSTimer.timerWithTimeInterval(
            interval,
            target = this,
            selector = NSSelectorFromString(selectorName),
            userInfo = null,
            repeats = repeats,
        )
        NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
        return timer
    }

    private fun recordAndScheduleUpdate() {
        scheduleUpdatePresentation()
        syncSettingsWindow()
    }

    private fun syncSettingsWindow(snapshot: PrototypeCoordinatorSnapshot = stateStore.snapshot()) {
        settingsWindowController?.render(snapshot, launchAtLoginManager.refresh())
    }

    private fun settingsWindowController(): PrototypeSettingsWindowController {
        val existingController = settingsWindowController
        if (existingController != null) {
            return existingController
        }

        val createdController = PrototypeSettingsWindowController(
            stateStore = stateStore,
            launchAtLoginManager = launchAtLoginManager,
            onMutation = { recordAndScheduleUpdate() },
        )
        settingsWindowController = createdController
        createdController.render(stateStore.snapshot(), launchAtLoginManager.refresh())
        return createdController
    }

    @ObjCAction
    fun emitBrightnessEvent(sender: NSTimer) {
        sender
        if (stateStore.onBrightnessTimerTick()) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun emitEngineEvent(sender: NSTimer) {
        sender
        val reading = ambientLightReader.currentReading()
        val sensorAvailable = if (reading == null) ambientLightReader.isSensorAvailable() else true
        if (stateStore.onEngineTimerTick(reading = reading, sensorAvailable = sensorAvailable)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun persistedSettingsDidChange(notification: NSNotification) {
        notification
        if (stateStore.reloadPersistedSettings(trigger = "NSUserDefaultsDidChangeNotification")) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun runPersistedSettingsValidationProbe() {
        persistedSettingsProbeTimer = null
        println("[kmp-menubar-poc] Running persisted settings validation probe.")
        if (stateStore.applyThresholdPreset(PrototypeThresholdPreset.BrightRoom)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun completeManualBrightnessHold() {
        manualBrightnessHoldTimer = null
        if (stateStore.onManualBrightnessHoldTimerFired()) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun selectModeOff() {
        if (stateStore.selectMode(PrototypeMode.Off)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun selectModeAuto() {
        if (stateStore.selectMode(PrototypeMode.Auto)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun selectModeManual() {
        if (stateStore.selectMode(PrototypeMode.Manual)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun persistDimRoomThresholds() {
        if (stateStore.applyThresholdPreset(PrototypeThresholdPreset.DimRoom)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun persistBrightRoomThresholds() {
        if (stateStore.applyThresholdPreset(PrototypeThresholdPreset.BrightRoom)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun sampleNow() {
        if (stateStore.sampleNow()) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun switchLight() {
        if (stateStore.forceAppearance(PrototypeAppearance.Light)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun switchDark() {
        if (stateStore.forceAppearance(PrototypeAppearance.Dark)) {
            recordAndScheduleUpdate()
        }
    }

    @ObjCAction
    fun openSettings() {
        settingsWindowController().show()
        syncSettingsWindow()
    }

    @ObjCAction
    fun quit() {
        pendingPresentationTimer?.invalidate()
        brightnessEventTimer?.invalidate()
        engineEventTimer?.invalidate()
        persistedSettingsProbeTimer?.invalidate()
        manualBrightnessHoldTimer?.invalidate()
        NSNotificationCenter.defaultCenter.removeObserver(this)
        ambientLightReader.close()
        application.terminate(null)
    }
}