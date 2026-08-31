# Handoff: upload build 24 to TestFlight (Mac, any clone)

Delete this file (its own commit) once build 24 is distributed.

Build 24 supersedes builds 22 and 23. Build 22's handoff was never
removed and build 23 was bumped without one, so neither appears to have
been uploaded — if either in fact was, no harm: everything they carried
ships here too, and is repeated below.

## What build 24 is

An iambic paddle stays iambic, wherever you use it.

- **The Vail Adapter no longer resets your paddle to a straight key
  (#43).** The adapter boots in HID keyboard mode and sends no MIDI until
  it receives a Control Change, so waking it necessarily asserts a keyer
  mode — and every screen asserted Straight Key, because the only control
  that set it lived on the Vail screen. That is why a paddle configured
  at vailmorse.com reverted the moment the app woke the adapter, and why
  there was no way to ask for iambic without visiting the repeater. The
  mode now has one home behind Settings, the repeater and Sending
  Practice, so setting a paddle once makes it a paddle everywhere. The
  wake still asserts a mode; it asserts yours
- **Reported against Android, fixed here too.** The iOS app had the same
  shape and the same bug, so it was fixed on both rather than only where
  it was noticed

...and what builds 22 and 23 carried:

- **Sending Practice wakes the adapter (#91).** It opened MIDI input
  alone, so it enumerated the adapter, named it, and then waited on a
  device that had never been told to start sending
- **The Bluetooth MIDI browser stays put (#91).** It hung off the "no key
  connected" branch, so the view presenting it was torn out of the
  hierarchy the moment a key connected — which is exactly what happens
  while the browser is open, since connecting there is what makes CoreMIDI
  see the key at all
- **The session-end exit is named for where it lands (#90).** "Change
  setup" read as a settings screen; it now reads "Return home"
- **A keep-alive noise floor you can't hear (#92).** Keeping the Bluetooth
  link awake and the audible noise level were the same knob, so silencing
  the hiss meant giving up the fix it exists for. Keep-alive is now its
  own level below Whisper — non-zero PCM, ~56 dB under the tone — and the
  new default, with a one-time migration off Whisper
- **The QSO exchange takes what operators actually send (#38).** A signal
  report in front of the exchange is not a miscopy, and cut numbers count
  wherever a digit is expected
- **A near miss keeps the caller correcting you**, two callers you might
  mean both come back, a send can carry your exchange, the pileup has
  human timing, the run says who got away, and a partial call gets an
  answer again (#85)

`CURRENT_PROJECT_VERSION` is already bumped to 24 (marketing version
stays 1.1). Nothing to edit — just build and ship.

CI built this commit with `xcodebuild` on a macOS runner and ran the
`MorseKitCheck` harness green, so the archive should be uneventful.

## Ship it

    git checkout main && git pull
    cd ios
    source tools/asc-auth.sh
    ./tools/upload-testflight.sh
