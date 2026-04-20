package com.github.gutugutu3030.autodarkmode.prototype

internal class PrototypeStateStore(
    private val persistedSettings: PrototypePersistedSettingsClient,
) {
    private var state = PrototypeStatusState()
    private var stats = PrototypeAggregationStats()
    private var simulatedManualBrightness = 0.72

    fun bootstrap(sensorAvailable: Boolean) {
        val snapshot = persistedSettings.currentSnapshot()
        state = state.copy(
            sensorAvailable = sensorAvailable,
            mode = snapshot.mode,
            darkThresholdLux = snapshot.darkThresholdLux,
            lightThresholdLux = snapshot.lightThresholdLux,
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

        if (changedFields.isEmpty()) {
            return false
        }

        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        state = nextState.copy(message = "Persisted settings applied from ${trigger}: ${changedFields.joinToString(", ") }.")
        println("[kmp-menubar-poc] Persisted settings applied from ${trigger}: ${changedFields.joinToString(", ")}.")
        recordMutation()
        return true
    }

    fun selectMode(mode: PrototypeMode): Boolean {
        persistedSettings.persistMode(mode)
        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        state = state.copy(mode = mode, message = "Persisted mode ${mode.displayName} from menu action.")
        recordMutation()
        return true
    }

    fun applyThresholdPreset(preset: PrototypeThresholdPreset): Boolean {
        persistedSettings.persistThresholdPreset(preset)
        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        state = state.copy(
            darkThresholdLux = preset.darkThresholdLux,
            lightThresholdLux = maxOf(preset.lightThresholdLux, preset.darkThresholdLux),
            message = "Persisted threshold preset ${preset.name} from menu action.",
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

        if (state.tickCount % 4 == 0) {
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
        )
        recordMutation()
        return true
    }

    fun forceAppearance(appearance: PrototypeAppearance): Boolean {
        state = state.copy(
            appearance = appearance,
            message = "Forced ${appearance.displayName} from menu action.",
        )
        recordMutation()
        return true
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
            state = state.copy(message = "BrightnessKeyMonitor event arrived, but mode is ${state.mode.displayName}.")
            recordMutation()
            return true
        }

        state = state.copy(
            appearance = if (event.brightnessAfterSampling >= 0.99) {
                PrototypeAppearance.Light
            } else {
                PrototypeAppearance.Dark
            },
            message = "BrightnessKeyMonitor: ${event.direction.name.lowercase()} ${event.phase.name.lowercase()} at ${(event.brightnessAfterSampling * 100).toInt()}%.",
        )
        recordMutation()
        return true
    }

    private fun handleEngineReading(reading: NativeAmbientLightReading?, sensorAvailable: Boolean): Boolean {
        stats = stats.copy(engineEventCount = stats.engineEventCount + 1)

        if (state.mode != PrototypeMode.Auto) {
            state = state.copy(message = "AutoSwitchEngine event arrived, but mode is ${state.mode.displayName}.")
            recordMutation()
            return true
        }

        if (reading == null) {
            state = state.copy(
                sensorAvailable = sensorAvailable,
                source = NativeAmbientLightSource.Unavailable.displayName,
                message = if (sensorAvailable) {
                    "NativeAmbientLightReader: sample failed."
                } else {
                    "NativeAmbientLightReader: sensor unavailable on this Mac."
                },
            )
            recordMutation()
            return true
        }

        state = state.copy(
            sensorAvailable = true,
            lux = reading.lux,
            source = reading.source.displayName,
            appearance = when {
                reading.lux <= state.darkThresholdLux -> PrototypeAppearance.Dark
                reading.lux >= state.lightThresholdLux -> PrototypeAppearance.Light
                else -> state.appearance
            },
            message = "NativeAmbientLightReader: ${reading.source.displayName} ${formatLux(reading.lux)}.",
        )
        recordMutation()
        return true
    }

    private fun recordMutation() {
        stats = stats.copy(pendingMutationsSinceLastFlush = stats.pendingMutationsSinceLastFlush + 1)
    }
}