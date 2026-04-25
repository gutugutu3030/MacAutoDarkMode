@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.github.gutugutu3030.autodarkmode.app

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSButton
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSSlider
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject

/**
* デーモン起動チェックボックスのタイプです。
 * NSButtonTypeSwitch もしくは NSButtonTypeOnOff である必要がありますが、Kotlin/Native の定数定義が古いため直接数値を指定します。
 */
private const val launchAtLoginCheckboxButtonType: ULong = 3uL

/**
 * 設定ウィンドウを管理します。
 *
 * @param stateStore 状態操作の入り口です。
 * @param launchAtLoginManager Launch at login の管理対象です。
 * @param onMutation 画面更新を要求するコールバックです。
 */
internal class SettingsWindowController(
    private val stateStore: StateStore,
    private val launchAtLoginManager: LaunchAtLoginManager,
    private val onMutation: (LaunchAtLoginSnapshot?) -> Unit,
) : NSObject() {
    /** 設定 UI を表示するウィンドウ本体です。 */
    private val window = NSWindow(
        contentRect = CGRectMake(0.0, 0.0, 560.0, 420.0),
        styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskMiniaturizable,
        backing = NSBackingStoreBuffered,
        defer = false,
    )

    /** 各 UI 部品を載せるルートビューです。 */
    private val rootView = NSView(frame = CGRectMake(0.0, 0.0, 560.0, 420.0))

    /** 状態欄の見出しラベルです。 */
    private val statusLabel = makeLabel(CGRectMake(20.0, 370.0, 520.0, 20.0), "Status")

    /** 現在の周囲光状態を表示する値ラベルです。 */
    private val statusValueLabel = makeLabel(CGRectMake(20.0, 346.0, 520.0, 20.0), "Ambient light: --")

    /** モード選択欄の見出しラベルです。 */
    private val modeLabel = makeLabel(CGRectMake(20.0, 304.0, 120.0, 20.0), "Switching mode")

    /** 切り替えモードを選択するポップアップです。 */
    private val modePopup = NSPopUpButton(frame = CGRectMake(160.0, 300.0, 180.0, 28.0), pullsDown = false)

    /** ダーク側しきい値欄の見出しラベルです。 */
    private val darkThresholdLabel = makeLabel(CGRectMake(20.0, 252.0, 520.0, 20.0), "Dark threshold")

    /** ダーク側しきい値を調整するスライダーです。 */
    private val darkThresholdSlider = NSSlider(frame = CGRectMake(20.0, 220.0, 360.0, 24.0))

    /** 現在照度をダーク側しきい値へ取り込むボタンです。 */
    private val darkThresholdCurrentButton = NSButton(frame = CGRectMake(400.0, 216.0, 140.0, 30.0))

    /** ライト側しきい値欄の見出しラベルです。 */
    private val lightThresholdLabel = makeLabel(CGRectMake(20.0, 188.0, 520.0, 20.0), "Light threshold")

    /** ライト側しきい値を調整するスライダーです。 */
    private val lightThresholdSlider = NSSlider(frame = CGRectMake(20.0, 156.0, 360.0, 24.0))

    /** 現在照度をライト側しきい値へ取り込むボタンです。 */
    private val lightThresholdCurrentButton = NSButton(frame = CGRectMake(400.0, 152.0, 140.0, 30.0))

    /** 起動時設定欄の見出しラベルです。 */
    private val startupLabel = makeLabel(CGRectMake(20.0, 128.0, 520.0, 20.0), "Startup")

    /** Launch at login を切り替えるチェックボックスです。 */
    private val launchAtLoginCheckbox = NSButton(frame = CGRectMake(20.0, 96.0, 320.0, 22.0))

    /** Launch at login の利用可否を補足表示するラベルです。 */
    private val launchAtLoginSupportLabel = makeWrappingLabel(CGRectMake(20.0, 36.0, 520.0, 50.0), "Launch at login is disabled.")

    init {
        // ウィンドウの外枠と基本プロパティを先に固定します。
        window.title = "autoDarkMode Settings"
        window.center()
        window.releasedWhenClosed = false
        window.contentView = rootView

        // モード選択はポップアップから直接イベントを受け取ります。
        modePopup.addItemsWithTitles(listOf(Mode.Off.displayName, Mode.Auto.displayName, Mode.Manual.displayName))
        modePopup.target = this
        modePopup.action = NSSelectorFromString("modeChanged")

        // しきい値スライダーは広い範囲を直接調整できるようにします。
        darkThresholdSlider.minValue = 0.0
        darkThresholdSlider.maxValue = 120000.0
        darkThresholdSlider.target = this
        darkThresholdSlider.action = NSSelectorFromString("darkThresholdChanged")
        darkThresholdCurrentButton.title = "Use Current Value"
        darkThresholdCurrentButton.target = this
        darkThresholdCurrentButton.action = NSSelectorFromString("useCurrentDarkThreshold")

        lightThresholdSlider.minValue = 0.0
        lightThresholdSlider.maxValue = 120000.0
        lightThresholdSlider.target = this
        lightThresholdSlider.action = NSSelectorFromString("lightThresholdChanged")
        lightThresholdCurrentButton.title = "Use Current Value"
        lightThresholdCurrentButton.target = this
        lightThresholdCurrentButton.action = NSSelectorFromString("useCurrentLightThreshold")

        // 起動時設定はチェックボックスで切り替えます。
        launchAtLoginCheckbox.title = "Launch automatically at login"
        launchAtLoginCheckbox.setButtonType(launchAtLoginCheckboxButtonType)
        launchAtLoginCheckbox.target = this
        launchAtLoginCheckbox.action = NSSelectorFromString("launchAtLoginToggled")

        listOf(
            statusLabel,
            statusValueLabel,
            modeLabel,
            modePopup,
            darkThresholdLabel,
            darkThresholdSlider,
            darkThresholdCurrentButton,
            lightThresholdLabel,
            lightThresholdSlider,
            lightThresholdCurrentButton,
            startupLabel,
            launchAtLoginCheckbox,
            launchAtLoginSupportLabel,
        ).forEach(rootView::addSubview)
    }

    /**
     * ウィンドウを表示します。
     */
    fun show() {
        window.makeKeyAndOrderFront(null)
        NSApplication.sharedApplication.activateIgnoringOtherApps(true)
    }

    /**
     * 画面表示を最新のスナップショットへ同期します。
     *
     * @param snapshot 現在状態です。
     * @param launchSnapshot 起動時実行の状態です。
     */
    fun render(snapshot: CoordinatorSnapshot, launchSnapshot: LaunchAtLoginSnapshot) {
        val state = snapshot.status
        statusValueLabel.stringValue = "Ambient light: ${formatLux(state.lux)} | Appearance: ${state.appearance?.displayName ?: "Unknown"} | Sensor: ${state.source}"

        // モードに応じてポップアップ選択を同期します。
        modePopup.selectItemAtIndex(
            when (state.mode) {
                Mode.Off -> 0
                Mode.Auto -> 1
                Mode.Manual -> 2
            }.toLong(),
        )

        darkThresholdLabel.stringValue = "Dark threshold: ${formatLux(state.darkThresholdLux)}"
        darkThresholdSlider.doubleValue = state.darkThresholdLux
        lightThresholdLabel.stringValue = "Light threshold: ${formatLux(state.lightThresholdLux)}"
        lightThresholdSlider.doubleValue = state.lightThresholdLux

        val autoControlsHidden = state.mode != Mode.Auto
        darkThresholdLabel.hidden = autoControlsHidden
        darkThresholdSlider.hidden = autoControlsHidden
        darkThresholdCurrentButton.hidden = autoControlsHidden
        darkThresholdCurrentButton.enabled = state.lux >= 0
        lightThresholdLabel.hidden = autoControlsHidden
        lightThresholdSlider.hidden = autoControlsHidden
        lightThresholdCurrentButton.hidden = autoControlsHidden
        lightThresholdCurrentButton.enabled = state.lux >= 0

        launchAtLoginCheckbox.state = if (launchSnapshot.isEnabled) NSControlStateValueOn else NSControlStateValueOff
        launchAtLoginCheckbox.enabled = launchSnapshot.canManageLaunchAgent
        launchAtLoginSupportLabel.stringValue = launchSnapshot.supportMessage
    }

    /**
     * モード変更イベントを処理します。
     */
    @ObjCAction
    fun modeChanged() {
        val selectedMode = when (modePopup.indexOfSelectedItem.toInt()) {
            0 -> Mode.Off
            2 -> Mode.Manual
            else -> Mode.Auto
        }
        if (stateStore.selectMode(selectedMode)) {
            onMutation(null)
        }
    }

    /**
     * 暗い側しきい値変更イベントを処理します。
     */
    @ObjCAction
    fun darkThresholdChanged() {
        if (stateStore.updateDarkThresholdLux(darkThresholdSlider.doubleValue)) {
            onMutation(null)
        }
    }

    /**
     * 明るい側しきい値変更イベントを処理します。
     */
    @ObjCAction
    fun lightThresholdChanged() {
        if (stateStore.updateLightThresholdLux(lightThresholdSlider.doubleValue)) {
            onMutation(null)
        }
    }

    /**
     * 現在の周囲光を暗い側しきい値へ取り込みます。
     */
    @ObjCAction
    fun useCurrentDarkThreshold() {
        if (stateStore.useCurrentLuxAsDarkThreshold()) {
            onMutation(null)
        }
    }

    /**
     * 現在の周囲光を明るい側しきい値へ取り込みます。
     */
    @ObjCAction
    fun useCurrentLightThreshold() {
        if (stateStore.useCurrentLuxAsLightThreshold()) {
            onMutation(null)
        }
    }

    /**
     * Launch at login の切り替えイベントを処理します。
     */
    @ObjCAction
    fun launchAtLoginToggled() {
        val launchSnapshot = launchAtLoginManager.setEnabled(launchAtLoginCheckbox.state == NSControlStateValueOn)
        onMutation(launchSnapshot)
    }

    /**
     * 通常ラベルを作ります。
     *
     * @param frame ラベルの配置枠です。
     * @param text 表示テキストです。
     * @return 生成したラベルです。
     */
    private fun makeLabel(frame: CValue<CGRect>, text: String): NSTextField {
        val label = NSTextField(frame = frame)
        label.stringValue = text
        label.editable = false
        label.bordered = false
        label.bezeled = false
        label.drawsBackground = false
        label.selectable = false
        return label
    }

    /**
     * 折り返し可能なラベルを作ります。
     *
     * @param frame ラベルの配置枠です。
     * @param text 表示テキストです。
     * @return 生成したラベルです。
     */
    private fun makeWrappingLabel(frame: CValue<CGRect>, text: String): NSTextField {
        val label = makeLabel(frame, text)
        label.maximumNumberOfLines = 3
        return label
    }
}
