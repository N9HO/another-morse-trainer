# Handoff: upload build 22 to TestFlight (Mac, any clone)

Delete this file (its own commit) once build 22 is distributed.

Build 22 supersedes build 21, which was never uploaded — everything in
that handoff ships here too, and is repeated below.

## What build 22 is

The QSO simulator's exchange takes what operators actually send.

- **A signal report in front of the exchange is not a miscopy (#38).**
  Type "5NN OH" and the copy grades, in every contest mode — SST, CWT,
  MST, Sprint and Field Day send a bare name, serial or class, but an
  operator sends 5NN out of muscle memory anyway, and the pileup used to
  charge that as a bust and repeat itself forever. Write it 5NN or 599,
  spaced or run together ("5NNOH"); it reads the same. A serial that
  merely looks like a report is still graded, and if you opted into
  copying the report it is still required
- **Cut numbers count wherever a digit is expected (#38).** Field Day's
  class mixes a number and a category letter, so a cut copy of 9B is
  "NB" — which the class comparison rejected outright. A digit position
  now takes its cut letter; a letter position still has to match, so
  "9U" remains wrong

...and what build 21 carried:

- **A near miss keeps the caller correcting you.** Copy a call almost
  right — "N9HS" for N9HO — and the pileup used to treat it as a total
  bust: silence on the strict setting. On the air the station sends its
  own call again and keeps sending it until you get it right, so now it
  does. Still a miscopy: it counts as a bust and spends the station's
  patience, so a caller you never resolve eventually walks
- **Two callers you might mean both come back.** If your copy fits two
  calls, neither of them knows whether you meant them, so both answer —
  hanging off the beat by different amounts while they work out whether
  you were coming back to them. Neither gives up its exchange until you
  actually name one, so you still cannot work a station you never copied
- **A send can carry your exchange.** "N9HS 5NN AL" was squashed into one
  token, so a call with an exchange behind it matched nothing — even when
  the call was right
- **The pileup has human timing.** Each operator now has their own
  reaction time, drawn once, so the same op reads as consistently quick
  or consistently hesitant across a run, with fresh jitter so no two
  rounds land identically. The exchange was a hard-coded 0.2 seconds for
  every station on every contact; it is now quicker than a call but no
  longer metronomic
- **The run says who got away.** A caller who gives up is recorded with
  the real call, what you had them as, and how many times they came back.
  A new QSO realism setting — shown only when callers can give up —
  picks when you hear it: "At the end" (default) in the run summary,
  "As it happens" also raises a dismissible card, "Off" stays quiet
- **A partial call gets an answer again (#85).** A partial query only
  matched calls that *started* with the fragment, so a trailing "B?" —
  what two stations on top of each other leave you — matched nobody, fell
  to the busted-call path, and on the silence setting gave no reply at
  all while charging a bust against clean-copy accuracy. Any station
  whose call contains the fragment now answers

`CURRENT_PROJECT_VERSION` is already bumped to 22 (marketing version
stays 1.1). Nothing to edit — just build and ship.

CI built this commit with `xcodebuild` on a macOS runner and ran the
`MorseKitCheck` harness green, so the archive should be uneventful.

## Ship it

    git checkout main && git pull
    source tools/asc-auth.sh
    ./tools/upload-testflight.sh
