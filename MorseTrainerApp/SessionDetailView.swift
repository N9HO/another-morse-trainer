import SwiftUI

/// A completed session, with the per-character "Instant Character Recognition"
/// chart (#19): one bar per learned character, length = median recognition time,
/// a dashed "ideal" reference line, green when accurate / red when it needs work.
struct SessionDetailView: View {
    let record: SessionRecord
    /// The learner's recognize-within goal, in milliseconds (the dashed line).
    let idealMS: Int

    private var modeTitle: String { TrainingMode(rawValue: record.mode)?.title ?? record.mode }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                headerCard

                if !record.chartRows.isEmpty {
                    sectionTitle("Recognition time")
                    Theme.Card {
                        RecognitionTimeChart(rows: record.chartRows, idealMS: idealMS)
                    }
                    sectionTitle("Per-character")
                    Theme.Card { perCharacterList }
                } else {
                    Theme.Card {
                        Text("No per-character data for this session — recognition times are tracked in single-character drills.")
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .padding()
        }
        .scrollContentBackground(.hidden)
        .background(Theme.Background())
        .navigationTitle("Session detail")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Header

    private var headerCard: some View {
        Theme.Card {
            VStack(alignment: .leading, spacing: 10) {
                Text(record.date.formatted(date: .abbreviated, time: .shortened))
                    .font(.headline)
                Text("\(record.characterWPM) WPM character / \(record.effectiveWPM) WPM effective")
                    .font(.subheadline)
                    .foregroundStyle(Theme.textSecondary)
                HStack(spacing: 18) {
                    stat("Mode", modeTitle)
                    stat("Answered", "\(record.attempts)")
                    stat("Accuracy", record.attempts == 0 ? "—" : "\(Int((record.accuracy * 100).rounded()))%")
                    stat("Median", record.medianTTR.map { String(format: "%.2fs", $0) } ?? "—")
                }
                .padding(.top, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.subheadline.monospacedDigit()).bold()
            Text(label).font(.caption2).foregroundStyle(Theme.textSecondary)
        }
    }

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.title3).bold()
            .padding(.horizontal, 4)
    }

    // MARK: - Per-character list

    private var perCharacterList: some View {
        let tested = record.characters
            .filter { $0.attempts > 0 }
            .sorted { SessionRecord.characterOrder($0.character, $1.character) }
        return VStack(spacing: 0) {
            ForEach(Array(tested.enumerated()), id: \.element.id) { idx, c in
                if idx > 0 { Divider().background(Theme.hairline) }
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(c.character)
                            .font(.system(.body, design: .monospaced)).bold()
                        Text(MorseCode.pattern(for: c.character.first ?? " ") ?? "")
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .frame(width: 44, alignment: .leading)
                    Text(c.medianMS.map { "\($0)ms" } ?? "—")
                        .font(.body.monospacedDigit())
                        .foregroundStyle(c.accuracy >= 0.9 ? .green : .red)
                        .frame(width: 80, alignment: .leading)
                    Spacer()
                    Text("\(Int((c.accuracy * 100).rounded()))%")
                        .font(.subheadline.monospacedDigit())
                    Text("\(c.attempts) tries")
                        .font(.caption2)
                        .foregroundStyle(Theme.textSecondary)
                }
                .padding(.vertical, 8)
            }
        }
    }
}

/// Horizontal bar chart of per-character median recognition time.
private struct RecognitionTimeChart: View {
    let rows: [SessionRecord.ChartRow]
    let idealMS: Int

    private let gutter: CGFloat = 22       // character-label column
    private let rowHeight: CGFloat = 24
    private let topInset: CGFloat = 18     // room for the "ideal" label
    private let axisHeight: CGFloat = 20
    private let goodAccuracy = 0.9

    private var maxMS: Int {
        let observed = rows.compactMap { $0.result?.medianMS }.max() ?? 0
        return SessionRecord.axisCeilingMS(max(observed, idealMS))
    }
    private var gridValues: [Int] { Array(stride(from: 250, through: maxMS, by: 250)) }

    var body: some View {
        let rowsHeight = CGFloat(rows.count) * rowHeight
        GeometryReader { geo in
            let plotX = gutter
            let plotW = max(1, geo.size.width - gutter)
            ZStack(alignment: .topLeading) {
                // Gridlines
                ForEach(gridValues, id: \.self) { v in
                    Rectangle()
                        .fill(Theme.hairline)
                        .frame(width: 1, height: rowsHeight)
                        .offset(x: plotX + xFrac(v) * plotW, y: topInset)
                }
                // Dashed "ideal" reference line + label
                let idealX = plotX + xFrac(idealMS) * plotW
                DashedVLine()
                    .stroke(style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .foregroundStyle(Color.white.opacity(0.45))
                    .frame(width: 1, height: rowsHeight)
                    .offset(x: idealX, y: topInset)
                Text("\(idealMS)ms ideal")
                    .font(.caption2)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize()
                    .offset(x: idealX - 28, y: 0)

                // Bars
                ForEach(Array(rows.enumerated()), id: \.element.id) { idx, row in
                    rowView(row, y: topInset + CGFloat(idx) * rowHeight, plotX: plotX, plotW: plotW)
                }

                // Axis labels
                ForEach([0] + gridValues, id: \.self) { v in
                    Text("\(v)")
                        .font(.caption2)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize()
                        .offset(x: plotX + xFrac(v) * plotW - 8, y: topInset + rowsHeight + 2)
                }
            }
        }
        .frame(height: topInset + rowsHeight + axisHeight)
    }

    private func xFrac(_ ms: Int) -> CGFloat { CGFloat(ms) / CGFloat(maxMS) }

    private func rowView(_ row: SessionRecord.ChartRow, y: CGFloat,
                         plotX: CGFloat, plotW: CGFloat) -> some View {
        let ms = row.result?.medianMS
        let attempts = row.result?.attempts ?? 0
        let accuracy = row.result?.accuracy ?? 0
        let barW = ms.map { max(2, xFrac($0) * plotW) } ?? 0
        let color: Color = accuracy >= goodAccuracy ? .green : .red
        return ZStack(alignment: .topLeading) {
            Text(row.character)
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(Theme.textSecondary)
                .frame(width: gutter, height: rowHeight, alignment: .leading)
            if let ms, attempts > 0 {
                Capsule()
                    .fill(color)
                    .frame(width: barW, height: 6)
                    .offset(x: plotX, y: rowHeight / 2 - 3)
                Text("\(ms)ms")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize()
                    .offset(x: plotX + barW + 5, y: rowHeight / 2 - 8)
            }
        }
        .frame(height: rowHeight, alignment: .topLeading)
        .offset(y: y)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText(row, ms: ms, attempts: attempts, accuracy: accuracy))
    }

    private func accessibilityText(_ row: SessionRecord.ChartRow, ms: Int?,
                                   attempts: Int, accuracy: Double) -> String {
        guard attempts > 0, let ms else { return "\(row.character): not drilled this session" }
        return "\(row.character): \(ms) milliseconds, \(Int((accuracy * 100).rounded())) percent accurate"
    }
}

/// A simple top-to-bottom line, for the dashed reference marker.
private struct DashedVLine: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.midX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        return p
    }
}

#Preview {
    let chars: [SessionRecord.CharResult] = [
        .init(character: "B", attempts: 5, correct: 5, medianTTR: 0.377),
        .init(character: "O", attempts: 5, correct: 2, medianTTR: 0.219),
        .init(character: "P", attempts: 4, correct: 1, medianTTR: 0.291),
        .init(character: "R", attempts: 6, correct: 6, medianTTR: 0.767),
        .init(character: "U", attempts: 5, correct: 5, medianTTR: 1.105),
        .init(character: "W", attempts: 5, correct: 5, medianTTR: 1.105),
    ]
    let record = SessionRecord(
        id: UUID(), date: Date(), mode: "characters",
        characterWPM: 25, effectiveWPM: 7, attempts: 30, correct: 24,
        fastestTTR: 0.219, medianTTR: 0.45, durationSeconds: 300,
        characters: chars,
        activeCharacters: ["B", "C", "E", "O", "P", "Q", "R", "U", "W", "Z"])
    return NavigationStack { SessionDetailView(record: record, idealMS: 600) }
        .preferredColorScheme(.dark)
}
