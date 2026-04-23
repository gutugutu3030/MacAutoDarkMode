@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.github.gutugutu3030.autodarkmode.app

import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.toKString
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSImage
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSStatusBar
import platform.AppKit.NSStatusItem
import platform.AppKit.NSVariableStatusItemLength
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSTimer
import platform.Foundation.NSUserDefaultsDidChangeNotification
import platform.darwin.NSObject
import platform.posix.getenv

private lateinit var coordinator: StatusBarCoordinator

/**
 * アプリのエントリーポイントです。
 *
 * @param args 起動引数です。
 */
fun main(args: Array<String>) {
    if (CalibrationCli.canHandle(args.toList())) {
        CalibrationCli.run(args.toList())
        return
    }

    val application = NSApplication.sharedApplication()
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory)

    coordinator = StatusBarCoordinator(application)
    coordinator.start()

    application.run()
}

/**
 * メニューバー常駐の状態同期とユーザー操作をまとめます。
 *
 * @param application 終了制御に使うアプリケーションです。
 */
private class StatusBarCoordinator(
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
    private val persistedSettings = PersistedSettings()
    private val stateStore = StateStore(
        persistedSettings,
        appearanceController = SystemAppearanceController(),
    )
    private val launchAtLoginManager = LaunchAtLoginManager()

    private var brightnessEventTimer: NSTimer? = null
    private var engineEventTimer: NSTimer? = null
    private var pendingPresentationTimer: NSTimer? = null
    private var persistedSettingsProbeTimer: NSTimer? = null
    private var manualBrightnessHoldTimer: NSTimer? = null
    private var updateScheduled = false
    private var settingsWindowController: SettingsWindowController? = null
    private var pendingLaunchAtLoginSnapshot: LaunchAtLoginSnapshot? = null

    /**
     * アプリ起動時の初期化を行います。
     */
    fun start() {
        // メニュー構成、通知購読、センサー初期化を順に進めます。
        configureMenu()
        observePersistedSettings()
        val sensorAvailable = ambientLightReader.isSensorAvailable()
        stateStore.bootstrap(sensorAvailable = sensorAvailable)
        configureManualBrightnessMonitoring()
        println("[autoDarkMode] NativeAmbientLightReader startup availability: ${stateStore.snapshot().status.sensorAvailable}")
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

    /**
     * メニュー項目を初期化します。
     */
    private fun configureMenu() {
        luxItem.enabled = false
        sourceItem.enabled = false
        appearanceItem.enabled = false
        thresholdItem.enabled = false
        manualKeysItem.enabled = false
        eventStatsItem.enabled = false
        flushStatsItem.enabled = false
        messageItem.enabled = false

        // モード項目はアクションごとに選択状態を同期します。
        modeOffItem.title = "Mode: Off"
        modeOffItem.target = this
        modeOffItem.setAction(NSSelectorFromString("selectModeOff"))

        modeAutoItem.title = "Mode: Auto"
        modeAutoItem.target = this
        modeAutoItem.setAction(NSSelectorFromString("selectModeAuto"))

        modeManualItem.title = "Mode: Manual"
        modeManualItem.target = this
        modeManualItem.setAction(NSSelectorFromString("selectModeManual"))

        // しきい値プリセットは永続化設定を書き換えるメニューとして並べます。
        val dimRoomPresetItem = NSMenuItem(title = ThresholdPreset.DimRoom.menuTitle, action = NSSelectorFromString("persistDimRoomThresholds"), keyEquivalent = "")
        dimRoomPresetItem.target = this

        val brightRoomPresetItem = NSMenuItem(title = ThresholdPreset.BrightRoom.menuTitle, action = NSSelectorFromString("persistBrightRoomThresholds"), keyEquivalent = "")
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

        // 情報表示、操作項目、終了項目を順番に登録します。
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

    /**
     * 表示更新をまとめて遅延スケジュールします。
     */
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

    /**
     * 遅延中の表示更新をまとめて反映します。
     */
    @ObjCAction
    fun flushScheduledPresentationUpdate() {
        val snapshot = stateStore.recordFlush()
        val launchSnapshot = pendingLaunchAtLoginSnapshot ?: launchAtLoginManager.refresh()
        println(
            "[autoDarkMode] ${snapshot.stats.lastFlushSummary}; brightness=${snapshot.stats.brightnessEventCount}, " +
                "engine=${snapshot.stats.engineEventCount}, settings=${snapshot.stats.settingsEventCount}, mode=${snapshot.status.mode.displayName}",
        )
        pendingPresentationTimer = null
        updateScheduled = false
        syncManualBrightnessHoldTimer(snapshot)
        updatePresentation(snapshot)
        syncSettingsWindow(snapshot, launchSnapshot)
        pendingLaunchAtLoginSnapshot = null
    }

    /**
     * 状態スナップショットをメニューへ反映します。
     *
     * @param snapshot 反映対象です。
     */
    private fun updatePresentation(snapshot: CoordinatorSnapshot = stateStore.snapshot()) {
        val state = snapshot.status
        val stats = snapshot.stats

        // 状態の要点をメニュー項目へそのまま流し込みます。
        luxItem.title = "Ambient light: ${formatLux(state.lux)}"
        sourceItem.title = "Sensor path: ${state.source}"
        appearanceItem.title = "Appearance: ${state.appearance?.displayName ?: "Unknown"}"

        modeOffItem.state = if (state.mode == Mode.Off) NSControlStateValueOn else NSControlStateValueOff
        modeAutoItem.state = if (state.mode == Mode.Auto) NSControlStateValueOn else NSControlStateValueOff
        modeManualItem.state = if (state.mode == Mode.Manual) NSControlStateValueOn else NSControlStateValueOff

        thresholdItem.hidden = state.mode != Mode.Auto
        thresholdItem.title = "Dark <= ${formatLux(state.darkThresholdLux)} / Light >= ${formatLux(state.lightThresholdLux)} / ${state.requiredConsecutiveSamples} samples / ${state.cooldownSeconds.toInt()}s"
        manualKeysItem.hidden = state.mode != Mode.Manual
        manualKeysItem.title = when {
            state.manualBrightnessPermissionRequired -> "Brightness keys: Accessibility permission required"
            state.manualBrightnessHoldArmed -> "Brightness keys: Hold armed (${StateStore.manualLightLongPressSeconds}s)"
            state.manualBrightnessRequiresReleaseAfterMax -> "Brightness keys: Release required before next hold"
            state.manualBrightnessKeyMonitoringEnabled -> "Brightness keys: Active"
            else -> "Brightness keys: Follows display brightness only"
        }
        eventStatsItem.title = "Events: brightness ${stats.brightnessEventCount} / engine ${stats.engineEventCount} / settings ${stats.settingsEventCount}"
        flushStatsItem.title = "Flushes: ${stats.presentationFlushCount}, max batch ${stats.maxMutationsPerFlush}"
        messageItem.title = state.lastError ?: state.message

        statusItem.button?.image = NSImage.imageWithSystemSymbolName(stateStore.symbolName(), accessibilityDescription = "autoDarkMode")
        statusItem.button?.toolTip = "autoDarkMode (${state.mode.displayName})"
    }

    /**
     * 永続化設定変更通知を監視します。
     */
    private fun observePersistedSettings() {
        NSNotificationCenter.defaultCenter.addObserver(
            this,
            selector = NSSelectorFromString("persistedSettingsDidChange:"),
            name = NSUserDefaultsDidChangeNotification,
            `object` = null,
        )
    }

    /**
     * 検証用の設定確認を必要に応じて予約します。
     */
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

    /**
     * 手動モード用の Brightness キー監視を初期化します。
     */
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

    /**
     * 長押しで Light へ切り替えるタイマーを同期します。
     *
     * @param snapshot 現在状態です。
     */
    private fun syncManualBrightnessHoldTimer(snapshot: CoordinatorSnapshot = stateStore.snapshot()) {
        if (!snapshot.status.manualBrightnessHoldArmed) {
            manualBrightnessHoldTimer?.invalidate()
            manualBrightnessHoldTimer = null
            return
        }

        if (manualBrightnessHoldTimer != null) {
            return
        }

        manualBrightnessHoldTimer = scheduleTimerInCommonModes(
            StateStore.manualLightLongPressSeconds,
            repeats = false,
            selectorName = "completeManualBrightnessHold",
        )
    }

    /**
     * RunLoop の共通モードでタイマーを作成します。
     *
     * @param interval 発火間隔です。
     * @param repeats 繰り返し実行するかどうかです。
     * @param selectorName 実行するセレクタ名です。
     * @return 生成したタイマーです。
     */
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

    /**
     * 画面更新と設定ウィンドウ更新をまとめて再予約します。
     */
    private fun recordAndScheduleUpdate(launchSnapshot: LaunchAtLoginSnapshot? = null) {
        if (launchSnapshot != null) {
            pendingLaunchAtLoginSnapshot = launchSnapshot
        }
        scheduleUpdatePresentation()
        syncSettingsWindow(
            launchSnapshot = launchSnapshot ?: pendingLaunchAtLoginSnapshot ?: launchAtLoginManager.refresh(),
        )
    }

    /**
     * 設定ウィンドウが開いている場合だけ最新状態を反映します。
     *
     * @param snapshot 反映する状態です。
     */
    private fun syncSettingsWindow(
        snapshot: CoordinatorSnapshot = stateStore.snapshot(),
        launchSnapshot: LaunchAtLoginSnapshot = pendingLaunchAtLoginSnapshot ?: launchAtLoginManager.refresh(),
    ) {
        settingsWindowController?.render(snapshot, launchSnapshot)
    }

    /**
     * 設定ウィンドウコントローラを遅延生成します。
     *
     * @return 設定ウィンドウコントローラです。
     */
    private fun settingsWindowController(): SettingsWindowController {
        val existingController = settingsWindowController
        if (existingController != null) {
            return existingController
        }

        val createdController = SettingsWindowController(
            stateStore = stateStore,
            launchAtLoginManager = launchAtLoginManager,
            onMutation = { launchSnapshot -> recordAndScheduleUpdate(launchSnapshot) },
        )
        settingsWindowController = createdController
        createdController.render(stateStore.snapshot(), launchAtLoginManager.refresh())
        return createdController
    }

    /**
     * Brightness タイマーの発火を処理します。
     *
     * @param sender 発火したタイマーです。
     */
    @ObjCAction
    fun emitBrightnessEvent(sender: NSTimer) {
        if (stateStore.onBrightnessTimerTick()) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * 周囲光エンジンの発火を処理します。
     *
     * @param sender 発火したタイマーです。
     */
    @ObjCAction
    fun emitEngineEvent(sender: NSTimer) {
        val reading = ambientLightReader.currentReading()
        val sensorAvailable = if (reading == null) ambientLightReader.isSensorAvailable() else true
        if (stateStore.onEngineTimerTick(reading = reading, sensorAvailable = sensorAvailable)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * 既定値確認用の通知を処理します。
     *
     * @param notification 受信通知です。
     */
    @ObjCAction
    fun persistedSettingsDidChange(notification: NSNotification) {
        if (stateStore.reloadPersistedSettings(trigger = "NSUserDefaultsDidChangeNotification")) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * 検証用のしきい値プリセット適用を行います。
     */
    @ObjCAction
    fun runPersistedSettingsValidationProbe() {
        persistedSettingsProbeTimer = null
        println("[autoDarkMode] Running persisted settings validation probe.")
        if (stateStore.applyThresholdPreset(ThresholdPreset.BrightRoom)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * 手動モード用の長押し完了を処理します。
     */
    @ObjCAction
    fun completeManualBrightnessHold() {
        manualBrightnessHoldTimer = null
        if (stateStore.onManualBrightnessHoldTimerFired()) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * Off モードを選択します。
     */
    @ObjCAction
    fun selectModeOff() {
        if (stateStore.selectMode(Mode.Off)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * Auto モードを選択します。
     */
    @ObjCAction
    fun selectModeAuto() {
        if (stateStore.selectMode(Mode.Auto)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * Manual モードを選択します。
     */
    @ObjCAction
    fun selectModeManual() {
        if (stateStore.selectMode(Mode.Manual)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * 暗い部屋向けプリセットを保存します。
     */
    @ObjCAction
    fun persistDimRoomThresholds() {
        if (stateStore.applyThresholdPreset(ThresholdPreset.DimRoom)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * 明るい部屋向けプリセットを保存します。
     */
    @ObjCAction
    fun persistBrightRoomThresholds() {
        if (stateStore.applyThresholdPreset(ThresholdPreset.BrightRoom)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * その場でサンプルを取得します。
     */
    @ObjCAction
    fun sampleNow() {
        if (stateStore.sampleNow()) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * Light 外観へ強制切り替えします。
     */
    @ObjCAction
    fun switchLight() {
        if (stateStore.forceAppearance(Appearance.Light)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * Dark 外観へ強制切り替えします。
     */
    @ObjCAction
    fun switchDark() {
        if (stateStore.forceAppearance(Appearance.Dark)) {
            recordAndScheduleUpdate()
        }
    }

    /**
     * 設定ウィンドウを開きます。
     */
    @ObjCAction
    fun openSettings() {
        settingsWindowController().show()
        syncSettingsWindow()
    }

    /**
     * アプリを終了します。
     */
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
