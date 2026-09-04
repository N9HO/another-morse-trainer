<!--
Summary first: what changed and why, written for someone who has not read
the diff. If this closes an issue, say "Closes #N" so the close notifier can
tell the Discord reporter what changed.
-->

## Parity

Both apps ship the same behaviour — see [PARITY.md](https://github.com/N9HO/another-morse-trainer/blob/main/PARITY.md).
Tick exactly one. The merge gate reads this section on any pull request that
touches only one of `ios/` and `android/`.

- [ ] **Both platforms** are in this pull request.
- [ ] **Paired issue** — this is one platform; the other side is tracked in #
- [ ] **Platform limitation** — the other platform cannot do this; the exception is added to `PARITY.md` in this pull request.
- [ ] **Platform-internal** — no user-visible behaviour changes (build, CI, lint, refactor, version bump, crash fix in platform-only code).

## Verified how

<!--
Which of the checks in CLAUDE.md ran, and where. Android is verified only by
CI; say which job, and for a new test, that the negative control named it.
-->
