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
                .listRowBackground(Theme.navyElevated)

                Section("Characters") {
                    ForEach(model.characterStats) { stat in
                        row(stat)
                    }
                }
                .listRowBackground(Theme.navyElevated)

                if !model.history.sessions.isEmpty {
                    Section {
                        ForEach(model.history.sessions) { record in
                            NavigationLink {
                                SessionDetailView(record: record, idealMS: idealMS)
                            } label: {
                                sessionRow(record)
                            }
                        }
                    } header: {
                        Text("Recent sessions")
                    } footer: {
                        Text("Tap a session to see its per-character recognition-time chart.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                let bands = model.history.wpmBandSummaries()
                if !bands.isEmpty {
                    Section {
                        ForEach(bands) { band in
                            bandRow(band)
                        }
                    } header: {
                        Text("Performance by speed")
                    } footer: {
                        Text("How you do at each character-speed range, across every session. Watch where accuracy dips or reaction time climbs — that's your next speed to drill.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                if !model.confusionPairs.isEmpty {
                    Section {
                        ForEach(model.confusionPairs) { pair in
                            confusionRow(pair)
                        }
                    } header: {
                        Text("Most-confused pairs")
                    } footer: {
                        Text("Characters you've mixed up most often. Drill them head-to-head in the Confusion Drill mode — each one you get right eases the pair.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Background())
            .navigationTitle("Your Stats")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    /// The recognize-within goal as a millisecond value for the chart's ideal line.
    private var idealMS: Int { Int((model.settings.ttrThreshold * 1000).rounded()) }

    private func sessionRow(_ record: SessionRecord) -> some View {
        let title = TrainingMode(rawValue: record.mode)?.title ?? record.mode
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(record.date.formatted(date: .abbreviated, time: .shortened))
                    .font(.subheadline)
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(record.attempts == 0 ? "—" : "\(Int((record.accuracy * 100).rounded()))%")
                    .font(.subheadline.monospacedDigit())
                Text("\(record.attempts) drills")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func bandRow(_ band: WPMBandSummary) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(band.label) WPM")
                    .font(.body.monospacedDigit())
                Text("\(band.sessions) session\(band.sessions == 1 ? "" : "s")")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            .frame(width: 110, alignment: .leading)

            VStack(alignment: .leading, spacing: 2) {
                Text("\(Int((band.accuracy * 100).rounded()))%")
                    .font(.body.monospacedDigit())
                    .foregroundStyle(band.accuracy >= 0.9 ? .green : .primary)
                Text("accuracy").font(.caption2).foregroundStyle(.secondary)
            }
            .frame(width: 80, alignment: .leading)

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text(band.medianMS.map { "\($0) ms" } ?? "—")
                    .font(.body.monospacedDigit())
                Text("reaction").font(.caption2).foregroundStyle(.secondary)
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

    private func confusionRow(_ pair: AppModel.ConfusionPair) -> some View {
        HStack(spacing: 12) {
            charBadge(String(pair.a), pair.aPattern)
            Image(systemName: "arrow.left.arrow.right")
                .font(.caption)
                .foregroundStyle(.secondary)
            charBadge(String(pair.b), pair.bPattern)
            Spacer()
            Text("\(pair.count)×")
                .font(.body.monospacedDigit())
                .foregroundStyle(.orange)
        }
    }

    private func charBadge(_ ch: String, _ pattern: String) -> some View {
        VStack(spacing: 2) {
            Text(ch)
                .font(.system(.title3, design: .monospaced)).bold()
            Text(pattern)
                .font(.system(.caption2, design: .monospaced))
                .foregroundStyle(.secondary)
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
