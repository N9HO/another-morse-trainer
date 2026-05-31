import SwiftUI

/// Per-character performance: accuracy and median time-to-recognize (TTR),
/// weakest characters first so you can see exactly what to drill.
struct StatsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("Current stage", value: model.stageName)
                    LabeledContent("Active characters", value: "\(model.activeCharacterCount)")
                    LabeledContent("Recognize-within goal",
                                   value: String(format: "%.1f s", model.settings.ttrThreshold))
                } footer: {
                    Text("TTR = time from the end of the tone to your answer. Green means mastered (fast & accurate); the slowest/weakest characters are listed first.")
                }

                Section("Characters") {
                    ForEach(model.characterStats) { stat in
                        row(stat)
                    }
                }
            }
            .navigationTitle("Your Stats")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func row(_ stat: AppModel.CharStat) -> some View {
        HStack(spacing: 14) {
            // Character + pattern
            VStack(alignment: .leading, spacing: 2) {
                Text(String(stat.character))
                    .font(.system(.title3, design: .monospaced)).bold()
                Text(stat.pattern)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            .frame(width: 56, alignment: .leading)

            // Median TTR
            VStack(alignment: .leading, spacing: 2) {
                Text(stat.medianTTR.map { String(format: "%.2f s", $0) } ?? "—")
                    .font(.body.monospacedDigit())
                    .foregroundStyle(ttrColor(stat))
                Text("median TTR").font(.caption2).foregroundStyle(.secondary)
            }
            .frame(width: 90, alignment: .leading)

            // Accuracy
            VStack(alignment: .leading, spacing: 2) {
                Text(stat.attempts == 0 ? "—" : "\(Int(stat.accuracy * 100))%")
                    .font(.body.monospacedDigit())
                Text("\(stat.attempts) tries").font(.caption2).foregroundStyle(.secondary)
            }
            .frame(width: 70, alignment: .leading)

            Spacer()

            Image(systemName: stat.mastered ? "checkmark.seal.fill" : "circle.dashed")
                .foregroundStyle(stat.mastered ? .green : .secondary)
        }
    }

    private func ttrColor(_ stat: AppModel.CharStat) -> Color {
        guard let ttr = stat.medianTTR else { return .secondary }
        if stat.mastered { return .green }
        return ttr > model.settings.ttrThreshold ? .orange : .primary
    }
}

#Preview {
    StatsView().environmentObject(AppModel())
}
