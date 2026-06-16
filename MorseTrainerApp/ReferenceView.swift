import SwiftUI

/// A browsable, tap-to-hear reference for the run-together signals every
/// operator needs at their fingertips: prosigns, Q-codes, and CW
/// abbreviations. Unlike the quiz modes (which test recall), this is the
/// "remind me / look it up" companion — scan the table, tap any entry to hear
/// it at your configured speed and pitch, and read what it means.
///
/// All content is the same curated `MorseData` the drills draw from, so the
/// reference and the training stay in lock-step.
struct ReferenceView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    // A private player kept alive for the life of the sheet. `@State` retains
    // the instance across re-renders; it pre-warms its audio engine on init.
    @State private var player = MorsePlayer()
    @State private var playingID: String?
    @State private var category: RefCategory = .prosigns
    @State private var query = ""

    /// The three reference tables, in the order an operator usually reaches for
    /// them.
    enum RefCategory: String, CaseIterable, Identifiable {
        case prosigns, qCodes, abbreviations
        var id: String { rawValue }
        var label: String {
            switch self {
            case .prosigns:      return "Prosigns"
            case .qCodes:        return "Q-Codes"
            case .abbreviations: return "Abbr"
            }
        }
        var blurb: String {
            switch self {
            case .prosigns:      return "Procedural signals, sent run-together as one character."
            case .qCodes:        return "Q-signal shorthand for common questions and answers."
            case .abbreviations: return "Everyday CW abbreviations heard in every QSO."
            }
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                VStack(spacing: 14) {
                    Picker("Category", selection: $category) {
                        ForEach(RefCategory.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)

                    Text(category.blurb)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 16)

                    searchField
                        .padding(.horizontal, 16)

                    list
                }
            }
            .navigationTitle("Reference")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onChange(of: category) { _ in
                // Switching tables stops any tone still ringing out from the old one.
                player.stop()
                playingID = nil
            }
        }
    }

    // MARK: - Search

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Theme.textSecondary)
            TextField("Search", text: $query)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Theme.textSecondary)
                }
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Theme.navyElevated, in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.hairline, lineWidth: 1))
    }

    // MARK: - List

    @ViewBuilder
    private var list: some View {
        let rows = filtered
        if rows.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.largeTitle).foregroundStyle(Theme.teal.opacity(0.5))
                Text("No matches for “\(query)”")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(rows) { item in
                        ReferenceRow(item: item,
                                     morse: morseString(for: item),
                                     isPlaying: playingID == item.id) {
                            play(item)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
    }

    // MARK: - Data

    private var items: [MorseItem] {
        switch category {
        case .prosigns:      return MorseData.prosignItems
        case .qCodes:        return MorseData.qCodeItems
        case .abbreviations: return MorseData.abbreviationItems
        }
    }

    /// Match the typed query against either the token (e.g. "QSB", "<AR>") or
    /// its meaning, so "fade" finds QSB just as "qsb" does.
    private var filtered: [MorseItem] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return items }
        return items.filter {
            $0.display.localizedCaseInsensitiveContains(q)
                || $0.answer.localizedCaseInsensitiveContains(q)
        }
    }

    // MARK: - Playback

    /// Play at the learner's own configured speed and pitch, honouring
    /// Farnsworth spacing — exactly what the drills use, so the reference
    /// sounds like practice.
    private var timing: MorseTiming {
        model.settings.farnsworth
            ? MorseTiming(characterWpm: model.settings.wpm, effectiveWpm: model.settings.effectiveWpm)
            : MorseTiming(wpm: model.settings.wpm)
    }

    private func play(_ item: MorseItem) {
        Haptics.tap()
        playingID = item.id
        player.play(playable: item.playable,
                    frequency: model.settings.toneFrequency,
                    timing: timing) {
            // Only clear if this is still the tone we started (a quick tap on a
            // different row supersedes it).
            if playingID == item.id { playingID = nil }
        }
    }

    /// Render the dot-dash as readable glyphs. Prosigns are one run-together
    /// pattern; text tokens show each character's code separated by spaces.
    private func morseString(for item: MorseItem) -> String {
        func glyphs(_ pattern: String) -> String {
            pattern.map { $0 == "." ? "·" : "−" }.joined()
        }
        switch item.playable {
        case .pattern(let p):
            return glyphs(p)
        case .text(let t):
            return t.compactMap { ch in
                MorseCode.pattern(for: ch).map(glyphs)
            }.joined(separator: " ")
        }
    }
}

/// One reference entry: the token in bold monospace, its meaning beneath, the
/// dot-dash on the trailing side, and a speaker that animates while sounding.
private struct ReferenceRow: View {
    let item: MorseItem
    let morse: String
    let isPlaying: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.display)
                        .font(.system(.title3, design: .monospaced)).bold()
                        .foregroundStyle(.white)
                    Text(item.answer)
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 6) {
                    Text(morse)
                        .font(.system(.subheadline, design: .monospaced))
                        .foregroundStyle(Theme.teal)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Image(systemName: isPlaying ? "speaker.wave.2.fill" : "play.circle")
                        .font(.title3)
                        .foregroundStyle(isPlaying ? Theme.tealBright : Theme.teal)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .brandCard()
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(item.display), \(item.answer)")
        .accessibilityHint("Plays the Morse code")
        .accessibilityAddTraits(.isButton)
    }
}

#Preview {
    ReferenceView().environmentObject(AppModel())
        .preferredColorScheme(.dark)
}
