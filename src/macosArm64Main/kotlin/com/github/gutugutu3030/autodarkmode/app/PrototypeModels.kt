package com.github.gutugutu3030.autodarkmode.app

/**
 * プロトタイプが扱う切り替えモードです。
 *
 * @property displayName UI に表示する名前です。
 */
enum class PrototypeMode(val displayName: String) {
    Off("Off"),
    Auto("Auto"),
    Manual("Manual"),
}

/**
 * プロトタイプが扱う外観状態です。
 *
 * @property displayName UI に表示する名前です。
 */
internal enum class PrototypeAppearance(val displayName: String) {
    Light("Light"),
    Dark("Dark"),
}

/**
 * 現在の状態を表すスナップショットです。
 *
 * @property lux 現在の周囲光です。
 * @property source 周囲光の取得経路です。
 * @property mode 現在の切り替えモードです。
 * @property appearance 現在の外観です。
 * @property sensorAvailable センサーが使えるかどうかです。
 * @property darkThresholdLux 暗い側しきい値です。
 * @property lightThresholdLux 明るい側しきい値です。
 * @property requiredConsecutiveSamples 連続サンプル数です。
 * @property cooldownSeconds クールダウン秒数です。
 * @property manualBrightness 手動モードで追跡する輝度です。
 * @property manualBrightnessKeyMonitoringEnabled 画面輝度キー監視が有効かどうかです。
 * @property manualBrightnessPermissionRequired 権限不足が発生しているかどうかです。
 * @property manualBrightnessHoldArmed 長押し判定が待機中かどうかです。
 * @property manualBrightnessRequiresReleaseAfterMax いったん離す必要があるかどうかです。
 * @property message 状態表示メッセージです。
 * @property lastError 最後のエラーメッセージです。
 * @property tickCount タイマーの経過回数です。
 */
internal data class PrototypeStatusState(
    val lux: Double = 240.0,
    val source: String = "iohid-bezelservices",
    val mode: PrototypeMode = PrototypeMode.Auto,
    val appearance: PrototypeAppearance? = PrototypeAppearance.Light,
    val sensorAvailable: Boolean = true,
    val darkThresholdLux: Double = 180.0,
    val lightThresholdLux: Double = 420.0,
    val requiredConsecutiveSamples: Int = 3,
    val cooldownSeconds: Double = 30.0,
    val manualBrightness: Double = 0.72,
    val manualBrightnessKeyMonitoringEnabled: Boolean = false,
    val manualBrightnessPermissionRequired: Boolean = false,
    val manualBrightnessHoldArmed: Boolean = false,
    val manualBrightnessRequiresReleaseAfterMax: Boolean = false,
    val message: String = "Kotlin-side menu coordinator is active.",
    val lastError: String? = null,
    val tickCount: Int = 0,
)

/**
 * イベント集計の統計情報です。
 *
 * @property brightnessEventCount 画面輝度イベント数です。
 * @property engineEventCount 周囲光エンジンイベント数です。
 * @property settingsEventCount 設定イベント数です。
 * @property presentationFlushCount 表示更新のフラッシュ回数です。
 * @property coalescedMutationCount 同一フラッシュにまとめられた変更数です。
 * @property maxMutationsPerFlush 1回のフラッシュでの最大変更数です。
 * @property pendingMutationsSinceLastFlush 次回フラッシュ待ちの変更数です。
 * @property lastFlushSummary 最後のフラッシュ要約です。
 */
internal data class PrototypeAggregationStats(
    val brightnessEventCount: Int = 0,
    val engineEventCount: Int = 0,
    val settingsEventCount: Int = 0,
    val presentationFlushCount: Int = 0,
    val coalescedMutationCount: Int = 0,
    val maxMutationsPerFlush: Int = 0,
    val pendingMutationsSinceLastFlush: Int = 0,
    val lastFlushSummary: String = "No flush yet.",
)

/**
 * 画面輝度イベントの方向です。
 */
internal enum class PrototypeBrightnessDirection {
    Up,
    Down,
}

/**
 * 画面輝度イベントの段階です。
 */
internal enum class PrototypeBrightnessPhase {
    Down,
    Up,
}

/**
 * 画面輝度イベントを表します。
 *
 * @property direction ボタンの操作方向です。
 * @property phase 押下シーケンスの段階です。
 * @property brightnessAfterSampling サンプリング後の輝度です。
 */
internal data class PrototypeBrightnessEvent(
    val direction: PrototypeBrightnessDirection,
    val phase: PrototypeBrightnessPhase,
    val brightnessAfterSampling: Double,
)

/**
 * 状態と統計をまとめた外部公開用スナップショットです。
 *
 * @property status 現在状態です。
 * @property stats 集計統計です。
 */
internal data class PrototypeCoordinatorSnapshot(
    val status: PrototypeStatusState,
    val stats: PrototypeAggregationStats,
)

/**
 * 周囲光の値を見やすい単位へ整形します。
 *
 * @param value 生の lux 値です。
 * @return 表示用文字列です。
 */
internal fun formatLux(value: Double): String {
    return if (value >= 1000.0) {
        "${((value / 100.0).toInt() / 10.0)} klx"
    } else {
        "${value.toInt()} lx"
    }
}