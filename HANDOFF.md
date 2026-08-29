# Handoff: upload build 20 to TestFlight (Mac, any clone)

Delete this file (its own commit) once build 20 is distributed.

## What build 20 is

A partial call gets an answer again (#85).

In the QSO simulator's pileup, a partial query only matched stations
whose call *started* with what you sent. A partial is whatever fragment
you managed to copy, and it is rarely the front: two stations landing on
top of each other leave you a letter from the end, and querying the
middle is ordinary contest practice — "9H?" is how you ask N9HO to come
back. Every one of those fell through to the busted-call path, so on the
default silence setting the pileup simply ignored you, and it cost you a
bust on your accuracy besides.

Any station whose call contains the fragment now comes back. An exact
full call still goes straight to the exchange, and a fragment nobody
contains is still a bust.

The Android repo carries the same change.

`CURRENT_PROJECT_VERSION` is already bumped to 20 (marketing version
stays 1.1). Nothing to edit — just build and ship.

## Ship it

    git checkout main && git pull
    source tools/asc-auth.sh
    ./tools/upload-testflight.sh
