// AccountSettingsSection.swift
// Settings › Leaderboard & Buddy › Account (MorseKit/AccountSync.swift): sign
// in with an emailed link, then the sync switch, where the queue stands,
// Sync now and Sign out. Its own view so the Settings screen places it with
// one line, like Buddy streak. Everything it shows is `model.settings.account`
// and the two published states in AppModel+Account.swift.

import SwiftUI
import UIKit

struct AccountSettingsSection: View {
    @EnvironmentObject var model: AppModel
    /// The rows' background: `SettingsView` passes its search-highlight tint.
    var rowBackground: Color = Theme.navyElevated

    @State private var email = ""
    @State private var confirmSignOut = false

    private var account: AccountSettings { model.settings.account }

    var body: some View {
        Section {
            if account.signedIn {
                signedInRows
            } else {
                signInRows
            }
        } header: {
            Text("Account")
        } footer: {
            Text(account.signedIn
                 ? "Finished sessions are sent to your account when you are online, so your stats follow you between devices and apps you allow (Carrier Wave, for one) can read them. Turn the switch off to keep practising without sending; Sign out removes this device from your account."
                 : "Sign in with the email address of your Another Morse Trainer account: a link is emailed to you, with no password. Nothing is sent to the account until you sign in; after that, every finished session is.")
        }
        .listRowBackground(rowBackground)
    }

    // MARK: - Signed out

    @ViewBuilder
    private var signInRows: some View {
        switch model.accountSignIn {
        case .sending:
            HStack {
                ProgressView()
                Text("Sending the link…").padding(.leading, 6)
            }
        case .waiting(let address):
            HStack(alignment: .top) {
                ProgressView().padding(.top, 2)
                Text("Check \(address) for a message from Another Morse Trainer and press Confirm. This screen finishes by itself; the link works for 15 minutes.")
                    .font(.footnote)
                    .padding(.leading, 6)
            }
            Button("Cancel") { model.accountCancelSignIn() }
        case .idle, .failed:
            HStack {
                Text("Email")
                Spacer()
                TextField("you@example.com", text: $email)
                    .multilineTextAlignment(.trailing)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .submitLabel(.send)
                    .onSubmit(send)
            }
            Button(action: send) {
                Label("Send sign-in link", systemImage: "envelope")
            }
            .disabled(email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if case .failed(let message) = model.accountSignIn {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
    }

    private func send() {
        model.accountStartSignIn(email: email)
    }

    // MARK: - Signed in

    @ViewBuilder
    private var signedInRows: some View {
        HStack {
            Text("Signed in as")
            Spacer()
            VStack(alignment: .trailing) {
                if !account.label.isEmpty {
                    Text(account.label).font(.body.monospaced())
                }
                Text(account.email)
                    .font(account.label.isEmpty ? .body : .footnote)
                    .foregroundStyle(.secondary)
            }
        }
        Toggle("Sync sessions to my account", isOn: $model.settings.account.syncSessions)
            .onChange(of: model.settings.account.syncSessions) { on in
                if on { model.accountSyncNow() }
            }
        statusRow
        Button {
            model.accountSyncNow()
        } label: {
            HStack {
                Label("Sync now", systemImage: "arrow.triangle.2.circlepath")
                if model.accountSyncStatus == .uploading { Spacer(); ProgressView() }
            }
        }
        .disabled(!account.syncSessions || model.accountSyncStatus == .uploading)
        Button(role: .destructive) {
            confirmSignOut = true
        } label: {
            Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
        }
        .confirmationDialog("Sign out of your account on this device?",
                            isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { model.accountSignOut() }
        } message: {
            Text("Removes this device from your account and stops sending sessions. Sessions already sent stay in the account; your local stats and history are untouched.")
        }
    }

    @ViewBuilder
    private var statusRow: some View {
        let pending = model.accountQueue.count
        switch model.accountSyncStatus {
        case .uploading:
            Text(pending == 1 ? "Sending 1 session…" : "Sending \(pending) sessions…")
                .font(.footnote)
                .foregroundStyle(.secondary)
        case .problem(let message):
            Text(pending == 0 ? message : "\(message) \(pending == 1 ? "1 session is" : "\(pending) sessions are") waiting to be sent.")
                .font(.footnote)
                .foregroundStyle(.orange)
        case .rejected(let count, let reason):
            Text("Your account refused \(count == 1 ? "1 session" : "\(count) sessions")\(reason.isEmpty ? "" : " (\(reason))"). \(count == 1 ? "It stays" : "They stay") in your local history.")
                .font(.footnote)
                .foregroundStyle(.orange)
        case .idle:
            if !account.syncSessions {
                Text(pending == 0 ? "Sync is paused." : "Sync is paused; \(pending == 1 ? "1 session is" : "\(pending) sessions are") waiting.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if pending > 0 {
                Text("\(pending == 1 ? "1 session is" : "\(pending) sessions are") waiting to be sent.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if let last = account.lastSyncAt {
                Text("Up to date. Last sent \(last.formatted(.relative(presentation: .named))).")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text("Nothing sent yet. Sync now sends what this device holds.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
