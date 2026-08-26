# Handoff: get build 15 to testers + a public TestFlight link for Discord

Delete this file (its own commit) once the link is live. Everything below was
true as of the evening of 2026-08-26 (US Central).

## State — the hard parts are already done

- `main` = `bc07f3f`: v1.1 **build 15** (CW decoder + Short Stories, PR #55),
  CI green.
- Build 15 is **uploaded and processed** in App Store Connect — status
  "Ready to Submit" under TestFlight → iOS Builds. Uploaded headlessly from
  the Mac via the Xcode account session.
- Export compliance is auto-answered (`ITSAppUsesNonExemptEncryption=false`
  in `Config/Info.plist`) — no "Missing Compliance" step.
- An **internal** tester group exists (13 testers; they received builds
  10–14). Build 15 is not yet in any group.
- **No external group exists yet**, so there is no public link yet — public
  links only live on external groups. That is why this was never "two
  clicks": the two-click move distributes to the existing internal group;
  Discord needs the one-time external setup below.

## To do in App Store Connect (human clicks, ~2 minutes)

App Store Connect → Another Morse Trainer → TestFlight.

1. **Existing 13 testers (the actual two clicks):** open the internal group
   → Builds → **+** → add 1.1 (15). Internal needs no review; it lands
   immediately.
2. **Discord public link (one-time external setup):**
   - Test Information (left sidebar): fill Beta App Description + Feedback
     Email if empty — suggested text below. The External Testing UI stays
     hidden/gated until this exists.
   - Create an **External** group (name it e.g. "Discord").
   - Add build 15 to it → the first external build auto-submits to **Beta
     App Review** (typically hours, up to ~48 h; later builds of the same
     version skip this).
   - Turn on **Public Link** for the group (optional tester cap).
   - Copy `https://testflight.apple.com/join/…` → paste in Discord. The
     link resolves right away; installs start once review clears.

Suggested beta description:
> Learn and practice Morse code: a guided Koch journey, listening drills,
> short stories and serials, live QSO practice on Vail, sending practice —
> and new in this build, a live CW decoder: tap the waveform icon, point
> the mic at Morse audio (rig speaker, WebSDR), and read it as text.

Feedback email: jus.k.rog@gmail.com

## Notes for future sessions (make next time one-shot)

- Headless upload from the Mac works and is proven:
  `xcodebuild archive` (Release, `generic/platform=iOS`) then
  `xcodebuild -exportArchive` with an ExportOptions.plist of
  `method=app-store-connect, destination=upload, signingStyle=automatic,
  teamID=F6ASU3CH5M, manageAppVersionAndBuildNumber=false` plus
  `-allowProvisioningUpdates` — auth rides on Xcode's signed-in Apple ID.
- `~/.appstoreconnect/private_keys` holds two **team** API keys
  (`35KAFXDH4S`, `A4RN4AD4X5`) but their **Issuer ID is recorded nowhere on
  the Mac**, which blocks all scripted App Store Connect API work (that's
  what forced the browser this time). Grab it once from App Store Connect →
  Users and Access → Integrations, save it to
  `~/.appstoreconnect/issuer_id`, and every future upload / group / public
  link operation can be fully scripted.
- Remember to bump `CURRENT_PROJECT_VERSION` (both entries in the pbxproj)
  before the next TestFlight upload; 15 is used.
