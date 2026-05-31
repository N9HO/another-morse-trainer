import SwiftUI

@main
struct MorseTrainerApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
    }
}

/// Shows the intro first, then the trainer once the user taps Start.
struct RootView: View {
    @State private var started = false

    var body: some View {
        if started {
            ContentView(onExit: { withAnimation { started = false } })
        } else {
            IntroView(onStart: { withAnimation { started = true } })
        }
    }
}
