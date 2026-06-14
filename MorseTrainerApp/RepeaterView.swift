import SwiftUI

/// The live Vail repeater operating screen: connect to the internet Morse
/// repeater, see who's on, key with the on-screen key or a hardware MIDI key
/// (Vail Adapter / BLE MIDI), watch the activity timeline, and chat. Presented
/// full-screen from the intro screen's antenna button.
struct RepeaterView: View {
    @EnvironmentObject var model: RepeaterModel
    @Environment(\.dismiss) private var dismiss

    @State private var showingSettings = false
    @State private var showingChat = false

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                ScrollView {
                    VStack(spacing: 16) {
                        statusBar
                        connectionCard
                        statRow
                        RepeaterSignalTimelineView()
                        RepeaterTouchKeyView()
                            .frame(height: 150)
                        breakInCard
                        adapterCard
                        rosterCard
                    }
                    .padding(18)
                }
            }
            .navigationTitle("On the Air")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    Button { showingChat = true } label: {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: "bubble.left.and.bubble.right")
                            if model.unreadChatCount > 0 {
                                Circle().fill(Color.orange).frame(width: 8, height: 8)
                                    .offset(x: 4, y: -2)
                            }
                        }
                    }
                    Button { showingSettings = true } label: {
                        Image(systemName: "slider.horizontal.3")
                    }
                }
            }
            .sheet(isPresented: $showingSettings) {
                RepeaterSettingsSheet().environmentObject(model)
            }
            .sheet(isPresented: $showingChat) {
                RepeaterChatSheet().environmentObject(model)
            }
        }
        .tint(Theme.teal)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }

    // MARK: - Status

    private var statusBar: some View {
        HStack(spacing: 8) {
            Circle().fill(stateColor).frame(width: 9, height: 9)
            Text(stateText)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Theme.textSecondary)
            Spacer()
            if let notice = model.lastNotice {
                Text(notice)
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .lineLimit(1)
            }
        }
    }

    private var stateColor: Color {
        switch model.connectionState {
        case .connected: return .green
        case .connecting, .reconnecting: return .orange
        case .disconnected, .idleDisconnected: return Theme.textSecondary
        }
    }

    private var stateText: String {
        switch model.connectionState {
        case .connected: return "Connected · \(model.channel)"
        case .connecting: return "Connecting…"
        case .reconnecting: return "Reconnecting…"
        case .disconnected: return "Disconnected"
        case .idleDisconnected: return "Idle — key or chat to reconnect"
        }
    }

    // MARK: - Connection

    private var connectionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            labeledField("Callsign", text: Binding(
                get: { model.callsign },
                set: { model.setCallsign($0) }
            ), placeholder: "W1AW", autocaps: true)

            labeledField("Channel", text: $model.channel,
                         placeholder: "General", autocaps: false)

            let connected = model.connectionState == .connected
                || model.connectionState == .connecting
                || model.connectionState == .reconnecting
            Button {
                Haptics.tap()
                if connected { model.disconnect() } else { model.connect() }
            } label: {
                Text(connected ? "Disconnect" : "Connect")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 46)
            }
            .buttonStyle(.borderedProminent)
            .tint(connected ? .red : Theme.teal)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }

    private func labeledField(_ label: String, text: Binding<String>,
                              placeholder: String, autocaps: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .bold)).tracking(1)
                .foregroundStyle(Theme.textSecondary)
            TextField(placeholder, text: text)
                .textFieldStyle(.plain)
                .textInputAutocapitalization(autocaps ? .characters : .never)
                .autocorrectionDisabled()
                .padding(10)
                .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(.white)
        }
    }

    // MARK: - Stats

    private var statRow: some View {
        HStack(spacing: 0) {
            statCell("TX TONE", midiNoteName(model.txTone), highlight: true)
            divider
            statCell("LAG", "\(model.lagMs) ms", highlight: false)
            divider
            statCell("OPS", "\(model.clientCount)", highlight: false)
        }
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .brandCard()
    }

    private var divider: some View {
        Rectangle().fill(Theme.hairline).frame(width: 1, height: 28)
    }

    private func statCell(_ label: String, _ value: String, highlight: Bool) -> some View {
        VStack(spacing: 3) {
            Text(label)
                .font(.system(size: 9, weight: .bold)).tracking(1)
                .foregroundStyle(Theme.textSecondary)
            Text(value)
                .font(.system(size: 17, weight: .medium)).monospacedDigit()
                .foregroundStyle(highlight ? Theme.teal : .white)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Break-in

    private var breakInCard: some View {
        Toggle(isOn: $model.breakInEnabled) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Break-in (transmit)").font(.subheadline).bold()
                Text("When off, the key only plays your local sidetone — nothing is sent to the repeater.")
                    .font(.caption).foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(.red)
        .padding(18)
        .brandCard()
    }

    // MARK: - Adapter

    private var adapterCard: some View {
        HStack(spacing: 12) {
            Image(systemName: model.midiAdapterConnected ? "cable.connector" : "cable.connector.slash")
                .foregroundStyle(model.midiAdapterConnected ? .green : Theme.textSecondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.midiAdapterConnected ? "MIDI adapter connected" : "No MIDI adapter")
                    .font(.subheadline)
                Text("Plug in a Vail Adapter or pair a BLE MIDI key, then Wake.")
                    .font(.caption).foregroundStyle(Theme.textSecondary)
            }
            Spacer()
            Button("Wake") { model.wakeMidiAdapter() }
                .buttonStyle(.bordered)
                .controlSize(.small)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }

    // MARK: - Roster

    private var rosterCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ON CHANNEL (\(model.users.count))")
                .font(.system(size: 10, weight: .bold)).tracking(1)
                .foregroundStyle(Theme.textSecondary)
            if model.users.isEmpty {
                Text("No one else here yet.")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            } else {
                ForEach(model.users, id: \.callsign) { user in
                    HStack {
                        Image(systemName: "person.fill").foregroundStyle(Theme.teal).font(.caption)
                        Text(user.callsign == model.callsign ? "\(user.callsign) (you)" : user.callsign)
                            .font(.subheadline)
                        Spacer()
                        if let tone = user.txTone {
                            Text(midiNoteName(tone))
                                .font(.caption.monospaced()).foregroundStyle(Theme.textSecondary)
                        }
                    }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .brandCard()
    }

    private func midiNoteName(_ note: Int) -> String {
        let names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
        let octave = (note / 12) - 1
        return "\(names[note % 12])\(octave)"
    }
}

// MARK: - Settings sheet

private struct RepeaterSettingsSheet: View {
    @EnvironmentObject var model: RepeaterModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    Picker("Repeater", selection: $model.serverURLString) {
                        ForEach(RepeaterModel.knownServers, id: \.url) { server in
                            Text(server.name).tag(server.url)
                        }
                        if !RepeaterModel.knownServers.contains(where: { $0.url == model.serverURLString }) {
                            Text("Custom").tag(model.serverURLString)
                        }
                    }
                    TextField("wss://…/chat", text: $model.serverURLString)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("Takes effect on the next connect.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("Tone") {
                    Stepper("TX tone: \(midiNoteName(model.txTone)) (\(model.txTone))",
                            value: Binding(get: { model.txTone },
                                           set: { model.setTxTone($0) }),
                            in: 48...96)
                }

                Section("Receive") {
                    Stepper("RX delay: \(model.rxDelayMs) ms",
                            value: $model.rxDelayMs, in: 0...4000, step: 250)
                    Text("Buffer added to scheduled playback so late packets aren't dropped.")
                        .font(.caption).foregroundStyle(.secondary)
                    Toggle("Buzz adapter piezo on RX", isOn: $model.adapterRxFeedbackEnabled)
                }

                Section("Hardware keyer") {
                    Picker("Keyer mode", selection: $model.keyerMode) {
                        ForEach(MIDIOutput.KeyerMode.allCases, id: \.rawValue) { mode in
                            Text(mode.displayName).tag(mode.rawValue)
                        }
                    }
                    Stepper("Keyer speed: \(model.keyerWPM) WPM",
                            value: $model.keyerWPM, in: 5...50)
                    Text("Sent to the Vail Adapter for iambic/bug modes. Straight key ignores speed.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("Privacy") {
                    Toggle("Private channel", isOn: $model.privateMode)
                    Text("Hides your room from the server's public room list.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Repeater Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private func midiNoteName(_ note: Int) -> String {
        let names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
        let octave = (note / 12) - 1
        return "\(names[note % 12])\(octave)"
    }
}

// MARK: - Chat sheet

private struct RepeaterChatSheet: View {
    @EnvironmentObject var model: RepeaterModel
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                VStack(spacing: 0) {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 8) {
                                ForEach(model.chatMessages) { msg in
                                    chatRow(msg)
                                        .id(msg.id)
                                }
                            }
                            .padding(16)
                        }
                        .onChange(of: model.chatMessages.count) { _ in
                            if let last = model.chatMessages.last {
                                withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                            }
                        }
                    }
                    composer
                }
            }
            .navigationTitle("Chat · \(model.channel)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { model.markChatRead() }
        }
    }

    private func chatRow(_ msg: RepeaterModel.ChatMessage) -> some View {
        let mine = msg.callsign == model.callsign
        return VStack(alignment: mine ? .trailing : .leading, spacing: 2) {
            Text(msg.callsign ?? "?")
                .font(.system(size: 10, weight: .bold)).tracking(0.5)
                .foregroundStyle(Theme.textSecondary)
            Text(msg.text)
                .font(.subheadline)
                .foregroundStyle(.white)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(mine ? Theme.teal.opacity(0.25) : Theme.navyRaised,
                            in: RoundedRectangle(cornerRadius: 12))
        }
        .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
    }

    private var composer: some View {
        HStack(spacing: 10) {
            TextField("Message", text: $draft)
                .textFieldStyle(.plain)
                .padding(10)
                .background(Theme.navyRaised, in: RoundedRectangle(cornerRadius: 12))
                .foregroundStyle(.white)
                .onSubmit(send)
            Button(action: send) {
                Image(systemName: "paperplane.fill").font(.title3)
            }
            .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(12)
        .background(.ultraThinMaterial)
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        model.sendChat(text)
        draft = ""
    }
}
