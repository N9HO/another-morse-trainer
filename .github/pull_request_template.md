<!--
Summary first: what changed and why, written for someone who has not read
the diff. If this closes an issue, say "Closes #N" so the close notifier can
tell the Discord reporter what changed.
-->

## Parity

All three apps ship the same behaviour — see [PARITY.md](https://github.com/N9HO/another-morse-trainer/blob/main/PARITY.md).
Tick exactly one. The merge gate reads this section on any pull request that
touches some but not all of `ios/`, `android/` and `desktop/`.

- [ ] **All platforms** (iOS, Android, desktop) are in this pull request.
- [ ] **Paired issue** — not every platform is here; the missing side(s) are tracked in #
- [ ] **Platform limitation** — the missing platform(s) cannot do this; the exception is added to `PARITY.md` in this pull request.
- [ ] **Platform-internal** — no user-visible behaviour changes (build, CI, lint, refactor, version bump, crash fix in platform-only code).

The **Parity check** job (`python3 parity/check.py`) runs on every pull request
that touches a tree, `fixtures/`, `parity/` or `PARITY.md`, and the merge gate
waits for it. It fails on a mode, screen, setting, option list or README
heading that differs between the apps without a record in
`parity/manifest.json`. If this pull request adds or changes a feature, its
manifest entry is part of the change (the three steps are in `CLAUDE.md`);
there is nothing to tick.


## Guide

The user guide at [anothermorsetrainer.app/guide](https://anothermorsetrainer.app/guide/)
describes every mode and setting, and it changes in the same release as the
app (#172). Tick one. The merge gate reads this on any pull request that
changes app code without ticking "Platform-internal" above.

- [ ] **Guide updated** — the section(s) covering what changed are updated; say which:
- [ ] **No guide change needed** — nothing the guide describes changed; say why:

## Verified how

<!--
Which of the checks in CLAUDE.md ran, and where. Android and desktop are
verified only by CI; say which job, and for a new test, that the negative
control named it.
-->
