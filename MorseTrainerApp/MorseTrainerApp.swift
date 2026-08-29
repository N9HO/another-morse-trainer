import SwiftUI

@main
struct MorseTrainerApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .tint(Theme.teal)              // brand accent on all controls
                .preferredColorScheme(.dark)   // navy-friendly dark UI
        }
        // The background-noise floor (issue #29) is a foreground comfort: it
        // exists to keep a Bluetooth route awake while you practise. Leaving it
        // running once the app is backgrounded would hiss indefinitely — the
        // `.playback` session keeps audio alive for hands-free Listen — so it
        // follows the scene in and out.
        .onChange(of: scenePhase) { phase in
            model.setAudioActive(phase == .active)
        }
    }
}

/// Shows the intro first, then the trainer once the user taps Start.
struct RootView: View {
    @State private var started = false
    /// Set when leaving a session via "Change setup": the intro then opens the
    /// selected mode's pre-flight sheet right away instead of landing on the
    /// bare menu grid (issue #67).
    @State private var reopenSetup = false

    var body: some View {
        ZStack {
            Theme.Background()
            if started {
                ContentView(onExit: { withAnimation { started = false; reopenSetup = true } })
            } else {
                IntroView(onStart: { withAnimation { started = true } },
                          openSetup: $reopenSetup)
            }
        }
    }
}
