package com.github.gutugutu3030.autodarkmode.prototype

import platform.CoreFoundation.CFAbsoluteTimeGetCurrent

internal class PrototypeStateStore(
    private val persistedSettings: PrototypePersistedSettingsClient,
    private val appearanceController: PrototypeAppearanceController = PrototypeInMemoryAppearanceController(),
    private val nowProvider: () -> Double = { CFAbsoluteTimeGetCurrent() },
) {
    companion object {
        const val manualLightBrightnessThreshold = 0.99
        const val manualLightLongPressSeconds = 0.35
    }

    private var state = PrototypeStatusState()
    private var stats = PrototypeAggregationStats()
    private var autoDarkCandidateCount = 0
    private var autoLightCandidateCount = 0
    private var lastAutoSwitchAtEpochSeconds: Double? = null
    private var simulatedManualBrightness = 0.72
    private var manualBrightnessUpIsPressed = false
    private var manualBrightnessWasNearMax = false

    fun bootstrap(sensorAvailable: Boolean) {
        val snapshot = persistedSettings.currentSnapshot()
        state = state.copy(
            appearance = appearanceController.currentAppearance(),
            sensorAvailable = sensorAvailable,
            mode = snapshot.mode,
            darkThresholdLux = snapshot.darkThresholdLux,
            lightThresholdLux = snapshot.lightThresholdLux,
            requiredConsecutiveSamples = snapshot.requiredConsecutiveSamples,
            cooldownSeconds = snapshot.cooldownSeconds,
        )
    }

    fun snapshot(): PrototypeCoordinatorSnapshot {
        return PrototypeCoordinatorSnapshot(status = state, stats = stats)
    }

    fun recordFlush(): PrototypeCoordinatorSnapshot {
        val pendingMutationCount = stats.pendingMutationsSinceLastFlush
        stats = stats.copy(
            presentationFlushCount = stats.presentationFlushCount + 1,
            coalescedMutationCount = stats.coalescedMutationCount + pendingMutationCount,
            maxMutationsPerFlush = maxOf(stats.maxMutationsPerFlush, pendingMutationCount),
            pendingMutationsSinceLastFlush = 0,
            lastFlushSummary = "Flush ${stats.presentationFlushCount + 1}: ${pendingMutationCount} mutation(s)",
        )
        return snapshot()
    }

    fun symbolName(): String = when {
        state.mode == PrototypeMode.Off -> "lightspectrum.horizontal"
        state.mode == PrototypeMode.Auto && !state.sensorAvailable -> "exclamationmark.triangle"
        state.appearance == PrototypeAppearance.Dark -> "moon.fill"
        state.appearance == PrototypeAppearance.Light -> "sun.max.fill"
        else -> "lightspectrum.horizontal"
    }

    fun setManualBrightnessKeyMonitoringEnabled(enabled: Boolean): Boolean {
        state = state.copy(
            manualBrightnessKeyMonitoringEnabled = enabled,
            manualBrightnessPermissionRequired = false,
            message = if (enabled) {
                "Brightness key monitoring is active for manual mode."
            } else {
                "Manual mode follows display brightness without brightness-key monitoring."
            },
            lastError = null,
        )
        if (!enabled) {
            resetManualBrightnessKeyState()
        }
        recordMutation()
        return true
    }

    fun reportManualBrightnessKeyMonitoringPermissionRequired(): Boolean {
        resetManualBrightnessKeyState()
        state = state.copy(
            manualBrightnessKeyMonitoringEnabled = false,
            manualBrightnessPermissionRequired = true,
            message = "Brightness key monitoring requires Accessibility permission. Manual mode still follows display brightness.",
            lastError = null,
        )
        recordMutation()
        return true
    }

    fun onManualBrightnessHoldTimerFired(): Boolean {
        if (state.mode != PrototypeMode.Manual || !state.manualBrightnessKeyMonitoringEnabled || !state.manualBrightnessHoldArmed) {
            return false
        }

        state = state.copy(manualBrightnessHoldArmed = false)
        if (appearanceForManualBrightness(state.manualBrightness) != PrototypeAppearance.Light) {
            state = state.copy(message = "Brightness moved away from maximum before hold completed.", lastError = null)
            recordMutation()
            return true
        }

        return applyAppearance(
            PrototypeAppearance.Light,
            "Held Brightness Up while display brightness was already at or near maximum.",
        )
    }

    fun reloadPersistedSettings(trigger: String): Boolean {
        val snapshot = persistedSettings.currentSnapshot()
        val changedFields = mutableListOf<String>()
        var nextState = state

        if (state.mode != snapshot.mode) {
            nextState = nextState.copy(mode = snapshot.mode)
            changedFields += "mode=${snapshot.mode.displayName}"
        }
        if (state.darkThresholdLux != snapshot.darkThresholdLux) {
            nextState = nextState.copy(darkThresholdLux = snapshot.darkThresholdLux)
            changedFields += "dark=${formatLux(snapshot.darkThresholdLux)}"
        }
        if (state.lightThresholdLux != snapshot.lightThresholdLux) {
            nextState = nextState.copy(lightThresholdLux = snapshot.lightThresholdLux)
            changedFields += "light=${formatLux(snapshot.lightThresholdLux)}"
        }
        if (state.requiredConsecutiveSamples != snapshot.requiredConsecutiveSamples) {
            nextState = nextState.copy(requiredConsecutiveSamples = snapshot.requiredConsecutiveSamples)
            changedFields += "samples=${snapshot.requiredConsecutiveSamples}"
        }
        if (state.cooldownSeconds != snapshot.cooldownSeconds) {
            nextState = nextState.copy(cooldownSeconds = snapshot.cooldownSeconds)
            changedFields += "cooldown=${snapshot.cooldownSeconds.toInt()}s"
        }

        if (changedFields.isEmpty()) {
            return false
        }

        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        state = nextState.copy(
            message = "Persisted settings applied from ${trigger}: ${changedFields.joinToString(", ")}.",
            lastError = null,
        )
        println("[autoDarkMode] Persisted settings applied from ${trigger}: ${changedFields.joinToString(", ")}.")
        recordMutation()
        return true
    }

    fun selectMode(mode: PrototypeMode): Boolean {
        persistedSettings.persistMode(mode)
        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        if (mode != PrototypeMode.Auto) {
            resetAutoCandidates()
        }
        if (mode != PrototypeMode.Manual) {
            resetManualBrightnessKeyState()
        }
        state = state.copy(mode = mode, message = "Persisted mode ${mode.displayName} from menu action.", lastError = null)
        recordMutation()
        return true
    }

    fun applyThresholdPreset(preset: PrototypeThresholdPreset): Boolean {
        persistedSettings.persistThresholdPreset(preset)
        val snapshot = persistedSettings.currentSnapshot()
        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        state = state.copy(
            darkThresholdLux = snapshot.darkThresholdLux,
            lightThresholdLux = snapshot.lightThresholdLux,
            requiredConsecutiveSamples = snapshot.requiredConsecutiveSamples,
            cooldownSeconds = snapshot.cooldownSeconds,
            message = "Persisted threshold preset ${preset.name} from menu action.",
            lastError = null,
        )
        recordMutation()
        return true
    }

    fun updateDarkThresholdLux(newValue: Double): Boolean {
        persistedSettings.persistThresholds(newValue, state.lightThresholdLux)
        return applyThresholdSnapshot("Dark threshold updated from settings window.")
    }

    fun updateLightThresholdLux(newValue: Double): Boolean {
        persistedSettings.persistThresholds(state.darkThresholdLux, newValue)
        return applyThresholdSnapshot("Light threshold updated from settings window.")
    }

    fun useCurrentLuxAsDarkThreshold(): Boolean {
        if (state.lux < 0) {
            return false
        }

        persistedSettings.persistThresholds(state.lux, state.lightThresholdLux)
        return applyThresholdSnapshot("Dark threshold captured from current ambient light.")
    }

    fun useCurrentLuxAsLightThreshold(): Boolean {
        if (state.lux < 0) {
            return false
        }

        persistedSettings.persistThresholds(state.darkThresholdLux, state.lux)
        return applyThresholdSnapshot("Light threshold captured from current ambient light.")
    }

    fun onBrightnessTimerTick(): Boolean {
        state = state.copy(tickCount = state.tickCount + 1)

        if (state.mode == PrototypeMode.Off) {
            state = state.copy(message = "Brightness event ignored while mode is Off.")
            recordMutation()
            return true
        }

        return handleBrightnessEvent(nextBrightnessEvent())
    }

    fun onBrightnessEvent(event: PrototypeBrightnessEvent): Boolean {
        return handleBrightnessEvent(event)
    }

    fun onEngineTimerTick(reading: NativeAmbientLightReading?, sensorAvailable: Boolean): Boolean {
        if (state.mode == PrototypeMode.Off) {
            state = state.copy(message = "Engine event ignored while mode is Off.")
            recordMutation()
            return true
        }

        val primaryMutation = handleEngineReading(reading, sensorAvailable)

        if (state.tickCount > 0 && state.tickCount % 4 == 0) {
            handleBrightnessEvent(
                PrototypeBrightnessEvent(
                    direction = PrototypeBrightnessDirection.Up,
                    phase = PrototypeBrightnessPhase.Down,
                    brightnessAfterSampling = 1.0,
                ),
            )
            state = state.copy(message = "Engine and brightness events were coalesced into the same flush.")
            recordMutation()
            return true
        }

        return primaryMutation
    }

    fun sampleNow(): Boolean {
        state = state.copy(
            lux = state.lux + 55.0,
            message = "Manual sample triggered on tick ${state.tickCount}.",
            lastError = null,
        )
        recordMutation()
        return true
    }

    fun forceAppearance(appearance: PrototypeAppearance): Boolean {
        return applyAppearance(appearance, "Forced ${appearance.displayName} from menu action.")
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

    private fun handleBrightnessEvent(event: PrototypeBrightnessEvent): Boolean {
        stats = stats.copy(brightnessEventCount = stats.brightnessEventCount + 1)

        if (state.mode != PrototypeMode.Manual) {
            state = state.copy(message = "BrightnessKeyMonitor event arrived, but mode is ${state.mode.displayName}.", lastError = null)
            recordMutation()
            return true
        }

        val brightness = event.brightnessAfterSampling.coerceIn(0.0, 1.0)
        val targetAppearance = appearanceForManualBrightness(brightness)
        val formattedBrightness = formatBrightnessPercent(brightness)
        val isNearMax = targetAppearance == PrototypeAppearance.Light

        state = state.copy(manualBrightness = brightness)

        if (state.manualBrightnessKeyMonitoringEnabled &&
            event.direction == PrototypeBrightnessDirection.Up &&
            event.phase == PrototypeBrightnessPhase.Up
        ) {
            manualBrightnessUpIsPressed = false
            state = state.copy(manualBrightnessRequiresReleaseAfterMax = false, manualBrightnessHoldArmed = false)

            if (isNearMax) {
                manualBrightnessWasNearMax = true
                state = state.copy(
                    message = "Brightness at or near maximum (${formattedBrightness}). Hold Brightness Up to switch to Light mode.",
                    lastError = null,
                )
                recordMutation()
                return true
            }
        }

        if (event.direction == PrototypeBrightnessDirection.Up && event.phase == PrototypeBrightnessPhase.Down) {
            manualBrightnessUpIsPressed = true
        }

        if (!state.manualBrightnessKeyMonitoringEnabled) {
            manualBrightnessWasNearMax = isNearMax
            state = state.copy(manualBrightnessHoldArmed = false, manualBrightnessRequiresReleaseAfterMax = false)
            return if (targetAppearance == PrototypeAppearance.Light) {
                applyAppearance(PrototypeAppearance.Light, "Display brightness at or near maximum.")
            } else {
                applyAppearance(PrototypeAppearance.Dark, "Display brightness below maximum (${formattedBrightness}).")
            }
        }

        if (targetAppearance == PrototypeAppearance.Dark) {
            manualBrightnessWasNearMax = false
            state = state.copy(manualBrightnessHoldArmed = false, manualBrightnessRequiresReleaseAfterMax = false)
            return applyAppearance(PrototypeAppearance.Dark, "Display brightness below maximum (${formattedBrightness}).")
        }

        if (shouldRequireReleaseAfterReachingManualMax(
                isNearMax = isNearMax,
                wasNearMax = manualBrightnessWasNearMax,
                brightnessUpIsPressed = manualBrightnessUpIsPressed,
                keyMonitoringEnabled = state.manualBrightnessKeyMonitoringEnabled,
            )) {
            manualBrightnessWasNearMax = true
            state = state.copy(
                manualBrightnessHoldArmed = false,
                manualBrightnessRequiresReleaseAfterMax = true,
                message = "Brightness at or near maximum (${formattedBrightness}). Release Brightness Up once, then hold it again to switch to Light mode.",
                lastError = null,
            )
            recordMutation()
            return true
        }

        if (state.manualBrightnessRequiresReleaseAfterMax) {
            manualBrightnessWasNearMax = true
            state = state.copy(
                manualBrightnessHoldArmed = false,
                message = "Brightness at or near maximum (${formattedBrightness}). Release Brightness Up once, then hold it again to switch to Light mode.",
                lastError = null,
            )
            recordMutation()
            return true
        }

        if (shouldArmManualBrightnessLongPress(
                direction = event.direction,
                brightnessAfterSampling = brightness,
                phase = event.phase,
                keyMonitoringEnabled = state.manualBrightnessKeyMonitoringEnabled,
                requiresReleaseAfterMax = state.manualBrightnessRequiresReleaseAfterMax,
            )) {
            manualBrightnessWasNearMax = true
            state = state.copy(
                manualBrightnessHoldArmed = true,
                message = "Brightness at or near maximum. Keep holding Brightness Up to switch to Light mode.",
                lastError = null,
            )
            recordMutation()
            return true
        }

        manualBrightnessWasNearMax = true
        state = state.copy(
            manualBrightnessHoldArmed = false,
            message = "Brightness at or near maximum (${formattedBrightness}). Hold Brightness Up to switch to Light mode.",
            lastError = null,
        )
        recordMutation()
        return true
    }

    private fun handleEngineReading(reading: NativeAmbientLightReading?, sensorAvailable: Boolean): Boolean {
        stats = stats.copy(engineEventCount = stats.engineEventCount + 1)

        if (state.mode != PrototypeMode.Auto) {
            state = state.copy(message = "AutoSwitchEngine event arrived, but mode is ${state.mode.displayName}.", lastError = null)
            recordMutation()
            return true
        }

        if (reading == null) {
            resetAutoCandidates()
            state = state.copy(
                sensorAvailable = sensorAvailable,
                source = NativeAmbientLightSource.Unavailable.displayName,
                message = if (sensorAvailable) {
                    "NativeAmbientLightReader: sample failed."
                } else {
                    "NativeAmbientLightReader: sensor unavailable on this Mac."
                },
                lastError = null,
            )
            recordMutation()
            return true
        }

        state = state.copy(
            sensorAvailable = true,
            lux = reading.lux,
            source = reading.source.displayName,
            lastError = null,
        )

        return evaluateAutoAppearance(reading)
    }

    private fun evaluateAutoAppearance(reading: NativeAmbientLightReading): Boolean {
        if (!state.sensorAvailable) {
            resetAutoCandidates()
            state = state.copy(message = "Ambient light sensor unavailable.", lastError = null)
            recordMutation()
            return true
        }

        if (reading.lux <= state.darkThresholdLux) {
            autoDarkCandidateCount += 1
            autoLightCandidateCount = 0
            val candidateMessage = "Dark candidate ${autoDarkCandidateCount}/${state.requiredConsecutiveSamples} at ${formatLux(reading.lux)}."

            if (autoDarkCandidateCount >= state.requiredConsecutiveSamples) {
                return applyAppearance(PrototypeAppearance.Dark, "Ambient light dropped to ${formatLux(reading.lux)}.")
            }

            state = state.copy(message = candidateMessage, lastError = null)
            recordMutation()
            return true
        }

        if (reading.lux >= state.lightThresholdLux) {
            autoLightCandidateCount += 1
            autoDarkCandidateCount = 0
            val candidateMessage = "Light candidate ${autoLightCandidateCount}/${state.requiredConsecutiveSamples} at ${formatLux(reading.lux)}."

            if (autoLightCandidateCount >= state.requiredConsecutiveSamples) {
                return applyAppearance(PrototypeAppearance.Light, "Ambient light rose to ${formatLux(reading.lux)}.")
            }

            state = state.copy(message = candidateMessage, lastError = null)
            recordMutation()
            return true
        }

        resetAutoCandidates()
        state = state.copy(message = "Inside hysteresis band at ${formatLux(reading.lux)}.", lastError = null)
        recordMutation()
        return true
    }

    private fun applyAppearance(target: PrototypeAppearance, reason: String): Boolean {
        val currentAppearance = appearanceController.currentAppearance() ?: state.appearance
        state = state.copy(appearance = currentAppearance)

        if (state.mode == PrototypeMode.Auto) {
            val lastSwitchAt = lastAutoSwitchAtEpochSeconds
            if (lastSwitchAt != null) {
                val elapsedSeconds = nowProvider() - lastSwitchAt
                if (elapsedSeconds < state.cooldownSeconds) {
                    resetAutoCandidates()
                    state = state.copy(
                        message = "Cooldown active. Next change allowed in ${(state.cooldownSeconds - elapsedSeconds).coerceAtLeast(0.0).toInt()}s.",
                        lastError = null,
                    )
                    recordMutation()
                    return true
                }
            }
        }

        if (currentAppearance == target) {
            resetAutoCandidates()
            state = state.copy(
                appearance = target,
                message = "Already in ${target.displayName} mode.",
                lastError = null,
            )
            recordMutation()
            return true
        }

        val error = appearanceController.setAppearance(target)
        if (error != null) {
            resetAutoCandidates()
            state = state.copy(
                appearance = currentAppearance,
                message = "Failed to change appearance.",
                lastError = error,
            )
            recordMutation()
            return true
        }

        if (state.mode == PrototypeMode.Auto) {
            lastAutoSwitchAtEpochSeconds = nowProvider()
        }
        resetAutoCandidates()
        state = state.copy(
            appearance = target,
            message = reason,
            lastError = null,
        )
        recordMutation()
        return true
    }

    private fun resetAutoCandidates() {
        autoDarkCandidateCount = 0
        autoLightCandidateCount = 0
    }

    private fun applyThresholdSnapshot(message: String): Boolean {
        val snapshot = persistedSettings.currentSnapshot()
        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        state = state.copy(
            darkThresholdLux = snapshot.darkThresholdLux,
            lightThresholdLux = snapshot.lightThresholdLux,
            requiredConsecutiveSamples = snapshot.requiredConsecutiveSamples,
            cooldownSeconds = snapshot.cooldownSeconds,
            message = message,
            lastError = null,
        )
        recordMutation()
        return true
    }

    private fun resetManualBrightnessKeyState() {
        manualBrightnessUpIsPressed = false
        manualBrightnessWasNearMax = false
        state = state.copy(
            manualBrightnessHoldArmed = false,
            manualBrightnessRequiresReleaseAfterMax = false,
        )
    }

    private fun appearanceForManualBrightness(brightness: Double): PrototypeAppearance {
        return if (brightness >= manualLightBrightnessThreshold) {
            PrototypeAppearance.Light
        } else {
            PrototypeAppearance.Dark
        }
    }

    private fun shouldArmManualBrightnessLongPress(
        direction: PrototypeBrightnessDirection,
        brightnessAfterSampling: Double,
        phase: PrototypeBrightnessPhase,
        keyMonitoringEnabled: Boolean,
        requiresReleaseAfterMax: Boolean,
    ): Boolean {
        if (!keyMonitoringEnabled) {
            return false
        }
        if (direction != PrototypeBrightnessDirection.Up) {
            return false
        }
        if (phase != PrototypeBrightnessPhase.Down) {
            return false
        }
        if (requiresReleaseAfterMax) {
            return false
        }
        return appearanceForManualBrightness(brightnessAfterSampling) == PrototypeAppearance.Light
    }

    private fun shouldRequireReleaseAfterReachingManualMax(
        isNearMax: Boolean,
        wasNearMax: Boolean,
        brightnessUpIsPressed: Boolean,
        keyMonitoringEnabled: Boolean,
    ): Boolean {
        if (!keyMonitoringEnabled) {
            return false
        }
        if (!isNearMax) {
            return false
        }
        if (!brightnessUpIsPressed) {
            return false
        }
        return !wasNearMax
    }

    private fun formatBrightnessPercent(brightness: Double): String {
        return "${(brightness * 100).toInt()}%"
    }

    private fun recordMutation() {
        stats = stats.copy(pendingMutationsSinceLastFlush = stats.pendingMutationsSinceLastFlush + 1)
    }
}