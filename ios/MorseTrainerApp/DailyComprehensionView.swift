import SwiftUI

/// **Daily Comprehension** (#353) — the day's short sentence, sent in Morse,
/// the same for everyone, answered by choosing one of four options rather than
/// by copying the sentence exactly. Hear it as often as you like; you get one
/// answer. It counts toward the daily streak and is not ranked.
struct DailyComprehensionView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject private var store = DailyComprehensionStore.shared
    @Environment(\.dismiss) private var dismiss

    /// The option highlighted but not yet locked in. A tap, a keyed letter and
    /// a hardware key all land here first, so a slip is not final.
    @State private var pending: Int?
    @State private var playingUntil: Date?

    private var game: DailyComprehensionGame { store.game }
    private var isPlaying: Bool { (playingUntil ?? .distantPast) > Date() }
    private let letters = ["A", "B", "C", "D"]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    header
                    playButton
                    questionCard
                    if game.isFinished { resultCard } else { submitButton }
                    howItWorks
                }
                .padding()
                .readableWidth()
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Background())
            .navigationTitle("Daily Comprehension")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { store.refresh() }
            .onDisappear {
                model.stopDailyComprehension()
                model.releaseAudioIfIdle()
            }
            // A Vail Adapter / BLE-MIDI key answers too: key A, B, C or D,
            // then stop keying to lock it in.
            .hardwareKeyInput(wpm: model.settings.wpm, toneHz: model.settings.toneFrequency,
                              isEnabled: !game.isFinished,
                              onText: { chunk in
                                  guard !game.isFinished else { return }
                                  if let last = chunk.uppercased().last(where: { letters.contains(String($0)) }),
                                     let i = letters.firstIndex(of: String(last)) {
                                      pending = i
                                  }
                              },
                              onPause: { if pending != nil { submit() } })
        }
    }

    // MARK: - Pieces

    private var header: some View {
        VStack(spacing: 6) {
            Text("Puzzle #\(game.puzzleNumber)")
                .font(.title2.weight(.semibold))
                .foregroundStyle(.white)
            Text("\(game.listens) \(game.listens == 1 ? "listen" : "listens")")
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var playButton: some View {
        Button { play() } label: {
            HStack(spacing: 10) {
                Image(systemName: isPlaying ? "waveform" : "play.fill")
                    .font(.title3.weight(.semibold))
                Text(game.isFinished ? "Hear it again" : "Play the sentence")
                    .font(.headline)
            }
            .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(Theme.teal, in: RoundedRectangle(cornerRadius: Theme.cornerRadius,
                                                         style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Plays at your own speed from Settings")
    }

    private func play() {
        let duration = model.playDailyComprehension()
        guard duration > 0 else { return }
        playingUntil = Date().addingTimeInterval(duration)
        DispatchQueue.main.asyncAfter(deadline: .now() + duration) {
            if !isPlaying { playingUntil = nil }
        }
    }

    private var questionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(game.item.question)
                .font(.headline)
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(Array(game.options.enumerated()), id: \.offset) { i, text in
                optionRow(index: i, text: text)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func optionRow(index: Int, text: String) -> some View {
        let finished = game.isFinished
        let isRight = index == game.correctPosition
        let isChosen = index == game.chosenPosition
        let selected = !finished && pending == index
        let fill: Color = finished
            ? (isRight ? Color(red: 0.24, green: 0.62, blue: 0.36)
                       : (isChosen ? Color(red: 0.70, green: 0.25, blue: 0.25) : Theme.navyElevated))
            : (selected ? Theme.teal.opacity(0.35) : Theme.navyElevated)
        return Button {
            guard !finished else { return }
            Haptics.selection()
            pending = index
        } label: {
            HStack(spacing: 12) {
                Text(letters[index])
                    .font(.system(.body, design: .rounded).weight(.bold))
                    .frame(width: 28)
                Text(text)
                    .font(.body)
                    .multilineTextAlignment(.leading)
                Spacer()
                if finished && isRight { Image(systemName: "checkmark") }
                else if finished && isChosen { Image(systemName: "xmark") }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(fill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(selected ? Theme.tealBright : Theme.hairline, lineWidth: selected ? 2 : 1))
        }
        .buttonStyle(.plain)
        .disabled(finished)
        .accessibilityLabel("Option \(letters[index]): \(text)")
        .accessibilityValue(finished
            ? (isRight ? "correct answer" : (isChosen ? "your answer, wrong" : ""))
            : (selected ? "selected" : ""))
    }

    private var submitButton: some View {
        Button { submit() } label: {
            Text(pending.map { "Answer \(letters[$0])" } ?? "Choose an answer")
                .font(.headline)
                .foregroundStyle(Theme.prominentLabel(on: Theme.tealBright))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(Theme.tealBright.opacity(pending == nil ? 0.35 : 1),
                            in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(pending == nil)
    }

    private func submit() {
        guard let pick = pending else { return }
        if model.answerDailyComprehension(pick) {
            pending = nil
            if pick == game.correctPosition { Haptics.success() } else { Haptics.error() }
        }
    }

    private var resultCard: some View {
        VStack(spacing: 8) {
            Text(game.isCorrect ? "Correct" : "Not quite")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
            Text(game.item.sentence)
                .font(.system(.body, design: .monospaced).weight(.semibold))
                .foregroundStyle(Theme.tealBright)
                .multilineTextAlignment(.center)
            Text("The sentence, as sent. Come back tomorrow for the next one.")
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private var howItWorks: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("How it works")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.textSecondary)
            Text("Listen to the sentence as often as you like, then answer the question about it. You don't have to copy every letter, only understand it. You get one answer a day. It keeps your daily streak going; it isn't ranked on the leaderboard.")
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
