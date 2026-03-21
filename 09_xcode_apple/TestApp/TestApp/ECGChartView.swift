import Charts
import SwiftUI

struct ECGChartView: View {
    let samplesUv: [Int]
    let sampleRate: Double

    private var dataPoints: [ECGDataPoint] {
        samplesUv.enumerated().map { index, uv in
            ECGDataPoint(
                id: index,
                time: Double(index) / sampleRate,
                uv: Double(uv)
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
