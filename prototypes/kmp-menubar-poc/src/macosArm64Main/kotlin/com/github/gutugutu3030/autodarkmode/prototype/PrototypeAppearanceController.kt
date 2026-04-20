package com.github.gutugutu3030.autodarkmode.prototype

internal interface PrototypeAppearanceController {
    fun currentAppearance(): PrototypeAppearance?
    fun setAppearance(target: PrototypeAppearance): String?
}

internal class PrototypeInMemoryAppearanceController(
    initialAppearance: PrototypeAppearance = PrototypeAppearance.Light,
) : PrototypeAppearanceController {
    private var appearance: PrototypeAppearance = initialAppearance

    override fun currentAppearance(): PrototypeAppearance {
        return appearance
    }

    override fun setAppearance(target: PrototypeAppearance): String? {
        appearance = target
        return null
    }
}