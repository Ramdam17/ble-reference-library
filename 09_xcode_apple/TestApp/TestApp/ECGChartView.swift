import Charts
import SwiftUI

struct ECGChartView: View {
    let samplesUv: [Int]
    let sampleRate: Double

    /// Skip the first 2 seconds to avoid PMD startup drift.
    private let skipSeconds: Double = 2.0

    private var dataPoints: [ECGDataPoint] {
        let skipSamples = Int(skipSeconds * sampleRate)
        guard samplesUv.count > skipSamples else { return [] }
        let trimmed = samplesUv.dropFirst(skipSamples)
        return trimmed.enumerated().map { index, uv in
            let clamped = max(-1500, min(1500, uv))
            return ECGDataPoint(
                id: index,
                time: Double(index) / sampleRate,
                uv: Double(clamped)
            )
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("ECG — Polar H10 (\(samplesUv.count) samples, \(Int(sampleRate)) Hz)")
                .font(.headline)

            Chart(dataPoints) { point in
                LineMark(
                    x: .value("Time (s)", point.time),
                    y: .value("µV", point.uv)
                )
                .foregroundStyle(.red)
                .lineStyle(StrokeStyle(lineWidth: 0.5))
            }
            .chartXAxisLabel("Time (seconds)")
            .chartYAxisLabel("Amplitude (µV)")
            .frame(minHeight: 300)
        }
        .padding()
    }
}

private struct ECGDataPoint: Identifiable {
    let id: Int
    let time: Double
    let uv: Double
}
