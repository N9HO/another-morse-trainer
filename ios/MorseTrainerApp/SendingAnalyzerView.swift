import SwiftUI

/// The Sending Analyzer sheet (#241, #234, #235). Setup (what to send, on what
/// key, through which input), sending (the key or the microphone meter, with a
/// live copy), and results (the copy lined up against the text, spacing
/// histograms, element timing, feedback, and the running record).
struct SendingAnalyzerView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var analyzer: SendingAnalyzerModel

    init(toneHz: Double, keyerWpm: Double, studied: [Character]) {
        _analyzer = StateObject(wrappedValue: SendingAnalyzerModel(toneHz: toneHz, keyerWpm: keyerWpm,
                                                                   studied: studied))
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                ScrollView {
                    VStack(spacing: 16) {
                        switch analyzer.phase {
                        case .setup:
                            SetupSection(analyzer: analyzer)
                        case .sending:
                            SendingSection(analyzer: analyzer, keyer: analyzer.keyer, mic: analyzer.mic)
                        case .results:
                            if let a = analyzer.analysis {
                                ResultsSection(analyzer: analyzer, analysis: a)
                            }
                        }
                        RecordSection(analyzer: analyzer)
                    }
                    .padding(16)
                    .readableWidth()
                }
            }
            .navigationTitle("Sending Analyzer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { analyzer.appear() }
        .onDisappear { analyzer.disappear() }
        .presentationDetents([.large])
    }
}

// MARK: - Shared bits

private struct SectionCard<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased())
                .font(.system(size: 11, weight: .bold)).tracking(1.4)
                .foregroundStyle(Theme.textSecondary)
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }
}

private struct TargetText: View {
    @EnvironmentObject var model: AppModel
    let text: String
    var body: some View {
        Text(text.isEmpty ? "—" : text)
            .font(Theme.copyFont(size: 22, weight: .semibold, monospaced: true,
                                 slashedZero: model.settings.slashedZero))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .textSelection(.enabled)
    }
}

// MARK: - Setup

private struct SetupSection: View {
    @ObservedObject var analyzer: SendingAnalyzerModel

    var body: some View {
        VStack(spacing: 16) {
            SectionCard(title: "Send this") {
                Picker("Text", selection: $analyzer.source) {
                    ForEach(SendingAnalyzerModel.Source.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                if analyzer.source == .custom {
                    TextField("Type what you want to send", text: $analyzer.customText, axis: .vertical)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .lineLimit(1...4)
                        .textFieldStyle(.roundedBorder)
                }
                TargetText(text: analyzer.target)
                if analyzer.source != .custom {
                    Button {
                        analyzer.newTarget()
                    } label: {
                        Label("New text", systemImage: "arrow.clockwise").font(.subheadline)
                    }
                    .buttonStyle(.bordered)
                    .tint(Theme.teal)
                }
            }

            SectionCard(title: "Your key") {
                Picker("Key", selection: $analyzer.keyType) {
                    ForEach(SendingKeyType.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.menu)
                .tint(Theme.teal)
                Text(keyTypeNote)
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            SectionCard(title: "Input") {
                Picker("Input", selection: $analyzer.input) {
                    ForEach(SendingAnalyzerModel.Input.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                if analyzer.input == .microphone {
                    MicPanel(analyzer: analyzer, mic: analyzer.mic)
                } else {
                    Text("The on-screen key, or a Vail adapter or Bluetooth MIDI key. Set the adapter's keyer mode in Settings to match your key; in a paddle mode the adapter keys at your Settings speed.")
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            SectionCard(title: "Judged against") {
                Stepper(value: $analyzer.targetWpm, in: 5...50, step: 1) {
                    Text("\(Int(analyzer.targetWpm)) WPM").font(.body.monospacedDigit())
                }
                Toggle("Farnsworth spacing", isOn: $analyzer.farnsworth).tint(Theme.teal)
                if analyzer.farnsworth {
                    Stepper(value: $analyzer.effectiveWpm, in: 5...max(5, analyzer.targetWpm), step: 1) {
                        Text("Effective \(Int(min(analyzer.effectiveWpm, analyzer.targetWpm))) WPM")
                            .font(.body.monospacedDigit())
                    }
                }
            }

            Button {
                Haptics.tap()
                analyzer.startSending()
            } label: {
                Label("Start sending", systemImage: "play.fill")
                    .font(.headline)
                    .foregroundStyle(Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            .disabled(!analyzer.canStart)
        }
    }

    private var keyTypeNote: String {
        switch analyzer.keyType {
        case .straight: return "Every element and gap is yours, so all of it is judged."
        case .bug:      return "A bug times its own dits; your dahs and spacing are judged."
        case .cootie:   return "Hand-timed on both sides, judged like a straight key."
        case .keyer:    return "Your keyer times the dits and dahs; your spacing is judged."
        }
    }
}

// MARK: - Microphone panel

private struct MicPanel: View {
    @ObservedObject var analyzer: SendingAnalyzerModel
    @ObservedObject var mic: SendingMicInput

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Circle()
                    .fill(mic.toneOn ? Theme.tealBright : Theme.navyRaised)
                    .frame(width: 14, height: 14)
                    .overlay(Circle().strokeBorder(Theme.hairline, lineWidth: 1))
                    .accessibilityLabel(mic.toneOn ? "Tone heard" : "No tone")
                LevelMeter(level: mic.level, floor: mic.noiseFloor)
                    .frame(height: 10)
            }
            HStack {
                Text(pitchText)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Button("Find pitch again") { analyzer.refindPitch() }
                    .font(.footnote)
                    .disabled(!mic.isListening)
            }
            Picker("Sensitivity", selection: $analyzer.sensitivity) {
                ForEach(SendingAnalyzerModel.Sensitivity.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            if mic.micDenied {
                Label("Microphone access is off. Allow it in Settings → Privacy → Microphone.",
                      systemImage: "mic.slash")
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text("Key your oscillator, keyer or rig near the phone. The light should follow your key; if it flickers in the gaps, lower the sensitivity.")
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var pitchText: String {
        guard mic.isListening else { return "Microphone off" }
        guard let hz = mic.pitchHz else { return "Listening for a tone…" }
        return "\(Int(hz.rounded())) Hz" + (mic.pitchLocked ? " · locked" : "")
    }
}

/// Tone level on a log scale (−60…0 dB of full scale), with the floor marked.
private struct LevelMeter: View {
    let level: Double
    let floor: Double

    private func fraction(_ v: Double) -> CGFloat {
        let db = 20 * log10(max(v, 1e-6))
        return CGFloat(min(1, max(0, (db + 60) / 60)))
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.navyRaised)
                Capsule().fill(Theme.teal)
                    .frame(width: geo.size.width * fraction(level))
                Rectangle().fill(Color.orange)
                    .frame(width: 2)
                    .offset(x: geo.size.width * fraction(floor))
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Sending

private struct SendingSection: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var analyzer: SendingAnalyzerModel
    @ObservedObject var keyer: SendingKeyer
    @ObservedObject var mic: SendingMicInput
    @State private var keyPressed = false
    @State private var showingBluetoothMIDI = false

    var body: some View {
        VStack(spacing: 16) {
            SectionCard(title: "Send this") {
                TargetText(text: analyzer.target)
            }
            SectionCard(title: "Copied so far") {
                Text(analyzer.liveText.isEmpty ? "—" : analyzer.liveText)
                    .font(Theme.copyFont(size: 22, weight: .medium, monospaced: true,
                                         slashedZero: model.settings.slashedZero))
                    .foregroundStyle(analyzer.liveText.isEmpty ? Theme.textSecondary : Theme.tealBright)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if analyzer.input == .key {
                keyButton
                midiStatus
            } else {
                SectionCard(title: "Microphone") {
                    MicPanel(analyzer: analyzer, mic: mic)
                }
            }
            HStack(spacing: 12) {
                Button {
                    analyzer.cancelSending()
                } label: {
                    Label("Cancel", systemImage: "xmark")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .tint(Theme.textSecondary)
                Button {
                    Haptics.tap()
                    analyzer.finish()
                } label: {
                    Label("Finish", systemImage: "checkmark")
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
            }
            Text("Send the whole text, then pause — the attempt finishes by itself. Finish ends it early.")
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
        }
    }

    private var keyButton: some View {
        ZStack {
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .fill(keyPressed || keyer.isKeying ? Theme.teal : Theme.navyRaised)
            RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                .strokeBorder(keyPressed ? Theme.tealBright : Theme.hairline, lineWidth: keyPressed ? 2 : 1)
            VStack(spacing: 4) {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(keyPressed || keyer.isKeying ? Theme.navy : Theme.teal)
                Text("HOLD TO KEY")
                    .font(.system(size: 12, weight: .bold)).tracking(1.5)
                    .foregroundStyle(keyPressed || keyer.isKeying ? Theme.navy : Theme.textSecondary)
            }
        }
        .frame(height: 140)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !keyPressed { keyPressed = true; keyer.touchKey(isDown: true) }
                }
                .onEnded { _ in
                    if keyPressed { keyPressed = false; keyer.touchKey(isDown: false) }
                }
        )
        .accessibilityLabel("Morse key")
        .accessibilityHint("Press and hold to send each dit and dah")
    }

    private var midiStatus: some View {
        VStack(spacing: 6) {
            if !keyer.midiDeviceNames.isEmpty {
                Label(keyer.midiDeviceNames.joined(separator: ", "), systemImage: "pianokeys")
                    .font(.caption)
                    .foregroundStyle(Theme.teal)
                    .lineLimit(1)
            } else {
                Label(keyer.midiUnavailable
                        ? "MIDI unavailable — on-screen key only"
                        : "No hardware key — on-screen key only",
                      systemImage: keyer.midiUnavailable ? "exclamationmark.triangle" : "pianokeys")
                    .font(.caption)
                    .foregroundStyle(keyer.midiUnavailable ? .orange : Theme.textSecondary)
            }
            Button {
                showingBluetoothMIDI = true
            } label: {
                Label(keyer.midiDeviceNames.isEmpty ? "Connect a Bluetooth key…" : "Bluetooth keys…",
                      systemImage: "dot.radiowaves.right")
                    .font(.caption)
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
        .bluetoothMIDISheet(isPresented: $showingBluetoothMIDI) {
            keyer.rescanMIDI()
        }
    }
}

// MARK: - Results

private struct ResultsSection: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var analyzer: SendingAnalyzerModel
    let analysis: SendingAnalysis

    var body: some View {
        VStack(spacing: 16) {
            if analysis.isEmpty {
                SectionCard(title: "Nothing heard") {
                    Text(analyzer.input == .microphone
                         ? "No keying came through. Check the light follows your key, then try again."
                         : "No keying came through. Press the key, or check your adapter is connected.")
                        .font(.callout)
                        .foregroundStyle(Theme.textSecondary)
                }
            } else {
                summary
                alignmentCard
                feedbackCard
                spacingCard
                elementsCard
            }
            HStack(spacing: 12) {
                Button {
                    analyzer.newTarget()
                } label: {
                    Label("New text", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .tint(Theme.teal)
                Button {
                    Haptics.tap()
                    analyzer.sendAgain()
                } label: {
                    Label("Send again", systemImage: "play.fill")
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
            }
        }
    }

    private var summary: some View {
        HStack(spacing: 0) {
            stat("\(Int((analysis.accuracy * 100).rounded()))%", "correct")
            stat("\(Int(analysis.characterWpm.rounded()))", "char WPM")
            stat("\(Int(analysis.effectiveWpm.rounded()))", "overall WPM")
        }
        .padding(.vertical, 12)
        .brandCard()
        .accessibilityElement(children: .combine)
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.weight(.bold).monospacedDigit()).foregroundStyle(.white)
            Text(label).font(.caption).foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
    }

    private var alignmentCard: some View {
        SectionCard(title: "What you sent") {
            alignedText
                .font(Theme.copyFont(size: 22, weight: .semibold, monospaced: true,
                                     slashedZero: model.settings.slashedZero))
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Text("Red: sent as something else · orange: left out · grey: extra · ⎵ word break")
                .font(.caption2)
                .foregroundStyle(Theme.textSecondary)
            let errors = analysis.alignment.filter { $0.kind != .match }
            if !errors.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(errors.enumerated()), id: \.offset) { _, op in
                        Text(describe(op))
                            .font(.footnote.monospaced())
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
            }
        }
    }

    private var alignedText: Text {
        analysis.alignment.reduce(Text("")) { acc, op in
            switch op.kind {
            case .match:
                return acc + Text(String(op.expected ?? " ")).foregroundColor(.white)
            case .substitute:
                return acc + Text(String(op.expected ?? " ")).foregroundColor(.red).underline()
            case .missing:
                let ch = op.expected == " " ? "⎵" : String(op.expected ?? " ")
                return acc + Text(ch).foregroundColor(.orange).strikethrough()
            case .extra:
                let ch = op.sent == " " ? "⎵" : String(op.sent ?? " ")
                return acc + Text(ch).foregroundColor(Theme.textSecondary).strikethrough()
            }
        }
    }

    private func pretty(_ pattern: String?) -> String {
        String((pattern ?? "").map { (c: Character) -> Character in c == "." ? "·" : "−" })
    }

    private func describe(_ op: SendingAnalysis.AlignmentOp) -> String {
        let expected = op.expected.map(String.init) ?? ""
        switch op.kind {
        case .substitute:
            let sent = op.sent == MorseDecoder.unknownMarker ? "an unknown character" : String(op.sent ?? " ")
            return "\(expected) (\(pretty(MorseCode.pattern(for: op.expected ?? " ")))) sent as \(sent) (\(pretty(op.sentPattern)))"
        case .missing:
            return op.expected == " " ? "word break sent too short" : "\(expected) left out"
        case .extra:
            if op.sent == " " { return "a gap inside a word read as a word break" }
            return "extra \(op.sent == MorseDecoder.unknownMarker ? "unknown character" : String(op.sent ?? " ")) (\(pretty(op.sentPattern)))"
        case .match:
            return expected
        }
    }

    private var feedbackCard: some View {
        SectionCard(title: "Feedback") {
            ForEach(analysis.feedback, id: \.self) { code in
                Label {
                    Text(analysis.message(for: code))
                        .font(.callout)
                        .foregroundStyle(.white)
                        .fixedSize(horizontal: false, vertical: true)
                } icon: {
                    Image(systemName: code.isPraise ? "checkmark.circle.fill" : "exclamationmark.circle")
                        .foregroundStyle(code.isPraise ? Theme.tealBright : .orange)
                }
            }
            if analysis.feedback.isEmpty {
                Text("Send a little more for timing feedback.")
                    .font(.callout)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private var spacingCard: some View {
        SectionCard(title: "Spacing") {
            if analysis.characterGaps.count > 0 {
                Histogram(title: "Between characters (aim 3)",
                          counts: analysis.characterGapHistogram,
                          labels: SendingAnalysis.charGapBinLabels,
                          ideal: SendingAnalysis.idealCharGapBin)
            }
            if analysis.wordGaps.count > 0 {
                Histogram(title: "Between words (aim 7)",
                          counts: analysis.wordGapHistogram,
                          labels: SendingAnalysis.wordGapBinLabels,
                          ideal: SendingAnalysis.idealWordGapBin)
            }
            Text(spacingSummary)
                .font(.footnote.monospacedDigit())
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var spacingSummary: String {
        var parts: [String] = []
        if analysis.characterGaps.count > 0 {
            parts.append(String(format: "Characters %.1f ± %.1f", analysis.characterGaps.mean, analysis.characterGaps.sd))
        }
        if analysis.wordGaps.count > 0 {
            parts.append(String(format: "words %.1f ± %.1f", analysis.wordGaps.mean, analysis.wordGaps.sd))
        }
        let unit = analysis.farnsworthFactor > 1.001 ? "Farnsworth spacing units" : "units"
        return parts.isEmpty ? "Send more than one character to see spacing."
            : parts.joined(separator: ", ") + " \(unit)."
    }

    private var elementsCard: some View {
        SectionCard(title: "Elements") {
            row("Dit", analysis.dits, units: analysis.dits.mean / (1200 / analysis.targetCharacterWpm))
            row("Dah", analysis.dahs, units: analysis.dahs.mean / (1200 / analysis.targetCharacterWpm))
            if let r = analysis.dahDitRatio {
                keyValue("Dah : dit", String(format: "%.1f : 1 (aim 3 : 1)", r))
            }
            if analysis.elementGaps.count > 0 {
                keyValue("Gap inside characters", String(format: "%.1f ± %.1f units (aim 1)",
                                                           analysis.elementGaps.mean, analysis.elementGaps.sd))
            }
            if !analysis.keyType.handTimesElements {
                Text("Your keyer times the elements, so they are shown but not judged.")
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
    }

    @ViewBuilder
    private func row(_ name: String, _ s: SendingAnalysis.Stat, units: Double) -> some View {
        if s.count > 0 {
            keyValue("\(name) ×\(s.count)",
                     String(format: "%.0f ± %.0f ms (%.1f units at %d WPM)",
                            s.mean, s.sd, units, Int(analysis.targetCharacterWpm)))
        }
    }

    private func keyValue(_ k: String, _ v: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(k).font(.subheadline).foregroundStyle(.white)
            Spacer(minLength: 8)
            Text(v).font(.footnote.monospacedDigit()).foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.trailing)
        }
    }
}

/// A small bar chart of gap counts, the ideal bin in the bright colour.
private struct Histogram: View {
    let title: String
    let counts: [Int]
    let labels: [String]
    let ideal: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline).foregroundStyle(.white)
            let top = max(1, counts.max() ?? 1)
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(counts.indices, id: \.self) { i in
                    VStack(spacing: 3) {
                        Text(counts[i] > 0 ? "\(counts[i])" : " ")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                        RoundedRectangle(cornerRadius: 3)
                            .fill(i == ideal ? Theme.tealBright : Theme.teal.opacity(0.45))
                            .frame(height: max(2, 64 * CGFloat(counts[i]) / CGFloat(top)))
                        Text(labels[i])
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(i == ideal ? Theme.tealBright : Theme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 100, alignment: .bottom)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title + ": " + counts.indices.filter { counts[$0] > 0 }
            .map { "\(counts[$0]) at \(labels[$0]) units" }.joined(separator: ", "))
    }
}

// MARK: - Record

private struct RecordSection: View {
    @ObservedObject var analyzer: SendingAnalyzerModel
    @State private var confirmingClear = false

    var body: some View {
        let record = analyzer.record
        if !record.isEmpty {
            SectionCard(title: "Your sending over time") {
                let chars = record.problemCharacters()
                let pairs = record.problemPairs()
                let mixups = record.mixups.entries().prefix(5)
                if chars.isEmpty && pairs.isEmpty && mixups.isEmpty {
                    Text("No repeat trouble spots yet. Characters and pairs you miss after at least three tries show up here.")
                        .font(.footnote)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !chars.isEmpty {
                    line("Characters", chars.map { "\($0.key) \(percent($0.tally))" })
                }
                if !pairs.isEmpty {
                    line("Pairs", pairs.map { "\($0.key) \(percent($0.tally))" })
                }
                if !mixups.isEmpty {
                    line("Mix-ups", mixups.map { "\($0.target)→\($0.chosen) ×\($0.count)" })
                }
                let recent = record.attempts.suffix(5).reversed()
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(Array(recent.enumerated()), id: \.offset) { _, a in
                        Text("\(a.date.formatted(date: .abbreviated, time: .shortened)) · \(Int((a.accuracy * 100).rounded()))% · \(Int(a.characterWpm.rounded())) WPM")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
                Button("Clear sending record", role: .destructive) { confirmingClear = true }
                    .font(.footnote)
                    .confirmationDialog("Clear your sending record?", isPresented: $confirmingClear,
                                        titleVisibility: .visible) {
                        Button("Clear", role: .destructive) { analyzer.clearRecord() }
                    }
            }
        }
    }

    private func percent(_ t: SendingRecord.Tally) -> String {
        "\(Int((t.missRate * 100).rounded()))% missed (\(t.misses)/\(t.attempts))"
    }

    private func line(_ title: String, _ items: [String]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.subheadline).foregroundStyle(.white)
            Text(items.joined(separator: " · "))
                .font(.footnote.monospaced())
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
