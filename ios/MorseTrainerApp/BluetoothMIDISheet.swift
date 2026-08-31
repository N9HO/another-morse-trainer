// BluetoothMIDISheet.swift
// The "Bluetooth MIDI Devices" browser, presented from anywhere a hardware key
// is expected.
//
// Pairing a BLE-MIDI key in iOS Settings > Bluetooth is *not* enough to make it
// playable: CoreMIDI only sees a BLE-MIDI peripheral once something has
// connected it as a MIDI device, and on iOS the only thing that does that is
// CoreAudioKit's central view controller. So a key can read "Connected" in
// Settings while every app on the phone — this one included — still enumerates
// zero MIDI sources. The Android port has the same requirement and solves it in
// BleMidi.kt (scan for the BLE-MIDI service, then openBluetoothDevice); this is
// the Apple-side counterpart.

import CoreAudioKit
import SwiftUI
import UIKit

/// Wraps iOS's own BLE-MIDI browser so it can be presented as a SwiftUI sheet.
struct BluetoothMIDISheet: UIViewControllerRepresentable {
    /// Bound to the presenting `.sheet(isPresented:)` so Done dismisses it.
    @Binding var isPresented: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator { isPresented = false }
    }

    func makeUIViewController(context: Context) -> UINavigationController {
        let browser = CABTMIDICentralViewController()
        browser.title = "Bluetooth Key"
        browser.navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done,
            target: context.coordinator,
            action: #selector(Coordinator.done)
        )
        return UINavigationController(rootViewController: browser)
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}

    final class Coordinator: NSObject {
        private let onDone: () -> Void

        init(onDone: @escaping () -> Void) {
            self.onDone = onDone
        }

        @objc func done() {
            onDone()
        }
    }
}

extension View {
    /// Present the BLE-MIDI browser, and re-scan CoreMIDI when it closes.
    ///
    /// Connecting a key there does fire a CoreMIDI setup notification, but the
    /// re-scan on dismiss costs nothing and means the readout is already right
    /// the moment the sheet slides away.
    func bluetoothMIDISheet(isPresented: Binding<Bool>,
                            onDismiss: @escaping () -> Void) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            BluetoothMIDISheet(isPresented: isPresented)
        }
    }
}
