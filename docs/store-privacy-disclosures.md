# Store privacy disclosures: shared leaderboard and optional account

The answers to enter in App Store Connect (App Privacy) and Play Console
(Data safety) for the release that ships the shared leaderboard
(docs/high-scores-design.md, step 2). Written 2026-09-09 against the client
in PR #217 and the server's README; if either changes what is sent, change
this and the forms together. The public privacy policy at
anothermorsetrainer.app/privacy has the matching plain-English section.

## What the app actually sends, and when

Nothing, until the user turns on **Settings › Leaderboard › Share scores to
the shared leaderboard** (off by default). Then, per finished run in a
ranked mode, to `amt-leaderboard.n9ho.workers.dev` (Cloudflare):

| Data | Details | Kept |
|---|---|---|
| Display name | 2–12 chars the user typed; shown publicly with their scores | While on the board |
| Run transcript | per item: text sent in Morse, text answered, reaction time, speed | 30 days (raw), then deleted; best score per mode kept |
| Device attestation | iOS: App Attest key id + assertion (Apple verifies genuineness); Android: Play Integrity token (Google verifies) + a hash of a random per-install id | The key id / hashed install id is the entry's identity, kept while on the board |
| Platform, date | "ios"/"android", submission date | While on the board |

Not sent: settings, practice history, location, contacts, any account,
anything identifying the person. No tracking, no ads, no analytics.
Deletion: in-app **Delete my scores** removes everything server-side for
that device at once; also by email. Browsing the board sends nothing.

### Buddy streak (#219, several buddies #237)

Its own opt-in (inviting or joining), not the Share scores switch. Once
paired, to the same Worker: the display name (shown to buddies), the same
device attestation, the pairing links (at most ten per install, #237) and,
once per local day practised, that day as `yyyy-mm-dd`. Buddies see each
other's display name and whether each practised that day, nothing else.
Delete my scores removes every pairing, invite and practice day. These are
the same three data types declared below (identifier, display name, app
interactions); several buddies adds rows, not kinds of data, so neither
store's answers change for #237.

### Optional account and sync (PR #337)

Its own opt-in: nothing is sent until the user signs in under **Settings ›
Account & Sync** (signed out by default). The service is a separate Worker,
`amt-accounts.n9ho-amt.workers.dev` (repository
`N9HO/another-morse-trainer-accounts`, whose README section 12 lists exactly
what it stores). Once signed in:

| Data | Details | Kept |
|---|---|---|
| Email address | typed to sign in; the sign-in link is emailed to it (via Resend) | until the account is deleted |
| Account id | a random id the server assigns | until the account is deleted |
| Callsign, display name | optional, typed by the user; shown only to the user and to apps they let read their stats | until cleared or the account is deleted |
| Device records | app name, the device's own name, platform, sign-in and last-seen times | until signed out (pruned 30 days later) or deleted |
| Training sessions | the app's session record: date, mode, speeds, attempts, correct, recognition times, duration, score, per-character results | until the account is deleted |
| Practice days | per device, local calendar day and seconds practised | newest 400 days; until deleted |
| Course progress | Journey position, Characters ladder position, First Four and Operating Procedure progress, story bookmarks | until the account is deleted |

Unlike the leaderboard, this data **is linked to the user**: it exists to
follow one person across their devices. It is not public, not used for
tracking or ads, and not shared, except with the processors that run it
(Cloudflare as host, Resend for the sign-in email). Sessions are
self-reported and never feed the public board. Deletion: in-app **Delete
account** (Settings › Account & Sync) removes the account and every synced
row at once; also by email. Sign out only removes this device's sign-in.

## App Store Connect › App Privacy

Answer **Yes, we collect data from this app**, then declare five data types.
"Used for tracking" is **No** for all five. "Linked to the user's identity"
is **No** for the leaderboard's per-install data, but **Yes** wherever the
optional account also collects the type, because Apple asks per data type
and an account links it to a person.

| Data type | Category | Purpose(s) | Linked to user | Tracking | Collected by |
|---|---|---|---|---|---|
| Email Address | Contact Info | App Functionality | Yes | No | account |
| User ID | Identifiers | App Functionality | Yes | No | account |
| Device ID | Identifiers | App Functionality | No | No | leaderboard |
| Other User Content | User Content | App Functionality | Yes | No | leaderboard (display name, unlinked); account (callsign and display name, linked) |
| Product Interaction | Usage Data | App Functionality | Yes | No | leaderboard (run transcript, unlinked); account (sessions, practice days, progress, linked) |

Apple also requires in-app account deletion for any app that lets users
create an account: Settings › Account & Sync › **Delete account** does it
(server delete, then the device signs out). Mention it in the review notes.

Notes for the reviewer field, if asked: "Optional account, signed out by
default: sign-in is by an emailed link, it syncs the user's own training
progress between their devices, and Settings › Account & Sync › Delete
account deletes it. Optional leaderboard, off by
default. Device ID = the App Attest key identifier for this install, used
only to keep one best score per device and to let the user delete it. Other
User Content = a display name the user chooses. Product Interaction = the
Morse copying transcript of a run, graded server-side. Nothing is linked to
an account and nothing is used for tracking or advertising."

Also in App Store Connect: the **Privacy Policy URL** stays
`https://anothermorsetrainer.app/privacy/`; make sure that page's effective
date is updated in the same release (site PR #17).

## Play Console › App content › Data safety

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data collected by your app encrypted in transit?** Yes
(HTTPS only). **Do you provide a way for users to request that their data is
deleted?** Yes — in-app "Delete my scores" and by email; the privacy policy
describes both, and "Delete account" covers the account.

**Account creation**: the app lets users create an account (optional), by a
passwordless emailed link. Play requires two deletion paths for apps with
accounts: in the app (Settings › Account & Sync › Delete account) **and a web
link** where a user can ask for deletion without the app. That web page does
not exist yet: it needs a page on anothermorsetrainer.app (for example
`/delete-account/`) saying how to delete in the app and giving the email to
ask for deletion, and its URL entered in Play Console before release.

Declare four data types, each **Collected**, **not shared**, **Optional**
(users choose whether it is collected: each feature is opt-in), not
processed ephemerally:

| Data type | Group | Purposes |
|---|---|---|
| Email address | Personal info | App functionality; Account management |
| Device or other IDs | Device or other IDs | App functionality; Fraud prevention, security, and compliance |
| User IDs | Personal info | App functionality; Account management |
| App interactions | App activity | App functionality |

- **Email address**: typed to sign in to the optional account; the sign-in
  link is sent to it.

- **Device or other IDs**: the hash of a random per-install identifier the
  app generates, plus what the Play Integrity token carries to Google for
  verification. "Fraud prevention, security, and compliance" is the honest
  second purpose: the attestation exists to keep fabricated scores off the
  board.
- **User IDs**: the account id, callsign and display name of the optional
  account, and the leaderboard display name. It is a pseudonymous handle the user
  types, shown publicly on the board; Google's "User IDs" is the closest
  category ("identifiers that relate to an identifiable person, such as an
  account name"). It is not a real name unless the user makes it one.
- **App interactions**: the leaderboard run transcript and score, and the
  account's synced sessions, practice days and course progress.

Play Console may also surface a **Play Integrity** entry from the Google
Play SDK Index with its own suggested data-safety lines; accept Google's
suggestion for the SDK if it appears, it is consistent with the above.

## Both stores

- The leaderboard switch is off by default and the account is signed out
  by default; nothing is sent before the user turns one on, so first-launch
  behaviour is unchanged and needs no consent dialog beyond the switch, the
  sign-in screen and their notes.
- No data is sold, no data is used for advertising or tracking, no data is
  shared with third parties other than the processors named in the policy
  (Cloudflare as host; Apple and Google as attestation verifiers; Resend
  for the account's sign-in email).
- Children: the app is for all ages; the display name is user-chosen and
  public, and the policy says not to use a name they would not want public.
