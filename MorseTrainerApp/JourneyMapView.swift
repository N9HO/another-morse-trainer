import SwiftUI

/// The journey "map": every level on the path, grouped by section, showing what
/// you've cleared, where you are, and what's still locked. Tapping an unlocked
/// level starts the journey there.
struct JourneyMapView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    /// Levels grouped into their sections, preserving curriculum order.
    private var sections: [(name: String, levels: [JourneyLevel])] {
        var order: [String] = []
        var byName: [String: [JourneyLevel]] = [:]
        for level in model.journeyQuiz.levels {
            if byName[level.section] == nil { order.append(level.section) }
            byName[level.section, default: []].append(level)
        }
        return order.map { ($0, byName[$0] ?? []) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(sections, id: \.name) { section in
                    Section(section.name) {
                        ForEach(section.levels) { level in
                            row(for: level)
                        }
                    }
                }
            }
            .navigationTitle("Journey Map")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    @ViewBuilder
    private func row(for level: JourneyLevel) -> some View {
        let unlocked = model.journeyProgress.isUnlocked(level: level.number)
        let completed = model.journeyProgress.completed.contains(level.number)
        let isCurrent = level.number == model.journeyLevelNumber

        Button {
            guard unlocked else { return }
            model.selectJourneyLevel(level.number)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                icon(unlocked: unlocked, completed: completed)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Level \(level.number)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(unlocked ? .primary : .secondary)
                    Text(level.title)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                if isCurrent {
                    Text("Current")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Theme.teal.opacity(0.2), in: Capsule())
                        .foregroundStyle(Theme.teal)
                }
            }
        }
        .disabled(!unlocked)
    }

    @ViewBuilder
    private func icon(unlocked: Bool, completed: Bool) -> some View {
        if completed {
            Image(systemName: "checkmark.seal.fill").foregroundStyle(.green)
        } else if unlocked {
            Image(systemName: "circle").foregroundStyle(Theme.teal)
        } else {
            Image(systemName: "lock.fill").foregroundStyle(.secondary)
        }
    }
}
