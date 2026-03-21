import Charts
import SwiftUI

struct EEGChartView: View {
    let channels: [String: [Double]]
    let sampleRate: Double

    private let channelColors: [String: Color] = [
        "TP9": .blue,
        "AF7": .green,
        "AF8": .orange,
        "TP10": .purple,
    ]

    private let channelOrder = ["TP9", "AF7", "AF8", "TP10"]

    private let channelSpacing: Double = 300.0

    /// Skip the first 2 seconds to avoid Muse startup drift.
    private let skipSeconds: Double = 2.0

    private var dataPoints: [EEGDataPoint] {
        let skipSamples = Int(skipSeconds * sampleRate)
        var points: [EEGDataPoint] = []
        var globalIdx = 0
        for (chIdx, name) in channelOrder.enumerated() {
            guard let samples = channels[name], samples.count > skipSamples else { continue }
            let trimmed = Array(samples.dropFirst(skipSamples))
            let offset = Double(chIdx) * channelSpacing
            for (i, uv) in trimmed.enumerated() {
                // Clamp to ±200 µV per channel to keep scale readable
                let clamped = max(-200, min(200, uv))
                points.append(EEGDataPoint(
                    id: globalIdx,
                    time: Double(i) / sampleRate,
                    uv: clamped + offset,
                    channel: name
                ))
                globalIdx += 1
            }
        }
        return points
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            let totalSamples = channels.values.first?.count ?? 0
            Text("EEG — Muse 2 (\(totalSamples) samples/ch, \(Int(sampleRate)) Hz)")
                .font(.headline)

            Chart(dataPoints) { point in
                LineMark(
                    x: .value("Time (s)", point.time),
                    y: .value("µV", point.uv)
                )
                .foregroundStyle(by: .value("Channel", point.channel))
                .lineStyle(StrokeStyle(lineWidth: 0.5))
            }
            .chartForegroundStyleScale([
                "TP9": Color.blue,
                "AF7": Color.green,
                "AF8": Color.orange,
                "TP10": Color.purple,
            ])
            .chartXAxisLabel("Time (seconds)")
            .chartYAxisLabel("Amplitude (µV, offset per channel)")
            .chartLegend(position: .top)
            .frame(minHeight: 400)
        }
        .padding()
    }
}

private struct EEGDataPoint: Identifiable {
    let id: Int
    let time: Double
    let uv: Double
    let channel: String
}
