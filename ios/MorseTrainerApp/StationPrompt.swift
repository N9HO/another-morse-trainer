// StationPrompt.swift
// Set up Your Station on first entry to Pileup Runner or Contest (#297).
// Both key your callsign when you call CQ and sign off, and until it is set that
// callsign is the W1AW placeholder: a first-time user found themselves on
// the air as W1AW with no idea why. So the first time either mode's setup
// opens while the callsign is still blank or W1AW, it asks for the station:
// callsign, and optionally name and state (CW 77 and First Four use those).
//
// Asked once. "Save" and "Not now" both answer it for good; Settings ›
// QSO & Pileups › Your Station is where to change the station later, and
// the prompt says so. An install whose callsign is already set is never
// asked. The Android and desktop twins are `StationPrompt.kt`; their
// Contest does not send your callsign, so there it is Pileup Runner only.

import SwiftUI

enum StationPrompt {
    /// The `@AppStorage` key recording that the prompt has been answered.
    static let askedKey = "stationPrompt.asked"

    /// Whether a callsign is still unset: blank or the W1AW placeholder.
    static func isPlaceholder(_ call: String) -> Bool {
        let c = call.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return c.isEmpty || c == MorseData.cw77PlaceholderCallsign
    }
}

/// The alert, raised once when the view it is attached to appears for
/// Pileup Runner or Contest with the callsign unset.
private struct StationPromptModifier: ViewModifier {
    @EnvironmentObject var model: AppModel
    let active: Bool
    @AppStorage(StationPrompt.askedKey) private var asked = false
    @State private var isPresented = false
    @State private var call = ""
    @State private var name = ""
    @State private var state = ""

    func body(content: Content) -> some View {
        content
            .onAppear {
                guard active, !asked, StationPrompt.isPlaceholder(model.settings.qso.myCall) else { return }
                name = model.settings.qso.myName
                state = model.settings.qso.myState
                isPresented = true
            }
            .alert("Set up your station?", isPresented: $isPresented) {
                TextField("Your callsign", text: $call)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                TextField("Your name (optional)", text: $name)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                TextField("Your state (optional)", text: $state)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Button("Save") {
                    let c = FirstFour.normalizeCall(call)
                    if !c.isEmpty { model.settings.qso.myCall = c }
                    model.settings.qso.myName = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    model.settings.qso.myState = FirstFour.normalizeState(state)
                    asked = true
                }
                Button("Not now", role: .cancel) {
                    asked = true
                }
            } message: {
                Text("Pileup Runner and Contest send your callsign when you call CQ and sign off. Until you set it, it sends W1AW. Your name and state are optional; CW 77 and First Four use them. You can change all three later in Settings › QSO & Pileups › Your Station.")
            }
    }
}

extension View {
    /// Attach to a mode's setup; asks for Your Station once, on first
    /// appearance while `active` and the callsign is unset (#297).
    func stationPrompt(active: Bool) -> some View {
        modifier(StationPromptModifier(active: active))
    }
}
