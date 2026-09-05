import SwiftUI

/// First-run onboarding: welcome the learner and ask how much Morse they
/// already know, seeding the Characters Koch ladder and unlocking the Journey
/// that far (#151). Shown once, gated by `AppSettings.onboardingDone`; the
/// answer can be changed later under Settings → Proficiency. Twin of the
/// Android `OnboardingScreen`.
///
/// It replaces the per-mode "Where are you starting?" card the setup sheet
/// used to carry: one app-wide answer, asked once, instead of the same
/// question re-asked on every Characters, Confusion Drill and Sending Practice
/// launch — where tapping the level you already had silently restarted the
/// ladder.
struct OnboardingView: View {
    @EnvironmentObject var model: AppModel
    @State private var selected: Proficiency = .none

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                logoMark
                Text("Welcome to\nAnother Morse Trainer")
                    .font(.title).bold()
                    .multilineTextAlignment(.center)
                Text("Learn to copy Morse by ear with the Koch method — full-speed characters, one at a time, building up as you go.")
                    .font(.subheadline)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)

                Text("How much Morse do you already know?")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .padding(.top, 28)
                    .padding(.bottom, 4)

                ForEach(Proficiency.allCases) { level in
                    levelCard(level)
                }

                Button {
                    Haptics.tap()
                    withAnimation { model.completeOnboarding(selected) }
                } label: {
                    Text("Start practicing")
                        .font(.headline)
                        .foregroundStyle(Theme.navy)
                        .frame(maxWidth: .infinity, minHeight: 54)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.teal)
                .padding(.top, 12)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 32)
            .readableWidth()
        }
        .onAppear { selected = model.settings.proficiency }
    }

    /// One selectable proficiency row: the chosen one fills with the brand
    /// teal and, as on the mode tiles, carries navy text for contrast.
    private func levelCard(_ level: Proficiency) -> some View {
        let isSelected = selected == level
        return Button {
            Haptics.selection()
            selected = level
        } label: {
            Text(level.label)
                .font(.body.weight(isSelected ? .bold : .medium))
                .foregroundStyle(isSelected ? Theme.navy : .primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 18)
                .padding(.vertical, 18)
                .background(
                    RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                        .fill(isSelected ? Theme.teal : Theme.navyElevated)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: Theme.cornerRadius, style: .continuous)
                        .strokeBorder(isSelected ? Theme.tealBright : Theme.hairline,
                                      lineWidth: isSelected ? 2 : 1)
                )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    /// Brand mark, as on the intro screen: the real logo if it's in the asset
    /// catalog, otherwise a styled placeholder in the brand colors.
    @ViewBuilder
    private var logoMark: some View {
        if let ui = UIImage(named: "AMTLogo") {
            Image(uiImage: ui)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 160)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        } else {
            ZStack {
                Circle()
                    .fill(Theme.teal.opacity(0.12))
                    .frame(width: 108, height: 108)
                Circle()
                    .strokeBorder(Theme.teal, lineWidth: 6)
                    .frame(width: 96, height: 96)
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 42))
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
        }
    }
}

#Preview {
    ZStack {
        Theme.Background()
        OnboardingView().environmentObject(AppModel())
    }
    .preferredColorScheme(.dark)
}
