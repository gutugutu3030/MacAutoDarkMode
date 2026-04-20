package com.github.gutugutu3030.autodarkmode.prototype

import platform.CoreFoundation.CFAbsoluteTimeGetCurrent

internal class PrototypeStateStore(
    private val persistedSettings: PrototypePersistedSettingsClient,
    private val appearanceController: PrototypeAppearanceController = PrototypeInMemoryAppearanceController(),
    private val nowProvider: () -> Double = { CFAbsoluteTimeGetCurrent() },
) {
    private var state = PrototypeStatusState()
    private var stats = PrototypeAggregationStats()
    private var autoDarkCandidateCount = 0
    private var autoLightCandidateCount = 0
    private var lastAutoSwitchAtEpochSeconds: Double? = null
    private var simulatedManualBrightness = 0.72

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
        println("[kmp-menubar-poc] Persisted settings applied from ${trigger}: ${changedFields.joinToString(", ")}.")
        recordMutation()
        return true
    }

    fun selectMode(mode: PrototypeMode): Boolean {
        persistedSettings.persistMode(mode)
        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        if (mode != PrototypeMode.Auto) {
            resetAutoCandidates()
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

    fun onBrightnessTimerTick(): Boolean {
        state = state.copy(tickCount = state.tickCount + 1)

        if (state.mode == PrototypeMode.Off) {
            state = state.copy(message = "Brightness event ignored while mode is Off.")
            recordMutation()
            return true
        }

        return handleBrightnessEvent(nextBrightnessEvent())
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

        val targetAppearance = if (event.brightnessAfterSampling >= 0.99) {
            PrototypeAppearance.Light
        } else {
            PrototypeAppearance.Dark
        }
        return applyAppearance(
            targetAppearance,
            "BrightnessKeyMonitor: ${event.direction.name.lowercase()} ${event.phase.name.lowercase()} at ${(event.brightnessAfterSampling * 100).toInt()}%.",
        )
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

    private fun recordMutation() {
        stats = stats.copy(pendingMutationsSinceLastFlush = stats.pendingMutationsSinceLastFlush + 1)
    }
}