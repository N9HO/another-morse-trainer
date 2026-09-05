import SwiftUI

/// The distilled "Start here" for a newcomer: how to begin, what to expect,
/// and why the characters come at you so fast. A condensed, in-app version of
/// the website guide's opening sections, one tap from the home screen (#96) —
/// the site explained all of this, but a new user on the tile grid had no idea
/// it existed. Everything here is stated on the site too; keep the two in step.
struct StartHereView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    private struct Step { let lead: String; let body: String }
    private static let steps: [Step] = [
        Step(lead: "Put headphones on.",
             body: "Not required, but Morse is a listening exercise and phone speakers flatten the rhythm."),
        Step(lead: "Open Journey.",
             body: "It's the first tile, and it's the guided path. It decides what to teach you and when."),
        Step(lead: "Listen, don't look.",
             body: "A character plays and answer choices appear. Pick the one you heard. Miss, and the answer is revealed and re-sent so your ear gets a second pass."),
        Step(lead: "Don't count dots and dashes.",
             body: "At 33 WPM you physically can't, and that's deliberate. Let each character land as one sound, like a syllable. Too fast? Widen the gaps, never slow the characters."),
        Step(lead: "Stop after 10–15 minutes.",
             body: "Short and daily beats long and occasional. Turn on the daily reminder in Settings and let the streak do the nagging.")
    ]

    private struct Stage { let stage: String; let roughly: String; let work: String }
    private static let stages: [Stage] = [
        Stage(stage: "First week", roughly: "2–6 characters", work: "Journey. Daily, short. Don't chase speed."),
        Stage(stage: "Weeks 2–6", roughly: "Full alphabet", work: "Journey, plus Confusion Drill when pairs start blurring."),
        Stage(stage: "Your call + ? + state", roughly: "Any time from week 2", work: "Get on the air and hunt POTA. Don't wait for the full alphabet."),
        Stage(stage: "Copying letters", roughly: "All 36, slow", work: "Common Words and Type It. Close the Farnsworth gap."),
        Stage(stage: "Copying words", roughly: "~15 WPM effective", work: "Head Copy, Rapid Fire, Short Stories."),
        Stage(stage: "Operating", roughly: "~18 WPM+", work: "Pileup Runner, Contest, Vail repeater."),
        Stage(stage: "Pushing", roughly: "35–60 WPM", work: "QRQ Speed and head copy at pace.")
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text("If you've never copied a character in your life, this is the whole path. It takes about five minutes to get going, and you'll be recognizing your first two letters by sound in one sitting.")
                        .font(.body)
                        .foregroundStyle(Theme.textSecondary)

                    section("Getting started", systemImage: "figure.walk")
                    ForEach(Array(Self.steps.enumerated()), id: \.offset) { i, step in
                        stepRow(number: i + 1, lead: step.lead, body: step.body)
                    }
                    callout("TIP", "Feeling lost in the first few sessions is normal and expected. The Koch method starts you at a speed you can't decode consciously, because conscious decoding is a dead end that caps you at about 10 WPM. The discomfort is the method working.")

                    section("Why it sounds so fast", systemImage: "hare")
                    Text("There are two different speeds, and they are set independently.")
                        .foregroundStyle(Theme.textSecondary)
                    speedCard(title: "Character speed",
                              what: "How fast the dits and dahs inside a letter are sent.",
                              target: "33 WPM or above, from day one. Never lower.")
                    speedCard(title: "Effective speed",
                              what: "How fast text arrives once the silence between characters is counted.",
                              target: "Whatever you can currently handle. 5 WPM is fine.")
                    callout("KEY", "You are not expected to copy at 33 WPM. You are expected to learn at 33 WPM. A character at 33 WPM with two seconds of silence after it is correct, normal, and exactly how a beginner's session should look.")
                    Text("At 33 WPM a character arrives too fast to count, so the only thing your brain can do is take it as a single sound — which is the skill you're building. Drop below about 33 and counting becomes possible, your brain takes the shortcut, and you build a habit you'll later have to break. When it feels too fast, widen the gaps (Farnsworth spacing) or raise the Recognition target. Never lower the character speed.")
                        .foregroundStyle(Theme.textSecondary)
                    if model.settings.wpm < 33 {
                        callout("CHECK", "Your character speed is currently \(Int(model.settings.wpm.rounded())) WPM, which is slow enough to count dits at. Raise it to 33 in Settings before the counting habit forms, and leave the Farnsworth speed wherever you're comfortable.")
                    }

                    section("How the app decides what's next", systemImage: "waveform.path.ecg")
                    Text("The Koch method: you start with two characters at full speed. When you can reliably recognize them, a third is added, then a fourth. The speed never drops; only the number of characters grows.")
                        .foregroundStyle(Theme.textSecondary)
                    Text("Time-to-recognize: every answer is timed from the moment the last tone ends. Right in 0.4 seconds is learned; right in 3 seconds means you're still decoding it consciously. That timing drives which character comes next, when a new one unlocks, and your weakest-first stats. If you need more time to answer, raise the Recognition target — don't slow the code down.")
                        .foregroundStyle(Theme.textSecondary)

                    section("What good looks like", systemImage: "chart.line.uptrend.xyaxis")
                    VStack(spacing: 0) {
                        ForEach(Array(Self.stages.enumerated()), id: \.offset) { i, s in
                            if i > 0 { Divider().overlay(Theme.hairline) }
                            stageRow(s)
                        }
                    }
                    .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    section("Aim at a contact", systemImage: "antenna.radiowaves.left.and.right")
                    Text("Have a target beyond \"learn Morse\". The best one is a real contact, and it needs far less than the full alphabet: your own callsign, the question mark, your state, and a handful of exchange words. People who aim at a contact tend to get there; people who aim at finishing the alphabet often stall.")
                        .foregroundStyle(Theme.textSecondary)

                    section("Already know some Morse?", systemImage: "checkmark.seal")
                    Text("Open Settings → Proficiency and tell it what you already know. That sets your starting character set instead of making you re-earn A and N. Comfortable with the whole alphabet? Skip Journey and go straight to Common Words, Rapid Fire, or Pileup Runner.")
                        .foregroundStyle(Theme.textSecondary)
                }
                .padding(24)
                .readableWidth()
            }
            .navigationTitle("Start here")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Building blocks

    private func section(_ title: String, systemImage: String) -> some View {
        Label(title, systemImage: systemImage)
            .font(.title3).bold()
            .foregroundStyle(.primary)
            .padding(.top, 6)
    }

    private func stepRow(number: Int, lead: String, body: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.subheadline.weight(.bold).monospacedDigit())
                .foregroundStyle(Theme.navy)
                .frame(width: 26, height: 26)
                .background(Theme.teal, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(lead).font(.body.weight(.semibold))
                Text(body).font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private func callout(_ tag: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(tag)
                .font(.caption.weight(.heavy))
                .foregroundStyle(Theme.teal)
                .frame(width: 48, alignment: .leading)
            Text(text).font(.subheadline)
        }
        .padding(14)
        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.teal.opacity(0.35), lineWidth: 1))
    }

    private func speedCard(title: String, what: String, target: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.body.weight(.semibold))
            Text(what).font(.subheadline).foregroundStyle(Theme.textSecondary)
            Text(target).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.tealBright)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.navyElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func stageRow(_ s: Stage) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(s.stage).font(.subheadline.weight(.semibold))
                Spacer()
                Text(s.roughly).font(.caption).foregroundStyle(Theme.tealBright)
            }
            Text(s.work).font(.subheadline).foregroundStyle(Theme.textSecondary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}
