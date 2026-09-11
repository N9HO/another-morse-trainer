// LeaderboardView.swift
// The shared leaderboard, read-only: one board per ranked mode, fetched from
// `GET /v1/board/{mode}` (no attestation needed). Reached from Your Stats.

import SwiftUI

struct LeaderboardView: View {
    @EnvironmentObject var model: AppModel
    @State private var board: LeaderboardMode = .rapidFire
    @State private var rows: [LeaderboardBoardRow] = []
    @State private var state: LoadState = .loading

    private enum LoadState: Equatable {
        case loading
        case loaded
        case failed(String)
    }

    var body: some View {
        List {
            Section {
                Picker("Mode", selection: $board) {
                    ForEach(LeaderboardMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.menu)
            } footer: {
                Text("Ranked on the speed summed over every item copied correctly, as graded by the server from the run's transcript — so a game's number here is not its on-screen score. One best per player per mode, from both apps.")
            }
            .listRowBackground(Theme.navyElevated)

            Section {
                switch state {
                case .loading:
                    HStack {
                        ProgressView()
                        Text("Loading…").foregroundStyle(.secondary)
                    }
                case .failed(let reason):
                    VStack(alignment: .leading, spacing: 6) {
                        Text(reason).foregroundStyle(.secondary)
                        Button("Try again") { Task { await load() } }
                    }
                case .loaded where rows.isEmpty:
                    Text("No scores yet — be the first.")
                        .foregroundStyle(.secondary)
                case .loaded:
                    ForEach(rows) { row in
                        boardRow(row)
                    }
                }
            } header: {
                Text(board.title)
            } footer: {
                if !settingsOptedIn {
                    Text("Your own runs are not posted until you turn on “Share scores” in Settings › Leaderboard.")
                }
            }
            .listRowBackground(Theme.navyElevated)
        }
        .scrollContentBackground(.hidden)
        .readableWidth()
        .background(Theme.Background())
        .navigationTitle("Leaderboard")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: board) { await load() }
        .refreshable { await load() }
    }

    private var settingsOptedIn: Bool { model.settings.leaderboard.shareScores }

    private func boardRow(_ row: LeaderboardBoardRow) -> some View {
        HStack(spacing: 12) {
            Text("#\(row.rank)")
                .font(.subheadline.monospacedDigit().weight(.semibold))
                .foregroundStyle(row.rank <= 3 ? Theme.tealBright : Color.secondary)
                .frame(minWidth: 36, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.displayName)
                    .font(.body.monospaced())
                HStack(spacing: 6) {
                    Image(systemName: row.platform == "android" ? "smartphone" : "apple.logo")
                        .font(.caption2)
                    Text(row.date)
                        .font(.caption2)
                }
                .foregroundStyle(.secondary)
            }
            Spacer()
            Text(row.metricLabel)
                .font(.title3.monospacedDigit()).bold()
        }
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        let mode = board
        state = .loading
        do {
            let fetched = try await model.leaderboard.board(mode)
            // The picker may have moved on while this was in flight.
            guard mode == board else { return }
            rows = fetched
            state = .loaded
        } catch let error as LeaderboardError {
            guard mode == board else { return }
            state = .failed(error.message)
        } catch {
            guard mode == board else { return }
            state = .failed(error.localizedDescription)
        }
    }
}
