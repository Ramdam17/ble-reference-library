// ContentView.swift — Main tab view for BLE integration tests
//
// Three tabs: Polar H10 test, Muse 2 test, full log viewer.

import SwiftUI

struct ContentView: View {
    @State private var polarRunner = PolarTestRunner()
    @State private var museRunner = MuseTestRunner()

    var body: some View {
        TabView {
            PolarTestView(runner: polarRunner)
                .tabItem { Label("Polar H10", systemImage: "heart.fill") }

            MuseTestView(runner: museRunner)
                .tabItem { Label("Muse EEG", systemImage: "brain.head.profile") }
        }
    }
}

// MARK: - Polar Test View

struct PolarTestView: View {
    @Bindable var runner: PolarTestRunner

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header + run button
                HStack {
                    Text("Polar H10 Integration Test")
                        .font(.title2.bold())
                    Spacer()
                    Button(runner.isRunning ? "Running..." : "Run Test") {
                        Task { await runner.run() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(runner.isRunning)
                }

                // Live stats
                if !runner.ecgSamples.isEmpty {
                    HStack(spacing: 24) {
                        StatBadge(label: "ECG", value: "\(runner.ecgSamples.count)")
                        StatBadge(label: "HR readings", value: "\(runner.hrmReadings.count)")
                        if let lastHR = runner.hrmReadings.last {
                            StatBadge(label: "Last BPM", value: "\(lastHR)")
                        }
                    }
                }

                // ECG Chart (appears after streaming)
                if runner.ecgPlotReady {
                    let chartView = ECGChartView(
                        samplesUv: runner.ecgSamples,
                        sampleRate: 130.0
                    )
                    chartView

                    Button("Export PNG") {
                        exportECG(chartView: chartView)
                    }
                    .buttonStyle(.bordered)
                }

                // Log entries
                LogListView(entries: runner.logger.entries)
            }
            .padding()
        }
    }

    @MainActor
    private func exportECG(chartView: ECGChartView) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "")
        let filename = "ecg_plot_\(timestamp).png"
        if let url = ChartExporter.exportPNG(chartView, filename: filename) {
            runner.logger.log(.success, "ECG plot saved: \(url.lastPathComponent)")
        } else {
            runner.logger.log(.error, "Failed to export ECG plot")
        }
    }
}

// MARK: - Muse Test View

struct MuseTestView: View {
    @Bindable var runner: MuseTestRunner

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Muse 2 EEG Integration Test")
                        .font(.title2.bold())
                    Spacer()
                    Button(runner.isRunning ? "Running..." : "Run Test") {
                        Task { await runner.run() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(runner.isRunning)
                }

                // Live stats
                if !runner.eegChannels.values.allSatisfy({ $0.isEmpty }) {
                    HStack(spacing: 16) {
                        ForEach(["TP9", "AF7", "AF8", "TP10"], id: \.self) { ch in
                            StatBadge(label: ch, value: "\(runner.eegChannels[ch]?.count ?? 0)")
                        }
                    }
                }

                // EEG Chart
                if runner.eegPlotReady {
                    let chartView = EEGChartView(
                        channels: runner.eegChannels,
                        sampleRate: 256.0
                    )
                    chartView

                    Button("Export PNG") {
                        exportEEG(chartView: chartView)
                    }
                    .buttonStyle(.bordered)
                }

                LogListView(entries: runner.logger.entries)
            }
            .padding()
        }
    }

    @MainActor
    private func exportEEG(chartView: EEGChartView) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "")
        let filename = "eeg_plot_\(timestamp).png"
        if let url = ChartExporter.exportPNG(chartView, filename: filename, size: CGSize(width: 1400, height: 800)) {
            runner.logger.log(.success, "EEG plot saved: \(url.lastPathComponent)")
        } else {
            runner.logger.log(.error, "Failed to export EEG plot")
        }
    }
}

// MARK: - Shared Components

struct StatBadge: View {
    let label: String
    let value: String

    var body: some View {
        VStack {
            Text(value).font(.title3.monospacedDigit().bold())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct LogListView: View {
    let entries: [TestLogger.LogEntry]

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(entries) { entry in
                Text(entry.formatted)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(colorForLevel(entry.level))
            }
        }
    }

    private func colorForLevel(_ level: TestLogger.LogEntry.Level) -> Color {
        switch level {
        case .info: return .primary
        case .success: return .green
        case .error: return .red
        case .data: return .secondary
        }
    }
}
