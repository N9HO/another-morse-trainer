import SwiftUI

/// A browsable, tap-to-hear reference for the signals every operator wants at
/// their fingertips: prosigns, Q-codes, CW abbreviations, contest cut numbers,
/// and the full Morse chart. Unlike the quiz modes (which test recall), this is
/// the "remind me / look it up" companion — scan a table, tap any row to hear it
/// at your configured speed and pitch, or open it for the full detail.
///
/// Content comes from the same curated `MorseData` the drills draw from, so the
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
    @State private var showingAudio = false

    /// The reference tables, in the order an operator usually reaches for them.
    enum RefCategory: String, CaseIterable, Identifiable {
        case prosigns, qCodes, abbreviations, cutNumbers, chart
        var id: String { rawValue }
        var label: String {
            switch self {
            case .prosigns:      return "Prosigns"
            case .qCodes:        return "Q-Codes"
            case .abbreviations: return "Abbr"
            case .cutNumbers:    return "Cut #"
            case .chart:         return "Chart"
            }
        }
        var blurb: String {
            switch self {
            case .prosigns:      return "Procedural signals, sent run-together as one character."
            case .qCodes:        return "Q-signal shorthand for common questions and answers."
            case .abbreviations: return "Everyday CW abbreviations heard in every QSO."
            case .cutNumbers:    return "Contest shorthand: a digit sent as a single letter to save time."
            case .chart:         return "The full alphabet, numbers, and punctuation with their rhythm."
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

                    if showingAudio {
                        ReferenceAudioControls()
                            .padding(.horizontal, 16)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }

                    searchField
                        .padding(.horizontal, 16)

                    list
                }
            }
            .navigationTitle("Reference")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) { showingAudio.toggle() }
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                    }
                    .accessibilityLabel(showingAudio ? "Hide playback settings" : "Show playback settings")
                }
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
                                     morse: referenceMorseString(for: item),
                                     detail: detail(for: item),
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
        case .cutNumbers:    return MorseData.cutNumberItems
        case .chart:         return MorseData.chartItems
        }
    }

    /// Extra encyclopedic detail for the row, when we have it (prosigns today).
    private func detail(for item: MorseItem) -> MorseData.ReferenceDetail? {
        MorseData.prosignDetail[item.display]
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

    private func play(_ item: MorseItem) {
        Haptics.tap()
        playingID = item.id
        player.play(playable: item.playable,
                    frequency: model.settings.toneFrequency,
                    timing: model.settings.referenceTiming) {
            // Only clear if this is still the tone we started (a quick tap on a
            // different row supersedes it).
            if playingID == item.id { playingID = nil }
        }
    }
}

/// Render an item's dot-dash as readable glyphs. Prosigns are one run-together
/// pattern; text tokens show each character's code separated by spaces.
func referenceMorseString(for item: MorseItem) -> String {
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

/// The spoken rhythm for an item, when it's a single run-together shape (a
/// prosign or a single character). Multi-character tokens get nil — their
/// per-character glyphs already tell the story.
func referenceRhythm(for item: MorseItem) -> String? {
    switch item.playable {
    case .pattern(let p):
        return MorseData.spokenRhythm(for: p)
    case .text(let t):
        guard t.count == 1, let ch = t.first,
              let pattern = MorseCode.pattern(for: ch) else { return nil }
        return MorseData.spokenRhythm(for: pattern)
    }
}

extension AppSettings {
    /// Playback timing for the Reference — the learner's own configured speed,
    /// honouring Farnsworth spacing, so the reference sounds like practice.
    var referenceTiming: MorseTiming {
        farnsworth
            ? MorseTiming(characterWpm: wpm, effectiveWpm: effectiveWpm)
            : MorseTiming(wpm: wpm)
    }
}

// MARK: - Row

/// One reference entry: the token in bold monospace and its meaning, the
/// dot-dash on the trailing side, a speaker that plays it, and a tap target that
/// opens the full detail.
private struct ReferenceRow: View {
    let item: MorseItem
    let morse: String
    let detail: MorseData.ReferenceDetail?
    let isPlaying: Bool
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            NavigationLink {
                ReferenceDetailView(item: item, morse: morse, detail: detail)
            } label: {
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
                    Text(morse)
                        .font(.system(.subheadline, design: .monospaced))
                        .foregroundStyle(Theme.teal)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
            }
            .buttonStyle(.plain)

            Button(action: action) {
                Image(systemName: isPlaying ? "speaker.wave.2.fill" : "play.circle")
                    .font(.title3)
                    .foregroundStyle(isPlaying ? Theme.tealBright : Theme.teal)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Play \(item.display) in Morse code")
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }
}

// MARK: - Detail

/// The full per-signal screen: the token large, its dot-dash and spoken rhythm,
/// the meaning (and, for prosigns, the ITU name, "also written" variants, and a
/// description), a big play button, and inline playback controls.
private struct ReferenceDetailView: View {
    @EnvironmentObject var model: AppModel
    let item: MorseItem
    let morse: String
    let detail: MorseData.ReferenceDetail?

    @State private var player = MorsePlayer()
    @State private var isPlaying = false

    var body: some View {
        ZStack {
            Theme.Background()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    if let detail, let itu = detail.ituName {
                        field("ITU / operational name", itu)
                    }
                    if let detail, !detail.alsoWritten.isEmpty {
                        field("Also written", detail.alsoWritten.joined(separator: "   "))
                    }
                    field("Meaning", item.answer)
                    if let detail, !detail.description.isEmpty {
                        field("About", detail.description)
                    }
                    Divider().overlay(Theme.hairline)
                    Text("Playback")
                        .font(.headline).foregroundStyle(.white)
                    ReferenceAudioControls()
                }
                .padding(20)
            }
        }
        .navigationTitle(item.display)
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { player.stop() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(item.display)
                .font(.system(size: 44, weight: .bold, design: .monospaced))
                .foregroundStyle(.white)
            Text(morse)
                .font(.system(.title2, design: .monospaced))
                .foregroundStyle(Theme.teal)
            if let rhythm = referenceRhythm(for: item) {
                Text(rhythm)
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(Theme.textSecondary)
            }
            Button(action: play) {
                Label(isPlaying ? "Playing…" : "Play",
                      systemImage: isPlaying ? "speaker.wave.2.fill" : "play.circle.fill")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.vertical, 10)
                    .padding(.horizontal, 18)
                    .background(Theme.teal, in: Capsule())
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
        }
    }

    private func field(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title.uppercased())
                .font(.caption2).bold()
                .foregroundStyle(Theme.textSecondary)
            Text(value)
                .font(.body)
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func play() {
        Haptics.tap()
        isPlaying = true
        player.play(playable: item.playable,
                    frequency: model.settings.toneFrequency,
                    timing: model.settings.referenceTiming) {
            isPlaying = false
        }
    }
}

// MARK: - Inline audio controls

/// Speed / pitch / Farnsworth sliders bound to the shared app settings, so
/// adjusting them here changes playback everywhere (the same settings the drills
/// use). Mirrors the controls cwsignals.com puts right in its reference.
struct ReferenceAudioControls: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(spacing: 12) {
            slider("Side tone", value: $model.settings.toneFrequency,
                   range: 300...1000, step: 10, format: "\(Int(model.settings.toneFrequency)) Hz")
            slider("Speed", value: $model.settings.wpm,
                   range: 15...60, step: 1, format: "\(Int(model.settings.wpm)) WPM")
            Toggle("Farnsworth spacing", isOn: $model.settings.farnsworth)
                .font(.subheadline)
                .foregroundStyle(.white)
                .tint(Theme.teal)
            if model.settings.farnsworth {
                slider("Effective speed", value: $model.settings.effectiveWpm,
                       range: 8...max(9, model.settings.wpm), step: 1,
                       format: "\(Int(model.settings.effectiveWpm)) WPM")
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .brandCard()
    }

    private func slider(_ title: String, value: Binding<Double>,
                        range: ClosedRange<Double>, step: Double,
                        format: String) -> some View {
        VStack(spacing: 4) {
            HStack {
                Text(title)
                    .font(.subheadline).foregroundStyle(.white)
                Spacer()
                Text(format)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            Slider(value: value, in: range, step: step)
                .tint(Theme.teal)
        }
    }
}

#Preview {
    ReferenceView().environmentObject(AppModel())
        .preferredColorScheme(.dark)
}
