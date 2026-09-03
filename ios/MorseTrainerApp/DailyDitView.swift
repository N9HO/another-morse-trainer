import SwiftUI
import UIKit

/// **Daily Dit** — the day's five-letter word, sent in Morse, the same for
/// everyone (issue #155).
///
/// The screen is the game: a play button, the grid of what you've guessed, and
/// a box to type the next one. Everything else is subordinate to those three.
/// Speed is chosen before the first guess and then belongs to the day — the
/// ladder walks it down as guesses are spent, and the speed you were still at
/// when you got it is what the share text brags about.
struct DailyDitView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var entry = ""
    @State private var message: String?
    @State private var playingUntil: Date?
    @State private var showingReference = false
    @FocusState private var entryFocused: Bool

    private var game: DailyDitGame { model.dailyDit }
    private var isPlaying: Bool { (playingUntil ?? .distantPast) > Date() }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    header
                    playButton
                    if game.isFinished { resultCard } else { entryRow }
                    grid
                    if !game.isFinished { letterTracker }
                    if game.guessesUsed == 0 { setupCard } else { speedNote }
                    if !game.hideReference { referenceCard }
                    howItWorks
                }
                .padding()
                .readableWidth()
            }
            .scrollContentBackground(.hidden)
            .background(Theme.Background())
            .navigationTitle("Daily Dit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if game.isFinished {
                        ShareLink(item: game.shareText) {
                            Image(systemName: "square.and.arrow.up")
                        }
                        .accessibilityLabel("Share your result")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { model.refreshDailyDit() }
            .onDisappear { model.stopDailyDit() }
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 6) {
            Text("Puzzle #\(game.puzzleNumber)")
                .font(.title2.weight(.semibold))
                .foregroundStyle(.white)
            Text(statusLine)
                .font(.subheadline)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .accessibilityElement(children: .combine)
    }

    private var statusLine: String {
        switch game.outcome {
        case .solved:
            let at = game.solvedWpm.map { " at \(DailyDit.format(wpm: $0)) WPM" } ?? ""
            return "Copied in \(game.guessesUsed)\(at)"
        case .lost:
            return "Out of guesses — it was \(game.answer)"
        case .playing:
            return "\(DailyDit.format(wpm: game.currentWpm)) WPM · \(game.guessesLeft) guesses left"
        }
    }

    // MARK: - Play

    private var playButton: some View {
        Button {
            play()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: isPlaying ? "waveform" : "play.fill")
                    .font(.title3.weight(.semibold))
                Text(game.isFinished ? "Hear it again" : "Play the word")
                    .font(.headline)
            }
            .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(Theme.teal, in: RoundedRectangle(cornerRadius: Theme.cornerRadius,
                                                         style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Play the word at \(DailyDit.format(wpm: game.currentWpm)) words per minute")
        .accessibilityHint("Replays are free — only guesses count")
    }

    private func play() {
        let duration = model.playDailyDit()
        guard duration > 0 else { return }
        playingUntil = Date().addingTimeInterval(duration)
        // Nothing depends on this firing — it only un-animates the button — so
        // a missed tick is cosmetic, not a stuck state.
        DispatchQueue.main.asyncAfter(deadline: .now() + duration) {
            if !isPlaying { playingUntil = nil }
        }
    }

    // MARK: - Guess entry

    private var entryRow: some View {
        VStack(spacing: 8) {
            HStack(spacing: 10) {
                TextField("Five letters", text: $entry)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .textCase(.uppercase)
                    .font(.system(.title3, design: .monospaced).weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(Theme.navyElevated,
                                in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(Theme.hairline, lineWidth: 1))
                    .focused($entryFocused)
                    .submitLabel(.send)
                    .onSubmit(submit)
                    .onChange(of: entry) { _ in
                        // Typing is how you recover from a rejection; clearing
                        // the message on the next keystroke says so.
                        if message != nil { message = nil }
                    }

                Button("Guess", action: submit)
                    .font(.headline)
                    .foregroundStyle(Theme.prominentLabel(on: Theme.tealBright))
                    .padding(.horizontal, 18)
                    .padding(.vertical, 13)
                    .background(Theme.tealBright.opacity(canGuess ? 1 : 0.35),
                                in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .disabled(!canGuess)
            }
            if let message {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .transition(.opacity)
            }
        }
    }

    private var canGuess: Bool {
        DailyDit.normalize(entry).count == DailyDit.wordLength
    }

    private func submit() {
        switch model.submitDailyDit(entry) {
        case .scored(let round):
            entry = ""
            message = nil
            if round.solved {
                Haptics.success()
                entryFocused = false
            } else {
                Haptics.selection()
            }
        case .rejected(let reason):
            message = reason.message
            Haptics.error()
        }
    }

    // MARK: - The grid

    private var grid: some View {
        VStack(spacing: 6) {
            ForEach(Array(game.rounds.enumerated()), id: \.offset) { _, round in
                HStack(spacing: 6) {
                    ForEach(Array(zip(round.guess, round.tiles).enumerated()), id: \.offset) { _, pair in
                        tile(letter: pair.0, tile: pair.1)
                    }
                    speedTag(round.wpm)
                }
            }
            if !game.isFinished {
                // The live row: what's in the box, so the typing lands in the
                // grid rather than somewhere off to the side of it.
                let typed = Array(DailyDit.normalize(entry).prefix(DailyDit.wordLength))
                HStack(spacing: 6) {
                    ForEach(0..<DailyDit.wordLength, id: \.self) { i in
                        tile(letter: i < typed.count ? typed[i] : nil, tile: nil)
                    }
                    speedTag(game.currentWpm).opacity(0.55)
                }
            }
        }
    }

    private func tile(letter: Character?, tile: DailyDit.Tile?) -> some View {
        Text(letter.map(String.init) ?? " ")
            .font(.system(size: 24, weight: .bold, design: .rounded))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .aspectRatio(1, contentMode: .fit)
            .background(fill(for: tile), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(tile == nil ? Theme.hairline : .clear, lineWidth: 1.5))
            .accessibilityLabel(accessibilityLabel(letter: letter, tile: tile))
    }

    /// Colour carries the result, but never alone: the share grid is emoji and
    /// every tile also says its state out loud to VoiceOver.
    private func fill(for tile: DailyDit.Tile?) -> Color {
        switch tile {
        case .correct: return Color(red: 0.24, green: 0.62, blue: 0.36)
        case .present: return Color(red: 0.79, green: 0.63, blue: 0.20)
        case .absent:  return Theme.navyRaised
        case nil:      return Theme.navyElevated
        }
    }

    private func accessibilityLabel(letter: Character?, tile: DailyDit.Tile?) -> String {
        guard let letter else { return "Empty" }
        switch tile {
        case .correct: return "\(letter), right place"
        case .present: return "\(letter), in the word, wrong place"
        case .absent:  return "\(letter), not in the word"
        case nil:      return "\(letter), not guessed yet"
        }
    }

    private func speedTag(_ wpm: Double) -> some View {
        Text("\(DailyDit.format(wpm: wpm))")
            .font(.caption2.weight(.semibold).monospacedDigit())
            .foregroundStyle(Theme.textSecondary)
            .frame(width: 26, alignment: .trailing)
            .accessibilityLabel("sent at \(DailyDit.format(wpm: wpm)) words per minute")
    }

    // MARK: - Letters ruled out

    private var letterTracker: some View {
        let dead = game.eliminatedLetters
        return VStack(alignment: .leading, spacing: 6) {
            Text("Ruled out")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.textSecondary)
            Text(dead.isEmpty ? "—" : String("ABCDEFGHIJKLMNOPQRSTUVWXYZ".filter { dead.contains($0) }))
                .font(.system(.body, design: .monospaced))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    // MARK: - Result

    private var resultCard: some View {
        VStack(spacing: 12) {
            Text(game.outcome == .solved ? "Solid copy" : "Tomorrow, then")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
            Text(game.answer)
                .font(.system(size: 32, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.tealBright)
                .accessibilityLabel("The word was \(game.answer)")

            Text(game.shareText)
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Theme.navyRaised,
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .accessibilityLabel("Your shareable result")

            HStack(spacing: 12) {
                Button {
                    UIPasteboard.general.string = game.shareText
                    Haptics.success()
                    message = "Copied."
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                        .font(.headline)
                        .foregroundStyle(Theme.prominentLabel(on: Theme.teal))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Theme.teal,
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)

                ShareLink(item: game.shareText) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Theme.navyRaised,
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            if let message {
                Text(message).font(.footnote).foregroundStyle(Theme.tealBright)
            }
            Text("Next puzzle tomorrow.")
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(16)
        .background(Theme.navyElevated,
                    in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
    }

    // MARK: - Before the first guess

    private var setupCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Starting speed")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Text("Drops \(Int(DailyDit.speedStepWpm)) WPM every \(DailyDit.guessesPerSpeedStep) guesses, down to \(Int(DailyDit.minimumWpm)). Locked once you guess.")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
            }
            speedPicker
            Toggle(isOn: hideReferenceBinding) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Hide the chart").font(.subheadline.weight(.semibold))
                    Text("No dit-dah reference — your share text says so.")
                        .font(.caption)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            .tint(Theme.teal)
            .foregroundStyle(.white)
        }
        .padding(16)
        .background(Theme.navyElevated,
                    in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
    }

    private var speedPicker: some View {
        // A segmented Picker would crush seven speeds into unreadable slivers
        // on a phone; a wrapping row of capsules stays tappable.
        FlowRow(spacing: 8) {
            ForEach(DailyDit.startingSpeeds, id: \.self) { speed in
                let selected = game.startingWpm == speed
                Button {
                    Haptics.selection()
                    model.configureDailyDit(startingWpm: speed,
                                            hideReference: game.hideReference)
                } label: {
                    Text("\(DailyDit.format(wpm: speed))")
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(selected ? Theme.prominentLabel(on: Theme.teal) : .white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(selected ? Theme.teal : Theme.navyRaised, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(DailyDit.format(wpm: speed)) words per minute")
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
    }

    private var hideReferenceBinding: Binding<Bool> {
        Binding(
            get: { game.hideReference },
            set: { model.configureDailyDit(startingWpm: game.startingWpm, hideReference: $0) }
        )
    }

    private var speedNote: some View {
        Text("Started at \(DailyDit.format(wpm: game.startingWpm)) WPM"
             + (game.hideReference ? " · no reference" : ""))
            .font(.caption)
            .foregroundStyle(Theme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Reference

    private var referenceCard: some View {
        DisclosureGroup(isExpanded: $showingReference) {
            let columns = [GridItem(.adaptive(minimum: 74), spacing: 8)]
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), id: \.self) { letter in
                    HStack(spacing: 6) {
                        Text(String(letter))
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white)
                        Text(MorseCode.pattern(for: letter) ?? "")
                            .font(.system(.caption, design: .monospaced))
                            .foregroundStyle(Theme.teal)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(.top, 10)
        } label: {
            Text("Dit-dah chart")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
        }
        .tint(Theme.teal)
        .padding(14)
        .background(Theme.navyElevated,
                    in: RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous))
    }

    private var howItWorks: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("How it works")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
            Text("""
                 One five-letter word a day, the same for everyone. Replay it as \
                 often as you like — only guesses count, and every \
                 \(DailyDit.guessesPerSpeedStep) of them slow the code by \
                 \(Int(DailyDit.speedStepWpm)) WPM. Green is the right letter in \
                 the right place, amber is the right letter somewhere else.
                 """)
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A row that wraps onto the next line when it runs out of width.
///
/// `LazyVGrid` with adaptive columns would space the speed capsules evenly
/// rather than packing them, which reads as a table of gaps at seven items.
private struct FlowRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += lineHeight + spacing
                lineHeight = 0
            }
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: proposal.width ?? x, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize,
                       subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += lineHeight + spacing
                lineHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}
