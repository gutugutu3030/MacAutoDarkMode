package com.github.gutugutu3030.autodarkmode.shared

enum class SwitchMode(
    val rawValue: String,
    val displayName: String,
    val menuDescription: String,
) {
    Off(
        rawValue = "off",
        displayName = "Off",
        menuDescription = "Switching disabled.",
    ),
    Auto(
        rawValue = "auto",
        displayName = "Auto",
        menuDescription = "Automatic switching by ambient light.",
    ),
    Manual(
        rawValue = "manual",
        displayName = "Manual",
        menuDescription = "Manual switching by display brightness.",
    );

    companion object {
        fun fromRawValue(raw: String?): SwitchMode? = entries.find { it.rawValue == raw }
    }
}