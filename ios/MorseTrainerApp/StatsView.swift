import SwiftUI

/// Per-character performance: accuracy and median time-to-recognize (TTR),
/// weakest characters first so you can see exactly what to drill.
struct StatsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// Weeks of the activity grid (#181): half a year on a phone, a full
    /// year where the width allows — Android switches at the same 600 dp.
    private var activityWeeks: Int { sizeClass == .regular ? 52 : 26 }

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

                if model.history.totalSessions > 0 {
                    Section {
                        ActivityGridView(ledger: model.activity, weeks: activityWeeks)
                            .padding(.vertical, 4)
                        HStack {
                            Label("\(model.currentStreak)-day streak", systemImage: "flame.fill")
                                .foregroundStyle(model.currentStreak > 0 ? Color.orange : Color.secondary)
                            Spacer()
                            Text("Longest \(model.longestStreak)")
                                .foregroundStyle(.secondary)
                        }
                        .font(.subheadline.monospacedDigit())
                    } header: {
                        Text("Activity")
                    } footer: {
                        Text("One tile per day, shaded by practice time: under 5 minutes, 5–15, 15–30, and 30 or more. Rows run Monday to Sunday.")
                    }
                    .listRowBackground(Theme.navyElevated)
                }

                Section("Characters") {
                    ForEach(model.characterStats) { stat in
                        row(stat)
                    }
                }
                .listRowBackground(Theme.navyElevated)

                if model.history.sessions.isEmpty {
                    Section("Recent sessions") {
                        Text("No sessions yet. Finish a practice round and it will show up here.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .listRowBackground(Theme.navyElevated)
                } else {
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
            .readableWidth()
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
                // A session that graded nothing — Listen & Learn, Short
                // Stories — has no accuracy, and must not read as 0% (#183).
                Text(record.isScored ? "\(Int((record.accuracy * 100).rounded()))%" : "N/A")
                    .font(.subheadline.monospacedDigit())
                Text(SessionRecord.passiveModes.contains(record.mode)
                     ? "\(record.attempts) heard" : "\(record.attempts) drills")
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

// MARK: - Activity grid (issue #181)

/// The GitHub-style activity grid: one tile per day, seven rows Monday to
/// Sunday, one column per week ending with the current week, shaded by that
/// day's practice time from the `ActivityLedger`. Drawn in a `Canvas` whose
/// aspect ratio follows from the week count, so the tiles fill the row's
/// width without a `GeometryReader` needing a height first; VoiceOver gets
/// one element per past day through `accessibilityChildren`. The Android
/// `ActivityGrid` in StatsScreen.kt lays out the same tiles.
struct ActivityGridView: View {
    let ledger: ActivityLedger
    /// Columns: the last `weeks` weeks, the current week last.
    let weeks: Int
    var today: Date = Date()

    // Layout in tile units: every measure scales with the tile, so the whole
    // grid has one aspect ratio for a given week count.
    private static let gapUnits: CGFloat = 0.25
    private static let gutterUnits: CGFloat = 2.6      // weekday labels
    private static let headerUnits: CGFloat = 1.6      // month labels

    private var widthUnits: CGFloat {
        Self.gutterUnits + CGFloat(weeks) + CGFloat(weeks - 1) * Self.gapUnits
    }
    private var heightUnits: CGFloat { Self.headerUnits + 7 + 6 * Self.gapUnits }

    private var calendar: Calendar {
        var cal = Calendar.current
        cal.firstWeekday = 2   // Monday, as the Brag Sheet's week strip
        return cal
    }

    /// One day of the grid: its date, column, row and shade.
    private struct Day: Identifiable {
        let date: Date
        let week: Int
        let weekday: Int        // 0 = Monday … 6 = Sunday
        let level: Int
        let isToday: Bool
        let isFuture: Bool
        var id: Int { week * 7 + weekday }
    }

    /// Every tile, column by column from the Monday `weeks - 1` weeks back.
    private var days: [Day] {
        let cal = calendar
        let todayStart = cal.startOfDay(for: today)
        let weekday = cal.component(.weekday, from: todayStart)   // 1=Sun…7=Sat
        let daysFromMonday = (weekday + 5) % 7
        let monday = cal.date(byAdding: .day, value: -daysFromMonday, to: todayStart) ?? todayStart
        let start = cal.date(byAdding: .day, value: -7 * (weeks - 1), to: monday) ?? monday
        var out: [Day] = []
        out.reserveCapacity(weeks * 7)
        for week in 0..<weeks {
            for weekday in 0..<7 {
                guard let date = cal.date(byAdding: .day, value: week * 7 + weekday, to: start) else { continue }
                out.append(Day(date: date, week: week, weekday: weekday,
                               level: ledger.level(on: date, calendar: cal),
                               isToday: date == todayStart,
                               isFuture: date > todayStart))
            }
        }
        return out
    }

    /// Shade for a level, 0 (nothing) to 4 (30 minutes or more): the teal
    /// accent stepped up in opacity, as the Android grid tints `Brand.teal`.
    static func color(forLevel level: Int) -> Color {
        switch level {
        case 0:  return Color.white.opacity(0.06)
        case 1:  return Theme.teal.opacity(0.3)
        case 2:  return Theme.teal.opacity(0.55)
        case 3:  return Theme.teal.opacity(0.8)
        default: return Theme.tealBright
        }
    }

    /// "Tue 3 Sep: 12 minutes" — the VoiceOver label for a day.
    static func accessibilityLabel(for date: Date, seconds: Int, recorded: Bool) -> String {
        let day = date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        if !recorded { return "\(day): no practice" }
        if seconds < 60 { return "\(day): under a minute" }
        return "\(day): \(seconds / 60) minutes"
    }

    var body: some View {
        let days = self.days
        let cal = calendar
        VStack(alignment: .leading, spacing: 8) {
            Canvas { context, size in
                draw(days, in: context, size: size)
            }
            .aspectRatio(widthUnits / heightUnits, contentMode: .fit)
            .accessibilityChildren {
                ForEach(days.filter { !$0.isFuture }) { day in
                    Text(Self.accessibilityLabel(
                        for: day.date,
                        seconds: ledger.seconds(on: day.date, calendar: cal),
                        recorded: ledger.isRecorded(day: ActivityLedger.dayKey(for: day.date, calendar: cal))))
                }
            }
            legend
        }
    }

    private func draw(_ days: [Day], in context: GraphicsContext, size: CGSize) {
        let unit = size.width / widthUnits
        let gap = unit * Self.gapUnits
        let originX = Self.gutterUnits * unit
        let originY = Self.headerUnits * unit
        let cal = calendar

        // Weekday labels down the gutter, on alternate rows.
        for (row, label) in [(0, "M"), (2, "W"), (4, "F")] {
            let y = originY + CGFloat(row) * (unit + gap) + unit / 2
            context.draw(Text(label).font(.caption2).foregroundColor(Theme.textSecondary),
                         at: CGPoint(x: 0, y: y), anchor: .leading)
        }

        // A month label over the first column that holds the 1st of a month,
        // at most every third column so they never overlap.
        var lastLabelWeek = -3
        for week in 0..<weeks {
            guard week - lastLabelWeek >= 3,
                  let first = days.first(where: { $0.week == week && cal.component(.day, from: $0.date) == 1 })
            else { continue }
            lastLabelWeek = week
            let x = originX + CGFloat(week) * (unit + gap)
            context.draw(Text(first.date.formatted(.dateTime.month(.abbreviated)))
                            .font(.caption2).foregroundColor(Theme.textSecondary),
                         at: CGPoint(x: x, y: 0), anchor: .topLeading)
        }

        // The tiles. Days still to come are left blank.
        for day in days where !day.isFuture {
            let rect = CGRect(x: originX + CGFloat(day.week) * (unit + gap),
                              y: originY + CGFloat(day.weekday) * (unit + gap),
                              width: unit, height: unit)
            let path = Path(roundedRect: rect, cornerRadius: unit * 0.2)
            context.fill(path, with: .color(Self.color(forLevel: day.level)))
            if day.isToday {
                context.stroke(path, with: .color(Theme.tealBright), lineWidth: 1)
            }
        }
    }

    /// "Less ▢▢▢▢▢ More" — the five shades in order.
    private var legend: some View {
        HStack(spacing: 4) {
            Spacer()
            Text("Less").font(.caption2).foregroundStyle(Theme.textSecondary)
            ForEach(0...ActivityLedger.maxLevel, id: \.self) { level in
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(Self.color(forLevel: level))
                    .frame(width: 10, height: 10)
            }
            Text("More").font(.caption2).foregroundStyle(Theme.textSecondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Less to more practice time")
    }
}

#Preview {
    StatsView().environmentObject(AppModel())
}
