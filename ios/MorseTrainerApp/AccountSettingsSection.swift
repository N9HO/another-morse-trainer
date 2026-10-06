// AccountSettingsSection.swift
// Settings › Account & Sync: the optional account (SyncCoordinator.swift).
// Signed out, a line on what an account is for, an email field and Send
// link, then the wait for the emailed link to be confirmed. Signed in, the
// email, the editable callsign and name, last synced, Sync now, Sign out and
// Delete account; the Devices section lists every device signed in to the
// account. Its own views so `SettingsView` places each with one line, as it
// does the buddy streak. Nothing here is needed for anything else in the
// app: signing in is optional and off until the user does it.

import SwiftUI

struct AccountSettingsSection: View {
    @ObservedObject var sync: SyncCoordinator
    /// The rows' background: `SettingsView` passes its search-highlight tint.
    var rowBackground: Color = Theme.navyElevated

    @State private var email = ""
    @State private var callsign = ""
    @State private var displayName = ""
    @State private var savingProfile = false
    @State private var profileProblem: String?
    @State private var profileSaved = false
    @State private var confirmDelete = false
    @State private var deleting = false
    @State private var deleteProblem: String?

    var body: some View {
        Section {
            if sync.state.signedOutByServer && !sync.isSignedIn {
                HStack(alignment: .top) {
                    Label("You were signed out. Sign in again to keep syncing.", systemImage: "exclamationmark.circle")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                    Spacer()
                    Button {
                        sync.dismissSignedOutBanner()
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Dismiss")
                }
            }
            if let account = sync.state.account {
                signedIn(account)
            } else {
                signedOut
            }
        } header: {
            Text("Account")
        } footer: {
            Text("Optional. An account keeps your progress in step across your devices and restores it on a new install: sessions, practice days, the Journey and Characters positions, First Four, Operating Procedure and story bookmarks. Sign-in is by an emailed link; there is no password. Settings are not synced, and nothing here touches the shared leaderboard.")
        }
        .listRowBackground(rowBackground)
    }

    // MARK: - Signed out

    @ViewBuilder
    private var signedOut: some View {
        Text("Sign in to sync your progress across your devices and restore it on a new install. Everything works without an account.")
            .font(.footnote)
            .foregroundStyle(.secondary)
        switch sync.signInStep {
        case .waiting(let address):
            HStack(spacing: 12) {
                ProgressView()
                VStack(alignment: .leading, spacing: 2) {
                    Text("Check your email on any device and tap Confirm.")
                    Text("Sent to \(address)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            Button("Cancel", role: .cancel) { sync.cancelSignIn() }
        case .sending:
            HStack {
                Text("Sending link…")
                Spacer()
                ProgressView()
            }
        case .idle, .expired, .problem:
            TextField("Email", text: $email)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.send)
                .onSubmit { sendLink() }
            Button {
                sendLink()
            } label: {
                Label("Send link", systemImage: "envelope")
            }
            .disabled(email.trimmingCharacters(in: .whitespaces).isEmpty)
            if sync.signInStep == .expired {
                Text("Link expired. Send another.")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
            if let problem = signInProblem {
                Text(problem)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
    }

    private func sendLink() {
        sync.sendLink(email: email)
    }

    private var signInProblem: String? {
        if case .problem(let problem) = sync.signInStep { return problem }
        return nil
    }

    // MARK: - Signed in

    @ViewBuilder
    private func signedIn(_ account: AccountSyncState.Account) -> some View {
        LabeledContent("Email", value: account.email ?? "—")
            // Fill the fields from the account, and again whenever it changes.
            .onAppear { fillProfile(account) }
            .onChange(of: account) { fillProfile($0) }
        HStack {
            Text("Callsign")
            Spacer()
            TextField("N0CALL", text: $callsign)
                .multilineTextAlignment(.trailing)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .font(.body.monospaced())
                .onChange(of: callsign) { raw in
                    // Uppercased as typed, the form the account keeps.
                    let up = raw.uppercased()
                    if up != raw { callsign = up }
                    profileSaved = false
                }
        }
        HStack {
            Text("Name")
            Spacer()
            TextField("Your name", text: $displayName)
                .multilineTextAlignment(.trailing)
                .autocorrectionDisabled()
                .onChange(of: displayName) { _ in profileSaved = false }
        }
        if profileChanged(account) {
            Button {
                saveProfile()
            } label: {
                HStack {
                    Label("Save callsign and name", systemImage: "checkmark.circle")
                    if savingProfile { Spacer(); ProgressView() }
                }
            }
            .disabled(savingProfile)
        }
        if let profileProblem {
            Text(profileProblem)
                .font(.footnote)
                .foregroundStyle(.orange)
        } else if profileSaved {
            Text("Saved.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        LabeledContent("Last synced", value: lastSyncedText)
        Button {
            Task { await sync.syncNow() }
        } label: {
            HStack {
                Label("Sync now", systemImage: "arrow.triangle.2.circlepath")
                if sync.syncing { Spacer(); ProgressView() }
            }
        }
        .disabled(sync.syncing)
        Button {
            Task { await sync.signOut() }
        } label: {
            Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
        }
        Button(role: .destructive) {
            confirmDelete = true
        } label: {
            HStack {
                Label("Delete account", systemImage: "trash")
                if deleting { Spacer(); ProgressView() }
            }
        }
        .disabled(deleting)
        .confirmationDialog("Delete your account?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete account", role: .destructive) {
                deleting = true
                deleteProblem = nil
                Task {
                    deleteProblem = await sync.deleteAccount()
                    deleting = false
                }
            }
        } message: {
            Text("Deletes your account and the progress synced to it on the server, for every device. This device keeps its own data; clear it with Reset all progress under Characters & Lessons. This cannot be undone.")
        }
        if let deleteProblem {
            Text(deleteProblem)
                .font(.footnote)
                .foregroundStyle(.orange)
        }
    }

    private var lastSyncedText: String {
        guard let date = sync.lastSynced else { return "Not yet" }
        return date.formatted(.relative(presentation: .named))
    }

    private func profileChanged(_ account: AccountSyncState.Account) -> Bool {
        callsign.trimmingCharacters(in: .whitespaces) != (account.callsign ?? "")
            || displayName.trimmingCharacters(in: .whitespaces) != (account.displayName ?? "")
    }

    private func fillProfile(_ account: AccountSyncState.Account) {
        callsign = account.callsign ?? ""
        displayName = account.displayName ?? ""
    }

    private func saveProfile() {
        savingProfile = true
        profileProblem = nil
        profileSaved = false
        let call = callsign, name = displayName
        Task {
            profileProblem = await sync.updateProfile(callsign: call, displayName: name)
            profileSaved = profileProblem == nil
            savingProfile = false
        }
    }
}

/// Settings › Account & Sync › Devices: every device signed in to the
/// account, this one marked, each with Sign out.
struct AccountDevicesSection: View {
    @ObservedObject var sync: SyncCoordinator
    var rowBackground: Color = Theme.navyElevated

    @State private var busy = false
    @State private var problem: String?

    var body: some View {
        Section {
            if !sync.isSignedIn {
                Text("Sign in to see the devices signed in to your account.")
                    .foregroundStyle(.secondary)
            } else if sync.devices.isEmpty {
                HStack {
                    Text(sync.loadingDevices ? "Loading devices…" : "No devices to show.")
                        .foregroundStyle(.secondary)
                    if sync.loadingDevices { Spacer(); ProgressView() }
                }
            } else {
                ForEach(sync.devices) { device in
                    deviceRow(device)
                }
            }
            if let problem {
                Text(problem)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        } header: {
            Text("Devices")
        } footer: {
            Text("Signing a device out stops it syncing; its own data stays on it. To sign out this device, use Sign out above.")
        }
        .listRowBackground(rowBackground)
        .task(id: sync.isSignedIn) { await sync.refreshDevices() }
    }

    private func deviceRow(_ device: AccountDevice) -> some View {
        let name = device.deviceName ?? device.client
        return HStack(spacing: 12) {
            Image(systemName: Self.symbol(for: device.platform))
                .foregroundStyle(device.current ? Theme.tealBright : .secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.body.weight(.semibold))
                Text(detail(device))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            Spacer()
            // This device signs out with the main Sign out above.
            if !device.current {
                Button("Sign out", role: .destructive) {
                    busy = true
                    problem = nil
                    Task {
                        problem = await sync.signOut(device: device)
                        busy = false
                    }
                }
                .buttonStyle(.borderless)
                .disabled(busy)
                .accessibilityLabel("Sign out \(name)")
            }
        }
    }

    private func detail(_ device: AccountDevice) -> String {
        let seen = AccountSync.date(epochMilliseconds: device.lastSeenAt).formatted(.relative(presentation: .named))
        var parts: [String] = []
        if device.current { parts.append("This device") }
        if let platform = device.platform { parts.append(Self.platformName(platform)) }
        parts.append("last seen \(seen)")
        return parts.joined(separator: " · ")
    }

    private static func platformName(_ platform: String) -> String {
        switch platform {
        case "ios": return "iOS"
        case "ipados": return "iPadOS"
        case "macos": return "macOS"
        case "android": return "Android"
        case "windows": return "Windows"
        case "linux": return "Linux"
        default: return platform
        }
    }

    private static func symbol(for platform: String?) -> String {
        switch platform {
        case "ipados": return "ipad"
        case "macos", "windows", "linux": return "desktopcomputer"
        default: return "iphone"
        }
    }
}
