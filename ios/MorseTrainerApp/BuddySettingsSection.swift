// BuddySettingsSection.swift
// Settings › Leaderboard & Buddy › Buddy streak (docs/buddy-streak-design.md,
// #219, #237): the
// buddy list — each buddy's streak and day at a glance, with Leave on each —
// then Invite and Join while the list has room. Its own view so the Settings
// screen places it with one line. Uses the leaderboard's display name and
// attestation but not its switch: inviting or joining is the consent. What
// it shows is the cache (`model.settings.buddy`), refreshed as the section
// appears (15-minute limit), so it survives the sheet closing.

import SwiftUI
import UIKit

struct BuddySettingsSection: View {
    @EnvironmentObject var model: AppModel
    /// The rows' background: `SettingsView` passes its search-highlight tint.
    var rowBackground: Color = Theme.navyElevated

    @State private var joinCode = ""
    @State private var busy = false
    @State private var problem: String?
    @State private var leaving: BuddyEntry?
    @State private var copiedInvite = false
    /// Why a tapped Invite or Join cannot go ahead, shown as an alert.
    @State private var blocked: Blocked?

    private struct Blocked {
        let title: String
        let reason: String
    }

    private var cache: BuddyStatusCache { model.settings.buddy }

    var body: some View {
        Section {
            if cache.paired {
                Text(cache.countLine)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .onAppear { model.refreshBuddyStatus() }
                    // On this one row, not the Section (#335): a modifier on a
                    // Form Section lands on every row in it, and a row carrying
                    // a confirmationDialog swallows its ShareLink's share sheet
                    // (the invite card's Share did nothing). This row exists
                    // exactly while there is a buddy to leave.
                    .confirmationDialog("Leave \(leaving?.displayName ?? "buddy")?",
                                        isPresented: Binding(get: { leaving != nil }, set: { if !$0 { leaving = nil } }),
                                        titleVisibility: .visible,
                                        presenting: leaving) { buddy in
                        Button("Leave \(buddy.displayName)", role: .destructive) {
                            run { await model.buddyLeave(buddy) }
                        }
                    } message: { _ in
                        Text("Ends your buddy streak with them, for both of you. Your other buddies are not affected, and either of you can pair again with a new code.")
                    }
                ForEach(Array(cache.buddies.enumerated()), id: \.offset) { _, buddy in
                    buddyRow(buddy)
                }
            } else {
                Text(BuddyStatusCache.notPairedLine)
                    .foregroundStyle(.secondary)
                    .onAppear { model.refreshBuddyStatus() }
            }
            if let reason = model.buddyUnavailableReason {
                Text(reason)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
            if cache.isFull {
                Text("Your buddy list is full. Leave a buddy to add another.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                if let invite = cache.pendingInvite(at: Date()) {
                    inviteCard(code: invite.code, expiresAt: invite.expiresAt)
                }
                Button {
                    guard explainIfUnavailable(action: "invite a buddy") else { return }
                    run {
                        if case .failure(let p) = await model.buddyInvite() { return p.message }
                        return nil
                    }
                } label: {
                    HStack {
                        Label(cache.pendingInvite(at: Date()) != nil ? "New invite code"
                              : (cache.paired ? "Invite another buddy" : "Invite a buddy"),
                              systemImage: "person.crop.circle.badge.plus")
                        if busy { Spacer(); ProgressView() }
                    }
                }
                // Not disabled when the buddy actions are unavailable (#335):
                // in this Form a disabled Label row looks exactly like an
                // enabled one, so the tap did nothing and said nothing. The
                // tap explains instead (`explainIfUnavailable`).
                .disabled(busy)
                .alert(blocked?.title ?? "",
                       isPresented: Binding(get: { blocked != nil }, set: { if !$0 { blocked = nil } }),
                       presenting: blocked) { _ in
                    Button("OK", role: .cancel) {}
                } message: { b in
                    Text(b.reason)
                }
                HStack {
                    Text("Join with a code")
                    Spacer()
                    TextField("ABC234", text: $joinCode)
                        .multilineTextAlignment(.trailing)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .keyboardType(.asciiCapable)
                        .font(.body.monospaced())
                        .frame(maxWidth: 140)
                        .onChange(of: joinCode) { raw in
                            // Uppercase, no separators, six at most —
                            // the form the server reads (BuddyInviteCode).
                            let kept = BuddyInviteCode.typed(raw)
                            if kept != raw { joinCode = kept }
                        }
                    Button("Join") {
                        guard explainIfUnavailable(action: "join a buddy") else { return }
                        let code = joinCode
                        run {
                            let problem = await model.buddyJoin(code: code)
                            if problem == nil { joinCode = "" }
                            return problem
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy || BuddyInviteCode.normalize(joinCode) == nil)
                }
            }
            if let problem {
                Text(problem)
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        } header: {
            Text("Buddy streak")
        } footer: {
            Text("Pair with up to ten people and keep a separate streak with each: the days you both practiced. Any practice day counts, the same as your own streak, and one practice counts for every buddy. Your buddies see your leaderboard display name and whether you practiced each day, nothing else; pairing needs the same device attestation as posting a score but not the Share scores switch. Leaving a buddy ends that streak for both of you and keeps the others, and Delete my scores above removes every pairing.")
        }
        .listRowBackground(rowBackground)
    }

    /// One buddy: the name, the pairing's streak and whether they practised
    /// today, and Leave.
    private func buddyRow(_ buddy: BuddyEntry) -> some View {
        let practised = cache.practised(buddy, on: model.buddyToday)
        return HStack(spacing: 12) {
            Image(systemName: practised ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(practised ? Theme.tealBright : .secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(buddy.displayName)
                    .font(.body.weight(.semibold))
                Text(cache.rowLine(for: buddy, today: model.buddyToday))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            Spacer()
            Button("Leave", role: .destructive) { leaving = buddy }
                .buttonStyle(.borderless)
                .disabled(busy || model.buddyUnavailableReason != nil)
                .accessibilityLabel("Leave \(buddy.displayName)")
        }
    }

    /// True when the buddy actions can run. Otherwise puts up an alert
    /// saying why (no display name, or a device that cannot attest) and what
    /// to do, so the tap is never silent (#335), and returns false.
    private func explainIfUnavailable(action: String) -> Bool {
        guard let reason = model.buddyUnavailableReason else { return true }
        Haptics.error()
        blocked = Blocked(title: "Can't \(action) yet", reason: reason)
        return false
    }

    /// Run one buddy action (invite, join, leave) with the busy spinner up
    /// and its refusal, if any, shown under the buttons.
    private func run(_ action: @escaping @MainActor () async -> String?) {
        busy = true
        problem = nil
        copiedInvite = false
        Task {
            problem = await action()
            busy = false
        }
    }

    /// An invite this device issued: the code large enough to read out, when
    /// it lapses, and the two ways to send it. Single use, 24 hours.
    private func inviteCard(code: String, expiresAt: Date) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(code)
                .font(.system(size: 36, weight: .bold, design: .monospaced))
                .tracking(6)
                .foregroundStyle(Theme.tealBright)
                .frame(maxWidth: .infinity)
                .accessibilityLabel("Invite code \(code.map(String.init).joined(separator: " "))")
            Text("Send this to your buddy however you like — it works once and expires \(expiresAt.formatted(date: .abbreviated, time: .shortened)).")
                .font(.footnote)
                .foregroundStyle(.secondary)
            HStack {
                ShareLink(item: shareText(code: code)) {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
                Spacer()
                Button {
                    UIPasteboard.general.string = code
                    copiedInvite = true
                    Haptics.success()
                } label: {
                    Label(copiedInvite ? "Copied" : "Copy code", systemImage: copiedInvite ? "checkmark.circle" : "doc.on.doc")
                }
            }
            .buttonStyle(.bordered)
        }
        .padding(.vertical, 4)
    }

    private func shareText(code: String) -> String {
        "Be my Morse buddy in Another Morse Trainer: open Settings › Leaderboard & Buddy › Buddy streak and join with the code \(code). It works once and expires in 24 hours."
    }
}
