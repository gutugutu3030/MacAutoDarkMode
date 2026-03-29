import SwiftUI

struct SettingsView: View {
    @ObservedObject var settings: SettingsStore
    @ObservedObject var monitor: AmbientLightMonitor
    @ObservedObject var engine: AutoSwitchEngine
    @ObservedObject var launchAtLoginManager: LaunchAtLoginManager

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Form {
                statusSection
                automationSection
                startupSection
                actionSection
            }

            if let error = engine.lastError ?? monitor.lastError {
                Text(error)
                    .foregroundStyle(.red)
                    .font(.caption)
            }

            Text(engine.lastActionDescription)
                .foregroundStyle(.secondary)
                .font(.caption)
        }
        .padding(20)
        .frame(width: 440, height: 420)
    }

    private var startupSection: some View {
        Section("Startup") {
            Toggle(
                "Launch automatically at login",
                isOn: Binding(
                    get: { launchAtLoginManager.isEnabled },
                    set: { launchAtLoginManager.setEnabled($0) }
                )
            )
            .disabled(!launchAtLoginManager.canManageLaunchAgent)

            Text(launchAtLoginManager.supportMessage)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var statusSection: some View {
        Section("Status") {
            LabeledContent("Ambient light") {
                Text(monitor.lastReadingLux.formattedLux)
                    .monospacedDigit()
            }
            LabeledContent("Sensor path") {
                Text(monitor.source.rawValue)
            }
            LabeledContent("Appearance") {
                Text(engine.lastKnownAppearance?.displayName ?? "Unknown")
            }
            LabeledContent("Last sample") {
                Text(monitor.lastUpdatedAt?.formatted(date: .abbreviated, time: .standard) ?? "—")
                    .monospacedDigit()
            }
        }
    }

    private var automationSection: some View {
        Section("Automation") {
            Toggle("Enable automatic switching", isOn: $settings.automationEnabled)

            VStack(alignment: .leading) {
                Text("Dark threshold: \(settings.effectiveDarkThresholdLux.formattedLux)")
                Slider(
                    value: Binding(
                        get: { settings.effectiveDarkThresholdLux },
                        set: { settings.updateDarkThresholdLux($0) }
                    ),
                    in: 0...2000,
                    step: 5
                )
                Button("Use Current Value") {
                    settings.useCurrentLuxAsDarkThreshold(monitor.lastReadingLux)
                }
                .disabled(monitor.lastReadingLux < 0)
            }

            VStack(alignment: .leading) {
                Text("Light threshold: \(settings.effectiveLightThresholdLux.formattedLux)")
                Slider(
                    value: Binding(
                        get: { settings.effectiveLightThresholdLux },
                        set: { settings.updateLightThresholdLux($0) }
                    ),
                    in: 0...120000,
                    step: 25
                )
                Button("Use Current Value") {
                    settings.useCurrentLuxAsLightThreshold(monitor.lastReadingLux)
                }
                .disabled(monitor.lastReadingLux < 0)
            }

            Stepper(
                "Required consecutive samples: \(settings.effectiveRequiredConsecutiveSamples)",
                value: Binding(
                    get: { settings.effectiveRequiredConsecutiveSamples },
                    set: { settings.updateRequiredConsecutiveSamples($0) }
                ),
                in: 1...10
            )

            VStack(alignment: .leading) {
                Text("Cooldown: \(Int(settings.effectiveCooldownSeconds))s")
                Slider(
                    value: Binding(
                        get: { settings.effectiveCooldownSeconds },
                        set: { settings.updateCooldownSeconds($0) }
                    ),
                    in: 5...300,
                    step: 5
                )
            }
        }
    }

    private var actionSection: some View {
        Section("Actions") {
            HStack {
                Button("Sample Now") {
                    monitor.sample()
                }
                Button("Switch Light") {
                    engine.forceSetAppearance(.light)
                }
                Button("Switch Dark") {
                    engine.forceSetAppearance(.dark)
                }
            }
        }
    }
}