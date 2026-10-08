import SwiftUI

/// **Past puzzles** — the last `DailyDit.historyDays` Daily Dits (#333).
///
/// Each day shows its puzzle number and date and how you left it: copied
/// (with the result as it was played that day), started and not solved, or
/// missed. Any past day opens as practice — a missed one to catch up on, a
/// copied one to replay — and practice is kept apart from the real thing:
/// it doesn't count toward any streak, isn't synced or reported, and never
/// changes the result shown here. Today's row goes back to today's game.
///
/// Pushed inside `DailyDitView`'s navigation stack. Mirrors Android's
/// `DailyDitHistoryScreen`.
struct DailyDitHistoryView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var practicePuzzle: Int?
    @State private var showingPractice = false

    var body: some View {
        let today = model.dailyDit.puzzleNumber
        ScrollView {
            LazyVStack(spacing: 10) {
                Text("Every Daily Dit from the last \(DailyDit.historyDays) days. Open a day you missed, or replay one you copied — as practice: it doesn't count toward your streak or change that day's result.")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.bottom, 4)
                ForEach(DailyDit.historyPuzzles(today: today), id: \.self) { number in
                    row(number, isToday: number == today)
                }
            }
            .padding()
            .readableWidth()
        }
        .scrollContentBackground(.hidden)
        .background(Theme.Background())
        .navigationTitle("Past puzzles")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $showingPractice) {
            if let practicePuzzle {
                DailyDitView(practicePuzzle: practicePuzzle)
            }
        }
        .onAppear { model.refreshDailyDit() }
    }

    // MARK: - A day

    private func row(_ number: Int, isToday: Bool) -> some View {
        let game = isToday ? model.dailyDit : model.dailyDitHistory[number]
        let solved = game?.isFinished == true
        let started = game?.hasStarted == true
        return Button {
            Haptics.selection()
            if isToday {
                dismiss()   // back to today's game
            } else {
                model.startDailyDitPractice(puzzle: number)
                practicePuzzle = number
                showingPractice = true
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: solved ? "checkmark.circle.fill"
                                  : started ? "circle.lefthalf.filled" : "circle.dashed")
                    .font(.title3)
                    .foregroundStyle(solved ? Theme.tealBright : Theme.textSecondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text("#\(number) · \(Self.dateLabel(forPuzzle: number))")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                    Text(status(game, isToday: isToday))
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
                Spacer(minLength: 8)
                Text(isToday ? "Today" : solved ? "Replay" : "Play")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(isToday ? Theme.textSecondary : Theme.teal)
            }
            .padding(14)
            .background(Theme.navyElevated,
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint(isToday ? "Back to today's puzzle" : "Opens this day as practice")
    }

    /// The day as it was left — the stored result, never a practice one.
    private func status(_ game: DailyDitGame?, isToday: Bool) -> String {
        guard let game, game.hasStarted else { return isToday ? "Not played yet" : "Missed" }
        let counts = DailyDit.count(game.guessesUsed, "guess", "guesses")
            + " · " + DailyDit.count(game.listens, "listen", "listens")
        if game.isFinished {
            let at = game.solvedWpm.map { "at \(DailyDit.format(wpm: $0)) WPM · " } ?? ""
            return "Copied \(at)\(counts)"
        }
        return "Not solved · \(counts)"
    }

    /// "Wed, Oct 7" — the day everyone played a puzzle, in the reader's locale.
    static func dateLabel(forPuzzle number: Int) -> String {
        let c = DailyDit.civilDate(forPuzzle: number)
        var parts = DateComponents()
        parts.year = c.year
        parts.month = c.month
        parts.day = c.day
        parts.hour = 12   // midday, so no time zone puts it on a neighbouring day
        guard let date = Calendar(identifier: .gregorian).date(from: parts) else { return "" }
        return date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }
}
