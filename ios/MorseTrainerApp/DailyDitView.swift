import SwiftUI
import UIKit

/// **Daily Dit** — the day's five-letter word, sent in Morse, the same for
/// everyone (issue #155).
///
/// The screen is the game: a play button, the grid of what you've guessed, and
/// a box to type the next one. Everything else is subordinate to those three.
/// Speed is chosen before the first guess and then belongs to the day — the
/// ladder walks it down as listens and wrong guesses are spent (#168), and the
/// slowest speed you heard it at is what the share text brags about.
struct DailyDitView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var entry = ""
    @State private var message: String?
    @State private var playingUntil: Date?
    @State private var showingReference = false
    @State private var shareURL: URL?
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
                        shareLink {
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
            // Re-render on every finish transition: solving mid-screen, and the
            // midnight rollover (refreshDailyDit) both change what the card says.
            .task(id: game.isFinished) { renderShareImage() }
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

    /// "1 guess · 3 listens" — the counts the share text carries, in the same words.
    private var counts: String {
        DailyDit.count(game.guessesUsed, "guess", "guesses")
            + " · " + DailyDit.count(game.listens, "listen", "listens")
    }

    private var statusLine: String {
        switch game.outcome {
        case .solved:
            let at = game.solvedWpm.map { "at \(DailyDit.format(wpm: $0)) WPM · " } ?? ""
            return "Copied \(at)\(counts)"
        case .playing:
            // The speed readout is the live ladder: listens step it as well as
            // wrong guesses, so it can change without a guess being made.
            return "\(DailyDit.format(wpm: game.currentWpm)) WPM · \(counts)"
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
        .accessibilityHint(game.isFinished
                           ? "Replays after the win are free"
                           : "Every \(DailyDit.listensPerSpeedStep) listens slow the code by \(Int(DailyDit.speedStepWpm)) words per minute")
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
            Text("Solid copy")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
            Text(game.answer)
                .font(.system(size: 32, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.tealBright)
                .accessibilityLabel("The word was \(game.answer)")

            // The three numbers the day is scored on (#168): the slowest speed
            // the word was heard at, and what it cost in guesses and listens.
            HStack(spacing: 0) {
                resultStat(value: game.solvedWpm.map { DailyDit.format(wpm: $0) } ?? "—",
                           label: "WPM, lowest heard")
                resultStat(value: "\(game.guessesUsed)",
                           label: game.guessesUsed == 1 ? "guess" : "guesses")
                resultStat(value: "\(game.listens)",
                           label: game.listens == 1 ? "listen" : "listens")
            }

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

                shareLink {
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

    private func resultStat(value: String, label: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.title3.weight(.bold).monospacedDigit())
                .foregroundStyle(.white)
            Text(label)
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Before the first guess

    private var setupCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Starting speed")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Text("Drops \(Int(DailyDit.speedStepWpm)) WPM for every \(DailyDit.listensPerSpeedStep) listens and for every \(DailyDit.guessesPerSpeedStep) wrong guesses, down to \(Int(DailyDit.minimumWpm)). Locked once you guess. Sent straight at that speed, with no Farnsworth spacing, so it is quicker than the same number in a drill.")
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

    // MARK: - Share image

    /// The share button leads with the themed card image; `shareText` travels
    /// with it as the caption (Android attaches the same `EXTRA_TEXT`). Until
    /// the render lands — or if it fails — fall back to sharing the text alone,
    /// so the button never shares nothing.
    @ViewBuilder
    private func shareLink<L: View>(@ViewBuilder label: () -> L) -> some View {
        if let url = shareURL {
            ShareLink(item: url,
                      message: Text(game.shareText),
                      preview: SharePreview("Daily Dit #\(game.puzzleNumber)",
                                            image: Image(systemName: "square.grid.3x3.fill"))) {
                label()
            }
        } else {
            ShareLink(item: game.shareText) { label() }
        }
    }

    @MainActor private func renderShareImage() {
        guard game.isFinished else {
            shareURL = nil
            return
        }
        let renderer = ImageRenderer(content: DailyDitShareCard(game: game))
        renderer.scale = 3
        guard let ui = renderer.uiImage, let data = ui.pngData() else { return }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("AnotherMorseTrainer-DailyDit.png")
        try? data.write(to: url)
        shareURL = url
    }

    private var howItWorks: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("How it works")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
            Text("""
                 One five-letter word a day, the same for everyone. Every \
                 \(DailyDit.listensPerSpeedStep) listens and every \
                 \(DailyDit.guessesPerSpeedStep) wrong guesses slow the code by \
                 \(Int(DailyDit.speedStepWpm)) WPM — no limit on either — and \
                 your share text reports the slowest speed you heard it at. \
                 Green is the right letter in the right place, amber is the \
                 right letter somewhere else.
                 """)
                .font(.caption)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A self-contained, fixed-width card rendered to an image for sharing: the
/// day's result in the brand navy/teal, with the guess grid as coloured tiles
/// and each row's sending speed beside it. Mirrors Android's
/// `DailyDitShareCard`. It does not read the environment so `ImageRenderer`
/// can rasterize it off-screen, and it carries no letters — the image spoils
/// nothing the emoji grid didn't.
private struct DailyDitShareCard: View {
    let game: DailyDitGame

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 8) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(Theme.teal)
                Text("Another Morse Trainer")
                    .font(.headline).foregroundStyle(.white)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Daily Dit #\(game.puzzleNumber)")
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text(scoreLine)
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
            }

            grid

            Text(DailyDit.shareLink)
                .font(.caption).foregroundStyle(Theme.teal.opacity(0.8))
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

    /// The headline's score, minus the "Daily Dit #N" the title already says.
    private var scoreLine: String {
        var line = ""
        if let wpm = game.solvedWpm { line += "\(DailyDit.format(wpm: wpm)) WPM · " }
        line += DailyDit.count(game.guessesUsed, "guess", "guesses")
            + " · " + DailyDit.count(game.listens, "listen", "listens")
        if game.hideReference { line += " · no reference" }
        return line
    }

    private var grid: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(game.rounds.enumerated()), id: \.offset) { _, round in
                HStack(spacing: 6) {
                    ForEach(Array(round.tiles.enumerated()), id: \.offset) { _, tile in
                        RoundedRectangle(cornerRadius: 7, style: .continuous)
                            .fill(fill(for: tile))
                            .frame(width: 34, height: 34)
                            .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous)
                                .strokeBorder(tile == .absent ? Theme.hairline : .clear, lineWidth: 1))
                    }
                    Text(DailyDit.format(wpm: round.wpm))
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)
                        .padding(.leading, 4)
                }
            }
        }
    }

    /// The same fills the on-screen grid uses (`DailyDitView.fill(for:)`).
    private func fill(for tile: DailyDit.Tile) -> Color {
        switch tile {
        case .correct: return Color(red: 0.24, green: 0.62, blue: 0.36)
        case .present: return Color(red: 0.79, green: 0.63, blue: 0.20)
        case .absent:  return Theme.navyRaised
        }
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
