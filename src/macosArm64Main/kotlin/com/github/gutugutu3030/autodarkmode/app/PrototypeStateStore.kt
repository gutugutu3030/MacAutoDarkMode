package com.github.gutugutu3030.autodarkmode.app

import platform.CoreFoundation.CFAbsoluteTimeGetCurrent

/**
 * プロトタイプ版の状態管理と自動切り替えロジックをまとめます。
 *
 * @param persistedSettings 永続化設定の読み書き先です。
 * @param appearanceController 外観を読み書きするコントローラです。
 * @param nowProvider 現在時刻の取得関数です。
 */
internal class StateStore(
    private val persistedSettings: PersistedSettingsClient,
    private val appearanceController: AppearanceController = InMemoryAppearanceController(),
    private val nowProvider: () -> Double = { CFAbsoluteTimeGetCurrent() },
) {
    companion object {
        /** 手動モードで明るい側とみなす輝度のしきい値です。 */
        const val manualLightBrightnessThreshold = 0.99
        /** 長押し判定に必要な Brightness Up の押下時間です。 */
        const val manualLightLongPressSeconds = 0.35
    }

    private var state = StatusState()
    private var stats = AggregationStats()
    private var autoDarkCandidateCount = 0
    private var autoLightCandidateCount = 0
    private var lastAutoSwitchAtEpochSeconds: Double? = null
    private var simulatedManualBrightness = 0.72
    private var manualBrightnessUpIsPressed = false
    private var manualBrightnessWasNearMax = false

    /**
     * 永続化設定と外観状態を読み込み、初期状態を組み立てます。
     *
     * @param sensorAvailable 周囲光センサーが利用可能かどうかです。
     */
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

    /**
     * 現在の状態と統計を返します。
     *
     * @return 現在のスナップショットです。
     */
    fun snapshot(): CoordinatorSnapshot {
        return CoordinatorSnapshot(status = state, stats = stats)
    }

    /**
     * まとめて反映される変更のフラッシュを記録します。
     *
     * @return フラッシュ後のスナップショットです。
     */
    fun recordFlush(): CoordinatorSnapshot {
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

    /**
     * 状態に応じた SF Symbols 名を返します。
     *
     * @return アイコン名です。
     */
    fun symbolName(): String = when {
        state.mode == Mode.Off -> "lightspectrum.horizontal"
        state.mode == Mode.Auto && !state.sensorAvailable -> "exclamationmark.triangle"
        state.appearance == Appearance.Dark -> "moon.fill"
        state.appearance == Appearance.Light -> "sun.max.fill"
        else -> "lightspectrum.horizontal"
    }

    /**
     * 手動モードでの輝度キー監視を有効化または無効化します。
     *
     * @param enabled 有効にする場合は `true` です。
     * @return 常に `true` を返します。
     */
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

    /**
     * 手動モードでの監視に Accessibility 権限が必要であることを記録します。
     *
     * @return 常に `true` を返します。
     */
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

    /**
     * Brightness Up の長押しタイマーが満了したときの処理です。
     *
     * @return 状態が変わった場合は `true` です。
     */
    fun onManualBrightnessHoldTimerFired(): Boolean {
        if (state.mode != Mode.Manual || !state.manualBrightnessKeyMonitoringEnabled || !state.manualBrightnessHoldArmed) {
            return false
        }

        state = state.copy(manualBrightnessHoldArmed = false)
        if (appearanceForManualBrightness(state.manualBrightness) != Appearance.Light) {
            state = state.copy(message = "Brightness moved away from maximum before hold completed.", lastError = null)
            recordMutation()
            return true
        }

        return applyAppearance(
            Appearance.Light,
            "Held Brightness Up while display brightness was already at or near maximum.",
        )
    }

    /**
     * 永続化設定の変化を再読込して反映します。
     *
     * @param trigger 再読込の契機です。
     * @return 変更があった場合は `true` です。
     */
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

    /**
     * モードを切り替えます。
     *
     * @param mode 切り替え先です。
     * @return 変更があった場合は `true` です。
     */
    fun selectMode(mode: Mode): Boolean {
        persistedSettings.persistMode(mode)
        stats = stats.copy(settingsEventCount = stats.settingsEventCount + 1)
        if (mode != Mode.Auto) {
            resetAutoCandidates()
        }
        if (mode != Mode.Manual) {
            resetManualBrightnessKeyState()
        }
        state = state.copy(mode = mode, message = "Persisted mode ${mode.displayName} from menu action.", lastError = null)
        recordMutation()
        return true
    }

    /**
     * 既定のしきい値プリセットを適用します。
     *
     * @param preset 適用するプリセットです。
     * @return 変更があった場合は `true` です。
     */
    fun applyThresholdPreset(preset: ThresholdPreset): Boolean {
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

    /**
     * 暗い側しきい値を更新します。
     *
     * @param newValue 新しい値です。
     * @return 変更があった場合は `true` です。
     */
    fun updateDarkThresholdLux(newValue: Double): Boolean {
        persistedSettings.persistThresholds(newValue, state.lightThresholdLux)
        return applyThresholdSnapshot("Dark threshold updated from settings window.")
    }

    /**
     * 明るい側しきい値を更新します。
     *
     * @param newValue 新しい値です。
     * @return 変更があった場合は `true` です。
     */
    fun updateLightThresholdLux(newValue: Double): Boolean {
        persistedSettings.persistThresholds(state.darkThresholdLux, newValue)
        return applyThresholdSnapshot("Light threshold updated from settings window.")
    }

    /**
     * 現在の周囲光を暗い側しきい値へ取り込みます。
     *
     * @return 取り込みできた場合は `true` です。
     */
    fun useCurrentLuxAsDarkThreshold(): Boolean {
        if (state.lux < 0) {
            return false
        }

        persistedSettings.persistThresholds(state.lux, state.lightThresholdLux)
        return applyThresholdSnapshot("Dark threshold captured from current ambient light.")
    }

    /**
     * 現在の周囲光を明るい側しきい値へ取り込みます。
     *
     * @return 取り込みできた場合は `true` です。
     */
    fun useCurrentLuxAsLightThreshold(): Boolean {
        if (state.lux < 0) {
            return false
        }

        persistedSettings.persistThresholds(state.darkThresholdLux, state.lux)
        return applyThresholdSnapshot("Light threshold captured from current ambient light.")
    }

    /**
     * 画面輝度タイマーのティックを処理します。
     *
     * @return 変更があった場合は `true` です。
     */
    fun onBrightnessTimerTick(): Boolean {
        state = state.copy(tickCount = state.tickCount + 1)

        if (state.mode == Mode.Off) {
            // Off の間は輝度イベントを無視します。
            state = state.copy(message = "Brightness event ignored while mode is Off.")
            recordMutation()
            return true
        }

        return handleBrightnessEvent(nextBrightnessEvent())
    }

    /**
     * 明示的に与えられた輝度イベントを処理します。
     *
     * @param event 画面輝度イベントです。
     * @return 変更があった場合は `true` です。
     */
    fun onBrightnessEvent(event: BrightnessEvent): Boolean {
        return handleBrightnessEvent(event)
    }

    /**
     * 周囲光エンジンのティックを処理します。
     *
     * @param reading 取得した周囲光です。
     * @param sensorAvailable センサーが利用可能かどうかです。
     * @return 変更があった場合は `true` です。
     */
    fun onEngineTimerTick(reading: NativeAmbientLightReading?, sensorAvailable: Boolean): Boolean {
        if (state.mode == Mode.Off) {
            // Off の間はエンジンイベントを無視します。
            state = state.copy(message = "Engine event ignored while mode is Off.")
            recordMutation()
            return true
        }

        val primaryMutation = handleEngineReading(reading, sensorAvailable)

        // 一定間隔で輻輳させ、フラッシュ統計を観察できるようにします。
        if (state.tickCount > 0 && state.tickCount % 4 == 0) {
            handleBrightnessEvent(
                BrightnessEvent(
                    direction = BrightnessDirection.Up,
                    phase = BrightnessPhase.Down,
                    brightnessAfterSampling = 1.0,
                ),
            )
            state = state.copy(message = "Engine and brightness events were coalesced into the same flush.")
            recordMutation()
            return true
        }

        return primaryMutation
    }

    /**
     * 手動サンプルを即時反映します。
     *
     * @return 変更があった場合は `true` です。
     */
    fun sampleNow(): Boolean {
        state = state.copy(
            lux = state.lux + 55.0,
            message = "Manual sample triggered on tick ${state.tickCount}.",
            lastError = null,
        )
        recordMutation()
        return true
    }

    /**
     * 外観を強制的に切り替えます。
     *
     * @param appearance 切り替え先の外観です。
     * @return 変更があった場合は `true` です。
     */
    fun forceAppearance(appearance: Appearance): Boolean {
        return applyAppearance(appearance, "Forced ${appearance.displayName} from menu action.")
    }

    /**
     * 画面輝度イベントの内部シーケンスを生成します。
     *
     * @return 次のイベントです。
     */
    private fun nextBrightnessEvent(): BrightnessEvent {
        val direction = if (state.tickCount % 3 == 0) {
            BrightnessDirection.Down
        } else {
            BrightnessDirection.Up
        }
        val phase = if (state.tickCount % 2 == 0) {
            BrightnessPhase.Down
        } else {
            BrightnessPhase.Up
        }

        simulatedManualBrightness = when {
            direction == BrightnessDirection.Up && phase == BrightnessPhase.Down -> minOf(1.0, simulatedManualBrightness + 0.12)
            direction == BrightnessDirection.Down && phase == BrightnessPhase.Down -> maxOf(0.22, simulatedManualBrightness - 0.18)
            else -> simulatedManualBrightness
        }

        return BrightnessEvent(
            direction = direction,
            phase = phase,
            brightnessAfterSampling = simulatedManualBrightness,
        )
    }

    /**
     * 手動モード向けの輝度イベントを評価します。
     *
     * @param event 評価対象のイベントです。
     * @return 変更があった場合は `true` です。
     */
    private fun handleBrightnessEvent(event: BrightnessEvent): Boolean {
        stats = stats.copy(brightnessEventCount = stats.brightnessEventCount + 1)

        if (state.mode != Mode.Manual) {
            // Manual 以外ではイベントを観測だけして、状態遷移はしません。
            state = state.copy(message = "BrightnessKeyMonitor event arrived, but mode is ${state.mode.displayName}.", lastError = null)
            recordMutation()
            return true
        }

        val brightness = event.brightnessAfterSampling.coerceIn(0.0, 1.0)
        val targetAppearance = appearanceForManualBrightness(brightness)
        val formattedBrightness = formatBrightnessPercent(brightness)
        val isNearMax = targetAppearance == Appearance.Light

        state = state.copy(manualBrightness = brightness)

        // Key Monitoring が有効な場合は、まず離し状態や最大値到達の扱いを判定します。
        if (state.manualBrightnessKeyMonitoringEnabled &&
            event.direction == BrightnessDirection.Up &&
            event.phase == BrightnessPhase.Up
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

        if (event.direction == BrightnessDirection.Up && event.phase == BrightnessPhase.Down) {
            manualBrightnessUpIsPressed = true
        }

        if (!state.manualBrightnessKeyMonitoringEnabled) {
            // 監視が無効な場合は輝度値だけで外観を決めます。
            manualBrightnessWasNearMax = isNearMax
            state = state.copy(manualBrightnessHoldArmed = false, manualBrightnessRequiresReleaseAfterMax = false)
            return if (targetAppearance == Appearance.Light) {
                applyAppearance(Appearance.Light, "Display brightness at or near maximum.")
            } else {
                applyAppearance(Appearance.Dark, "Display brightness below maximum (${formattedBrightness}).")
            }
        }

        if (targetAppearance == Appearance.Dark) {
            // 暗い側に戻ったら長押し状態と最大到達フラグをリセットします。
            manualBrightnessWasNearMax = false
            state = state.copy(manualBrightnessHoldArmed = false, manualBrightnessRequiresReleaseAfterMax = false)
            return applyAppearance(Appearance.Dark, "Display brightness below maximum (${formattedBrightness}).")
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
            // いったん離すべき状態が続いている間は、同じ案内を維持します。
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

        // ここまで来たら、最大付近ではあるがまだ長押しを開始しない状態です。
        manualBrightnessWasNearMax = true
        state = state.copy(
            manualBrightnessHoldArmed = false,
            message = "Brightness at or near maximum (${formattedBrightness}). Hold Brightness Up to switch to Light mode.",
            lastError = null,
        )
        recordMutation()
        return true
    }

    /**
     * 周囲光の読取結果を評価します。
     *
     * @param reading 読み取り結果です。
     * @param sensorAvailable センサーの利用可否です。
     * @return 変更があった場合は `true` です。
     */
    private fun handleEngineReading(reading: NativeAmbientLightReading?, sensorAvailable: Boolean): Boolean {
        stats = stats.copy(engineEventCount = stats.engineEventCount + 1)

        if (state.mode != Mode.Auto) {
            // Auto 以外ではエンジンイベントを観測だけして終えます。
            state = state.copy(message = "AutoSwitchEngine event arrived, but mode is ${state.mode.displayName}.", lastError = null)
            recordMutation()
            return true
        }

        if (reading == null) {
            // 読み取り失敗時は候補カウントをリセットしてセンサー状態だけ更新します。
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

    /**
     * 自動切り替えのヒステリシスと連続サンプル条件を評価します。
     *
     * @param reading 読み取り結果です。
     * @return 変更があった場合は `true` です。
     */
    private fun evaluateAutoAppearance(reading: NativeAmbientLightReading): Boolean {
        if (!state.sensorAvailable) {
            // センサーが使えない状態では候補を積まずに案内だけ返します。
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
                // 連続サンプル数を満たしたら実際に切り替えます。
                return applyAppearance(Appearance.Dark, "Ambient light dropped to ${formatLux(reading.lux)}.")
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
                // 連続サンプル数を満たしたら実際に切り替えます。
                return applyAppearance(Appearance.Light, "Ambient light rose to ${formatLux(reading.lux)}.")
            }

            state = state.copy(message = candidateMessage, lastError = null)
            recordMutation()
            return true
        }

        // ヒステリシス帯の中では候補をリセットします。
        resetAutoCandidates()
        state = state.copy(message = "Inside hysteresis band at ${formatLux(reading.lux)}.", lastError = null)
        recordMutation()
        return true
    }

    /**
     * 外観切り替えを適用し、失敗時はエラーを状態へ残します。
     *
     * @param target 切り替え先です。
     * @param reason 成功時の説明文です。
     * @return 変更があった場合は `true` です。
     */
    private fun applyAppearance(target: Appearance, reason: String): Boolean {
        val currentAppearance = appearanceController.currentAppearance() ?: state.appearance
        state = state.copy(appearance = currentAppearance)

        if (state.mode == Mode.Auto) {
            // Auto モードではクールダウン中の再切り替えを避けます。
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
            // すでに目標外観なら、候補を消して完了します。
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
            // 切り替え失敗時は現状維持とエラーメッセージを返します。
            resetAutoCandidates()
            state = state.copy(
                appearance = currentAppearance,
                message = "Failed to change appearance.",
                lastError = error,
            )
            recordMutation()
            return true
        }

        if (state.mode == Mode.Auto) {
            lastAutoSwitchAtEpochSeconds = nowProvider()
        }
        // 切り替え成功後は候補をリセットして次回判定に備えます。
        resetAutoCandidates()
        state = state.copy(
            appearance = target,
            message = reason,
            lastError = null,
        )
        recordMutation()
        return true
    }

    /**
     * 自動切り替え候補のカウントを初期化します。
     */
    private fun resetAutoCandidates() {
        autoDarkCandidateCount = 0
        autoLightCandidateCount = 0
    }

    /**
     * 保存済みしきい値のスナップショットを反映します。
     *
     * @param message 反映後の状態メッセージです。
     * @return 変更があった場合は `true` です。
     */
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

    /**
     * 手動輝度キーの内部状態をリセットします。
     */
    private fun resetManualBrightnessKeyState() {
        manualBrightnessUpIsPressed = false
        manualBrightnessWasNearMax = false
        state = state.copy(
            manualBrightnessHoldArmed = false,
            manualBrightnessRequiresReleaseAfterMax = false,
        )
    }

    /**
     * 手動モードでの輝度から外観候補を決めます。
     *
     * @param brightness 輝度の正規化値です。
     * @return 対応する外観です。
     */
    private fun appearanceForManualBrightness(brightness: Double): Appearance {
        return if (brightness >= manualLightBrightnessThreshold) {
            Appearance.Light
        } else {
            Appearance.Dark
        }
    }

    /**
     * Brightness Up の長押しをアームするかどうかを判定します。
     *
     * @param direction イベント方向です。
     * @param brightnessAfterSampling 輝度値です。
     * @param phase イベント段階です。
     * @param keyMonitoringEnabled 監視が有効かどうかです。
     * @param requiresReleaseAfterMax いったん離す必要があるかどうかです。
     * @return アームすべき場合は `true` です。
     */
    private fun shouldArmManualBrightnessLongPress(
        direction: BrightnessDirection,
        brightnessAfterSampling: Double,
        phase: BrightnessPhase,
        keyMonitoringEnabled: Boolean,
        requiresReleaseAfterMax: Boolean,
    ): Boolean {
        if (!keyMonitoringEnabled) {
            return false
        }
        if (direction != BrightnessDirection.Up) {
            return false
        }
        if (phase != BrightnessPhase.Down) {
            return false
        }
        if (requiresReleaseAfterMax) {
            return false
        }
        return appearanceForManualBrightness(brightnessAfterSampling) == Appearance.Light
    }

    /**
     * 最大値到達後にいったん離す必要があるかどうかを判定します。
     *
     * @param isNearMax 現在値が最大付近かどうかです。
     * @param wasNearMax 直前も最大付近だったかどうかです。
     * @param brightnessUpIsPressed Brightness Up が押されているかどうかです。
     * @param keyMonitoringEnabled 監視が有効かどうかです。
     * @return 離し要求が必要なら `true` です。
     */
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

    /**
     * 輝度をパーセント表記へ整形します。
     *
     * @param brightness 正規化された輝度です。
     * @return 表示文字列です。
     */
    private fun formatBrightnessPercent(brightness: Double): String {
        return "${(brightness * 100).toInt()}%"
    }

    /**
     * 変更があったことをフラッシュ待ち統計へ記録します。
     */
    private fun recordMutation() {
        stats = stats.copy(pendingMutationsSinceLastFlush = stats.pendingMutationsSinceLastFlush + 1)
    }
}