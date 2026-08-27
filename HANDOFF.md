# Handoff: get build 15 to testers + a public TestFlight link for Discord

Delete this file (its own commit) once the link is live and announced.

Corrected 2026-08-27: the 08-26 version of this file claimed the App Store
Connect Issuer ID was "recorded nowhere on the Mac" and that scripted ASC
work was therefore blocked. Wrong — `tools/asc-auth.sh` (gitignored, so
invisible to repo greps) exists in all three clones on the Mac and carries
`ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_KEY_PATH` / `ASC_APP_ID`; build 14
shipped end-to-end through it via `./tools/upload-testflight.sh`. Nearly
everything below is one scripted step per action; only the one-time
external-group setup ever needed the UI, and `extgroup` / `publiclink`
(added to `tools/asc-api.py` on this branch) script that too.

## State

- Build 15 (v1.1) is uploaded and processed — "Ready to Submit", not in
  any group yet. Export compliance is auto-answered in `Config/Info.plist`.
- The internal group has 13 testers (they received builds 10–14). No
  external group and no public link exist yet.
- The Discord announcement is fixed and armed on branch
  `claude/build-15-testflight-3v2frw`: dispatching
  `.github/workflows/discord-release.yml` with a `testflight_link` input
  curl-verifies the link, then posts changelog + join link through the
  channel webhook. Dry-run verified 08-26 (secret present, payload right).

## Ship build 15 to the existing testers (Mac, any clone)

    source tools/asc-auth.sh
    python3 tools/asc-api.py wait 15     # until processingState=VALID
    python3 tools/asc-api.py dist        # build 15 -> same testers as build 14
    python3 tools/asc-api.py submit      # beta app review; first external
                                         # build takes hours, up to ~48 h

## Mint the public link for Discord (scripted; needs this branch)

    python3 tools/asc-api.py extgroup Discord     # one-time: find-or-create the external group
    python3 tools/asc-api.py notify <group-id>    # add build 15 to it (id printed above)
    python3 tools/asc-api.py publiclink Discord   # enable + print https://testflight.apple.com/join/…

First-external-submission caveat: App Store Connect wants Test Information
(beta app description + feedback email) and beta-review contact details on
file before the first external build; if `submit` or `notify` complain,
fill those once in the UI (suggested text below) and re-run. The UI
fallback for the whole section: TestFlight → Test Information → create
External group "Discord" → add build 15 → enable Public Link.

Suggested beta description:
> Learn and practice Morse code: a guided Koch journey, listening drills,
> short stories and serials, live QSO practice on Vail, sending practice —
> and new in this build, a live CW decoder: tap the waveform icon, point
> the mic at Morse audio (rig speaker, WebSDR), and read it as text.

Feedback email: jus.k.rog@gmail.com

## Then

Hand the join link to the remote session on this branch — it dispatches
the Discord announcement, deletes this file, and opens the PR for the
branch. (Or dispatch the workflow yourself with inputs `tag=v1.1`,
`testflight_link=<url>`.) The link resolves as soon as it exists; installs
start once beta review clears.
