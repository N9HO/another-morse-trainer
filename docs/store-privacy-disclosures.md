# Store privacy disclosures for the shared leaderboard

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

### Account sync (iOS/Mac first; Android and desktop to follow)

Its own opt-in: signing in under **Settings › Leaderboard & Buddy ›
Account** (nothing is sent before). To `amt-accounts.n9ho-amt.workers.dev`
(Cloudflare), the same operator as the leaderboard:

| Data | Details | Kept |
|---|---|---|
| Email address | typed by the user to receive the sign-in link; the app keeps it only to show who is signed in | By the account service, as the account's address, until account deletion |
| Device name, platform | the device's name (as iOS reports it) and "ios"/"ipados"/"macos", shown in the sign-in email and the account's devices list | While the device is signed in (the row is pruned 30 days after sign-out) |
| Session records | per finished session: mode, speeds, attempts, correct, reaction times, duration, the mode's score, per-character results, active character set — the same record Your Stats shows | Until account deletion |
| Practice days | this device's local calendar day and whole seconds practised, for the streak and the activity calendar | Newest 400 days; account deletion |

The accounts service's README (§12, "Privacy: exactly what is stored")
lists every row it keeps and the store answers it calls for; this table
is the app's side of the same list.

Unlike the leaderboard, this IS linked to an account the user created, so
the App Store answers below change for the release that ships it: **Email
Address** (Contact Info, App Functionality, linked: Yes) is added, and
**Product Interaction** becomes linked: Yes. "Used for tracking" stays No
throughout. Sign out removes the device from the account; the account
itself, and everything it holds, is deleted from the account service (its
own screens and by email), which the privacy policy must say.

## App Store Connect › App Privacy

Answer **Yes, we collect data from this app**, then declare three data types.
"Linked to the user's identity" is **No** for all three (no account; the key
id is per install and the app never has an Apple ID, name or email).
"Used for tracking" is **No** for all three.

| Data type | Category | Purpose(s) | Linked to user | Tracking |
|---|---|---|---|---|
| Device ID | Identifiers | App Functionality | No | No |
| Other User Content | User Content | App Functionality | No | No |
| Product Interaction | Usage Data | App Functionality | No | No |

Notes for the reviewer field, if asked: "Optional leaderboard, off by
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
describes both. (There are no accounts, so the account-deletion questions
do not apply.)

Declare three data types, each **Collected**, **not shared**, **Optional**
(users can choose whether it is collected: the feature is opt-in), not
processed ephemerally:

| Data type | Group | Purposes |
|---|---|---|
| Device or other IDs | Device or other IDs | App functionality; Fraud prevention, security, and compliance |
| User IDs | Personal info | App functionality |
| App interactions | App activity | App functionality |

- **Device or other IDs**: the hash of a random per-install identifier the
  app generates, plus what the Play Integrity token carries to Google for
  verification. "Fraud prevention, security, and compliance" is the honest
  second purpose: the attestation exists to keep fabricated scores off the
  board.
- **User IDs**: the display name. It is a pseudonymous handle the user
  types, shown publicly on the board; Google's "User IDs" is the closest
  category ("identifiers that relate to an identifiable person, such as an
  account name"). It is not a real name unless the user makes it one.
- **App interactions**: the run transcript and the resulting score.

Play Console may also surface a **Play Integrity** entry from the Google
Play SDK Index with its own suggested data-safety lines; accept Google's
suggestion for the SDK if it appears, it is consistent with the above.

## Both stores

- The switch is off by default and nothing is sent before it is turned on,
  so first-launch behaviour is unchanged and needs no consent dialog beyond
  the switch and its footnote.
- No data is sold, no data is used for advertising or tracking, no data is
  shared with third parties other than the processors named in the policy
  (Cloudflare as host; Apple and Google as attestation verifiers).
- Children: the app is for all ages; the display name is user-chosen and
  public, and the policy says not to use a name they would not want public.
