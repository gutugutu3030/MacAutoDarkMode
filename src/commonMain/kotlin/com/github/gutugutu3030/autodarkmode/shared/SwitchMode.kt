package com.github.gutugutu3030.autodarkmode.shared

/**
 * 設定の切り替えモードを表します。
 *
 * @property rawValue 永続化に使う生の文字列表現です。
 * @property displayName UI に表示する名前です。
 * @property menuDescription メニューや説明文で使う簡潔な説明です。
 */
enum class SwitchMode(
    val rawValue: String,
    val displayName: String,
    val menuDescription: String,
) {
    /** 自動切り替えを無効にします。 */
    Off(
        rawValue = "off",
        displayName = "Off",
        menuDescription = "Switching disabled.",
    ),
    /** 周囲光に応じた自動切り替えを有効にします。 */
    Auto(
        rawValue = "auto",
        displayName = "Auto",
        menuDescription = "Automatic switching by ambient light.",
    ),
    /** 画面輝度の変化に応じた手動切り替えを有効にします。 */
    Manual(
        rawValue = "manual",
        displayName = "Manual",
        menuDescription = "Manual switching by display brightness.",
    );

    companion object {
        /**
         * 永続化された文字列表現からモードを復元します。
         *
         * @param raw 保存済みの生文字列です。
         * @return 一致するモード。見つからない場合は `null` を返します。
         */
        fun fromRawValue(raw: String?): SwitchMode? = entries.find { it.rawValue == raw }
    }
}