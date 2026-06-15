import SwiftUI

/// The "Brag Sheet": your progress at a glance — daily streak, lifetime totals,
/// personal bests, and recent sessions, in the app's navy/teal brand look. It's
/// the celebratory companion to the deeper, per-character `StatsView`. A Share
/// button renders a compact card to an image you can post.
struct BragSheetView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var shareURL: URL?

    private var stats: AppModel.BragStats { model.bragStats }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    streakCard
                    section("Lifetime") { lifetimeGrid }
                    section("Personal bests") { personalBests }
                    if !model.history.sessions.isEmpty {
                        section("Recent sessions") { recentSessions }
                    }
                }
                .padding()
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Background())
            .navigationTitle("Brag Sheet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if let url = shareURL {
                        ShareLink(item: url, preview: SharePreview("My Morse progress", image: Image(systemName: "antenna.radiowaves.left.and.right"))) {
                            Image(systemName: "square.and.arrow.up")
                        }
                        .accessibilityLabel("Share your brag sheet")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task { renderShareImage() }
        }
    }

    // MARK: - Streak hero

    private var streakCard: some View {
        VStack(spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Image(systemName: "flame.fill")
                        .font(.title2)
                        .foregroundStyle(stats.currentStreak > 0 ? .orange : Theme.textSecondary)
                    Text("\(stats.currentStreak)")
                        .font(.system(size: 40, weight: .semibold, design: .rounded))
                        .foregroundStyle(.white)
                    Text("day streak")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("Longest")
                        .font(.caption2)
                        .foregroundStyle(Theme.textSecondary)
                    Text("\(stats.longestStreak)")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                }
            }
            HStack(spacing: 0) {
                ForEach(model.streakWeek) { day in
                    VStack(spacing: 5) {
                        dayDot(day)
                        Text(day.label)
                            .font(.caption2)
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Theme.navyElevated,
                    in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .strokeBorder(Theme.teal.opacity(0.28), lineWidth: 1)
        )
    }

    @ViewBuilder
    private func dayDot(_ day: AppModel.StreakDay) -> some View {
        ZStack {
            if day.practiced {
                Circle().fill(Theme.teal)
                Image(systemName: "checkmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Theme.navy)
            } else {
                Circle().strokeBorder(
                    style: StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
                    .foregroundStyle(day.isFuture ? Theme.hairline : Theme.textSecondary.opacity(0.5))
            }
        }
        .frame(width: 30, height: 30)
        .overlay(
            Circle()
                .strokeBorder(Theme.tealBright, lineWidth: day.isToday ? 2 : 0)
                .padding(-2)
        )
    }

    // MARK: - Lifetime grid

    private var lifetimeGrid: some View {
        let cols = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]
        return LazyVGrid(columns: cols, spacing: 10) {
            metricTile("\(stats.totalSessions)", "Sessions")
            metricTile(stats.totalAnswered.formatted(), "Answered")
            metricTile(Self.duration(stats.practiceSeconds), "Practice time")
            metricTile(percent(stats.accuracy), "Accuracy", color: Theme.teal)
        }
    }

    private func metricTile(_ value: String, _ label: String, color: Color = .white) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value)
                .font(.system(.title2, design: .rounded).weight(.semibold))
                .foregroundStyle(color)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            Text(label)
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.navyElevated,
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: - Personal bests

    private var personalBests: some View {
        VStack(spacing: 0) {
            bestRow("bolt.fill", "Fastest copy", Theme.teal,
                    stats.fastestCopy.map { String(format: "%.2f s", $0) } ?? "—")
            divider
            bestRow("target", "Best session accuracy", Theme.textSecondary,
                    stats.bestSessionAccuracy.map(percent) ?? "—",
                    valueColor: stats.bestSessionAccuracy == nil ? .white : Theme.teal)
            divider
            bestRow("chart.bar.fill", "Biggest session", Theme.textSecondary,
                    stats.biggestSession.map { "\($0) answered" } ?? "—")
            divider
            bestRow("rosette", "Characters mastered", .orange,
                    "\(stats.charactersMastered) / \(stats.charactersTotal)",
                    valueColor: .orange)
            ProgressView(value: Double(stats.charactersMastered),
                         total: Double(max(stats.charactersTotal, 1)))
                .tint(.orange)
                .padding(.top, 10)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(Theme.navyElevated,
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func bestRow(_ icon: String, _ label: String, _ iconColor: Color,
                         _ value: String, valueColor: Color = .white) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.subheadline)
                .foregroundStyle(iconColor)
                .frame(width: 22)
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.white)
            Spacer()
            Text(value)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(valueColor)
        }
        .padding(.vertical, 11)
    }

    // MARK: - Recent sessions

    private var recentSessions: some View {
        VStack(spacing: 0) {
            let recent = Array(model.history.sessions.prefix(5))
            ForEach(Array(recent.enumerated()), id: \.element.id) { index, record in
                sessionRow(record)
                if index < recent.count - 1 { divider }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .background(Theme.navyElevated,
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func sessionRow(_ record: SessionRecord) -> some View {
        let title = TrainingMode(rawValue: record.mode)?.title ?? record.mode
        let acc = record.attempts == 0 ? .secondary : accColor(record.accuracy)
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline).foregroundStyle(.white)
                Text(Self.relativeDay(record.date))
                    .font(.caption2).foregroundStyle(Theme.textSecondary)
            }
            Spacer()
            Text(record.attempts == 0
                 ? "—"
                 : "\(record.attempts) · \(percent(record.accuracy))")
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(acc)
        }
        .padding(.vertical, 11)
    }

    // MARK: - Building blocks

    private var divider: some View {
        Rectangle().fill(Theme.hairline).frame(height: 0.5)
    }

    @ViewBuilder
    private func section<Content: View>(_ title: String,
                                        @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.caption.weight(.semibold))
                .tracking(0.6)
                .foregroundStyle(Theme.textSecondary)
                .padding(.leading, 4)
            content()
        }
    }

    private func percent(_ v: Double) -> String { "\(Int((v * 100).rounded()))%" }

    private func accColor(_ v: Double) -> Color {
        v >= 0.9 ? Color(red: 0.36, green: 0.79, blue: 0.65) : .orange
    }

    /// "4h 12m" / "12m" / "45s" — compact practice-time formatting.
    static func duration(_ seconds: TimeInterval) -> String {
        let total = Int(seconds.rounded())
        let h = total / 3600, m = (total % 3600) / 60
        if h > 0 { return "\(h)h \(m)m" }
        if m > 0 { return "\(m)m" }
        return "\(total)s"
    }

    static func relativeDay(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) { return "Today" }
        if cal.isDateInYesterday(date) { return "Yesterday" }
        let days = cal.dateComponents([.day], from: cal.startOfDay(for: date),
                                      to: cal.startOfDay(for: Date())).day ?? 0
        if days < 7 { return date.formatted(.dateTime.weekday(.wide)) }
        return date.formatted(.dateTime.month(.abbreviated).day())
    }

    // MARK: - Share image

    @MainActor private func renderShareImage() {
        let card = BragShareCard(stats: stats,
                                 week: model.streakWeek,
                                 stage: model.stageName)
        let renderer = ImageRenderer(content: card)
        renderer.scale = 3
        guard let ui = renderer.uiImage, let data = ui.pngData() else { return }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("AnotherMorseTrainer-BragSheet.png")
        try? data.write(to: url)
        shareURL = url
    }
}

/// A self-contained, fixed-width card rendered to an image for sharing. It does
/// not read the environment so `ImageRenderer` can rasterize it off-screen.
private struct BragShareCard: View {
    let stats: AppModel.BragStats
    let week: [AppModel.StreakDay]
    let stage: String

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 8) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(Theme.teal)
                Text("Another Morse Trainer")
                    .font(.headline).foregroundStyle(.white)
            }

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "flame.fill").foregroundStyle(.orange)
                Text("\(stats.currentStreak)")
                    .font(.system(size: 46, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text("day streak")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
                Spacer()
                Text(stage)
                    .font(.caption).foregroundStyle(Theme.teal)
            }

            HStack(spacing: 22) {
                shareStat("\(stats.totalAnswered)", "answered")
                shareStat("\(Int((stats.accuracy * 100).rounded()))%", "accuracy")
                shareStat("\(stats.charactersMastered)/\(stats.charactersTotal)", "mastered")
            }

            if let fastest = stats.fastestCopy {
                Text("Fastest copy \(String(format: "%.2f", fastest)) s · \(stats.totalSessions) sessions")
                    .font(.caption).foregroundStyle(Theme.textSecondary)
            }

            Text("anothermorsetrainer.app")
                .font(.caption2).foregroundStyle(Theme.teal.opacity(0.8))
        }
        .padding(22)
        .frame(width: 360, alignment: .leading)
        .background(
            ZStack {
                LinearGradient(colors: [Color(red: 0.020, green: 0.055, blue: 0.110), Theme.navy],
                               startPoint: .top, endPoint: .bottom)
                RadialGradient(colors: [Theme.teal.opacity(0.18), .clear],
                               center: .topTrailing, startRadius: 0, endRadius: 320)
            }
        )
    }

    private func shareStat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.system(.title3, design: .rounded).weight(.semibold))
                .foregroundStyle(.white)
            Text(label).font(.caption2).foregroundStyle(Theme.textSecondary)
        }
    }
}

#Preview {
    BragSheetView().environmentObject(AppModel())
}
