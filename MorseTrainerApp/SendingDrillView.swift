import SwiftUI
import UIKit

/// Generates a printable *sending* practice sheet from the characters you've
/// studied. The app can't grade your fist, so the deliverable is the sheet
/// itself: pick a drill type and length, and share or print pages of random
/// groups to key on your paddle. Mirrors cwsignals.com's Sending Drills, but
/// built from this app's own progress (and weighted toward your weak spots).
struct SendingDrillView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var kind: SendingDrill.Kind = .studied
    @State private var groupCount: Double = 50
    @State private var groupSize: Double = 5
    @State private var drill: SendingDrill?

    private var subtitle: String {
        let wpm = Int(model.settings.wpm.rounded())
        return "\(wpm) WPM · \(Date().formatted(date: .abbreviated, time: .omitted))"
    }

    private var sheetText: String {
        drill?.plainText(title: "CW Sending Practice", subtitle: subtitle) ?? ""
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.Background()
                VStack(spacing: 14) {
                    controls
                    preview
                }
                .padding(.top, 8)
            }
            .navigationTitle("Sending Practice")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        regenerate()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("Generate a new sheet")
                }
            }
            .onAppear { if drill == nil { regenerate() } }
            .onChange(of: kind) { _ in regenerate() }
        }
    }

    // MARK: - Controls

    private var controls: some View {
        VStack(spacing: 14) {
            Picker("Drill", selection: $kind) {
                ForEach(SendingDrill.Kind.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)

            Text(kind.blurb)
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)

            slider("Lines", value: $groupCount, range: 10...100, step: 5,
                   readout: "\(Int(groupCount) / Int(groupSize)) lines")
            slider("Group size", value: $groupSize, range: 3...7, step: 1,
                   readout: "\(Int(groupSize)) chars")
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .brandCard()
        .padding(.horizontal, 16)
        .onChange(of: groupCount) { _ in regenerate() }
        .onChange(of: groupSize) { _ in regenerate() }
    }

    private func slider(_ title: String, value: Binding<Double>,
                        range: ClosedRange<Double>, step: Double,
                        readout: String) -> some View {
        VStack(spacing: 4) {
            HStack {
                Text(title).font(.subheadline).foregroundStyle(.white)
                Spacer()
                Text(readout)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Theme.textSecondary)
            }
            Slider(value: value, in: range, step: step).tint(Theme.teal)
        }
    }

    // MARK: - Preview + output

    private var preview: some View {
        VStack(spacing: 12) {
            ScrollView {
                Text(drill?.rows.joined(separator: "\n") ?? "")
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
            }
            .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.hairline, lineWidth: 1))

            HStack(spacing: 12) {
                ShareLink(item: sheetText) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Theme.navyElevated, in: Capsule())
                        .overlay(Capsule().strokeBorder(Theme.teal.opacity(0.6), lineWidth: 1.5))
                        .foregroundStyle(Theme.teal)
                }

                Button {
                    SheetPrinter.print(sheetText, jobName: "CW Sending Practice")
                } label: {
                    Label("Print", systemImage: "printer")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(Theme.teal, in: Capsule())
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
    }

    private func regenerate() {
        let weights = kind == .personalized ? model.sendingDrillWeights() : [:]
        drill = SendingDrill.generate(kind: kind,
                                      studied: model.studiedCharacters,
                                      weights: weights,
                                      groupCount: Int(groupCount),
                                      groupSize: Int(groupSize))
    }
}

/// Sends plain text to the system print panel (AirPrint / Save to PDF), using a
/// monospaced font so the character groups stay column-aligned on paper.
enum SheetPrinter {
    static func print(_ text: String, jobName: String) {
        let info = UIPrintInfo.printInfo()
        info.outputType = .general
        info.jobName = jobName

        let formatter = UISimpleTextPrintFormatter(text: text)
        formatter.font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        formatter.perPageContentInsets = UIEdgeInsets(top: 40, left: 40, bottom: 40, right: 40)

        let controller = UIPrintInteractionController.shared
        controller.printInfo = info
        controller.printFormatter = formatter
        controller.present(animated: true, completionHandler: nil)
    }
}

#Preview {
    SendingDrillView().environmentObject(AppModel())
        .preferredColorScheme(.dark)
}
