import SwiftUI

/// Live CW decoder sheet: point the microphone at received Morse audio — a
/// rig's speaker, a WebSDR, a practice recording — and read it as text. The
/// decoding itself is the vendored Carrier Wave firmware core; this view just
/// runs the mic and shows what the core hears.
struct CWDecoderView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var engine = CWDecoderEngine()

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                VStack(spacing: 16) {
                    statusRow
                    transcript
                    if engine.micDenied { deniedNotice }
                    controls
                }
                .padding(20)
            }
            .navigationTitle("CW Decoder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onDisappear { engine.stop() }
        .presentationDetents([.large])
    }

    // MARK: - Telemetry

    private var statusRow: some View {
        HStack(spacing: 14) {
            Label {
                Text(engine.isListening ? levelText : "Off")
                    .font(.subheadline.monospacedDigit())
            } icon: {
                Image(systemName: "waveform")
                    .foregroundStyle(engine.tonePresent ? Theme.tealBright
                                     : engine.isListening ? Theme.teal : Theme.textSecondary)
            }
            Spacer()
            if engine.wpm > 0 {
                Text("\(Int(engine.wpm.rounded())) WPM")
                    .font(.subheadline.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Theme.tealBright)
                Text("\(Int(engine.toneHz.rounded())) Hz")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .brandCard()
        .accessibilityElement(children: .combine)
    }

    private var levelText: String {
        engine.inputLevel < 0.003 ? "Listening — it's quiet"
            : "Hearing audio"
    }

    // MARK: - Transcript

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if engine.decodedText.isEmpty {
                        Text(engine.isListening
                             ? "Waiting for CW…"
                             : "Tap Start and hold the phone near the audio you want copied.")
                            .font(.callout)
                            .foregroundStyle(Theme.textSecondary)
                    } else {
                        Text(engine.decodedText)
                            .font(Theme.copyFont(style: .title3, weight: .medium, monospaced: true,
                                                 slashedZero: model.settings.slashedZero))
                            .foregroundStyle(.primary)
                            .textSelection(.enabled)
                    }
                    Color.clear.frame(height: 1).id("cw-tail")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .brandCard()
            .onChange(of: engine.decodedText) { _ in
                withAnimation(.easeOut(duration: 0.15)) {
                    proxy.scrollTo("cw-tail", anchor: .bottom)
                }
            }
        }
    }

    private var deniedNotice: some View {
        Label {
            Text("Microphone access is off. Allow it in Settings → Privacy → Microphone to decode audio.")
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "mic.slash")
                .foregroundStyle(.orange)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }

    // MARK: - Controls

    private var controls: some View {
        HStack(spacing: 12) {
            Button {
                Haptics.tap()
                if engine.isListening { engine.stop() } else { engine.start() }
            } label: {
                Label(engine.isListening ? "Stop" : "Start",
                      systemImage: engine.isListening ? "stop.fill" : "mic.fill")
                    .font(.headline)
                    .foregroundStyle(engine.isListening ? .white : Theme.navy)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(engine.isListening ? .red : Theme.teal)

            Button {
                engine.clear()
            } label: {
                Label("Clear", systemImage: "trash")
                    .font(.headline)
                    .frame(minWidth: 96, minHeight: 50)
            }
            .buttonStyle(.bordered)
            .tint(Theme.textSecondary)
            .disabled(engine.decodedText.isEmpty)
        }
    }
}

#Preview {
    CWDecoderView()
        .environmentObject(AppModel())
        .preferredColorScheme(.dark)
}
