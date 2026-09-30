// MacCatalystSupport.swift
// What the Mac build (Mac Catalyst, issue #264) does differently, gathered in
// one place so the rest of the app stays free of platform checks.
//
// Everything else — CoreMIDI keys and the Vail adapter, the tone engine,
// the microphone modes, the hardware-keyboard shortcuts the typing and
// multiple-choice modes already have — runs unchanged on the Mac.

import SwiftUI
import UIKit

extension Scene {
    /// Mac menu-bar commands. A no-op on iPhone.
    ///
    /// There is one `AppModel` and one audio engine per process, so a second
    /// window would be a second view onto the same session, both playing. File
    /// › New Window is taken away rather than half-supported. Help opens the
    /// user guide, which is where a Mac user looks for it.
    func macCommands() -> some Scene {
        #if targetEnvironment(macCatalyst)
        return commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(replacing: .help) {
                Button("Another Morse Trainer Guide") {
                    if let url = URL(string: "https://anothermorsetrainer.app/guide") {
                        UIApplication.shared.open(url)
                    }
                }
            }
        }
        #else
        return self
        #endif
    }
}

extension View {
    /// Keep the Mac window at least phone-sized. The layouts are phone-first
    /// and already cap themselves at `Theme.contentMaxWidth` when the window
    /// is wide (`readableWidth`), so only the lower bound needs setting. A
    /// no-op on iPhone.
    func macWindowSizing() -> some View {
        #if targetEnvironment(macCatalyst)
        return onAppear {
            for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
                scene.sizeRestrictions?.minimumSize = CGSize(width: 400, height: 640)
            }
        }
        #else
        return self
        #endif
    }
}

#if targetEnvironment(macCatalyst)
/// The Mac stand-in for `BluetoothMIDISheet`.
///
/// iOS connects a BLE-MIDI key only through CoreAudioKit's
/// `CABTMIDICentralViewController`, which does not exist on the Mac. macOS
/// does the same job system-wide in Audio MIDI Setup: once a key is connected
/// there, CoreMIDI lists it to every app, this one included, and it stays
/// connected across launches. So the Mac sheet explains where that is instead
/// of browsing. USB keys and the Vail adapter need none of this.
struct MacBluetoothMIDIHelp: View {
    @Binding var isPresented: Bool

    private static let steps = [
        "Open Audio MIDI Setup (in Applications › Utilities).",
        "Choose Window › Show MIDI Studio.",
        "Click the Bluetooth button in the toolbar (Configure Bluetooth).",
        "Find your key in the list and click Connect.",
        "Come back here and click Done. The key shows up as a MIDI key.",
    ]

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("On a Mac, Bluetooth MIDI keys are connected once, in macOS's Audio MIDI Setup. After that every app can use the key. USB keys and the Vail adapter work as soon as they are plugged in.")
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        ForEach(Array(Self.steps.enumerated()), id: \.offset) { index, step in
                            HStack(alignment: .firstTextBaseline, spacing: 10) {
                                Text("\(index + 1).")
                                    .monospacedDigit()
                                    .foregroundStyle(Theme.tealBright)
                                Text(step)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Button {
                            UIApplication.shared.open(
                                URL(fileURLWithPath: "/System/Applications/Utilities/Audio MIDI Setup.app"))
                        } label: {
                            Label("Open Audio MIDI Setup", systemImage: "pianokeys")
                        }
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 4)
                    }
                    .padding(20)
                    .readableWidth()
                }
            }
            .navigationTitle("Bluetooth Key")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { isPresented = false }
                }
            }
        }
    }
}
#endif
