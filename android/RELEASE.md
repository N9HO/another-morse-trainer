# Releasing Another Morse Trainer to Google Play

## ⚠️ FIRST — back up your signing key (do this now)

Your app's identity on Google Play is the upload keystore. **If you lose it (or its
password) you can never publish an update to this listing.** Back up both, somewhere
safe (password manager + a second location):

- Keystore: `~/.android-keystores/amt-upload.jks`
- Credentials: `MorseTrainerAndroid/keystore.properties` (password, alias)

Neither is committed to git (`.gitignore` excludes them). The upload cert SHA-256 is:
`57:97:26:3B:2E:3D:3A:AC:84:51:68:0E:97:25:F3:61:86:63:CF:FA:20:31:30:E8:95:CE:F6:B7:85:5F:EB:BC`

> With Google Play App Signing (recommended, default for new apps) this is your
> *upload* key — Google holds the final app-signing key — but you still must keep
> this upload key to push updates.

## What's already set up (done for you)

- Release signing config in `app/build.gradle.kts` (reads `keystore.properties`).
- `versionCode = 1`, `versionName = "1.0"`.
- A **signed release AAB** at:
  `app/build/outputs/bundle/release/app-release.aab`

## Build a release AAB (repeat for every update)

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"   # was Android Studio's JBR
cd ~/MorseTrainerAndroid
./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

For each update **bump `versionCode`** (2, 3, …) — Play rejects a re-used code —
and usually `versionName` ("1.0.1", "1.1", …).

## Automated releases (GitHub Actions) — the iOS-parity bit

Two workflows are committed under `.github/workflows/`:

- **`android-ci.yml`** — builds the debug APK on every push/PR and attaches it as
  an artifact, so changes made from the Claude phone app can be verified without a
  laptop (the Android counterpart of the iOS repo's `ios.yml`).
- **`android-release.yml`** — on a pushed version tag (`v*`), builds the **signed
  AAB and uploads it to the Play _closed testing_ (beta) track** (`track: alpha`
  by default — change it if your closed track has a different id). This automates
  the step iOS still does by hand (Xcode → TestFlight). Cut a release with:

  ```bash
  # bump versionCode/versionName in app/build.gradle.kts first, commit, then:
  git tag v1.0.1 && git push origin v1.0.1
  ```

  Until the secrets below are set it degrades gracefully — it just builds the AAB
  and uploads it as a workflow artifact instead of failing.

### One-time setup to enable the automated upload

1. **The app must already exist in Play Console** with **one manual upload** on the
   target track (the API won't create the app or accept the very first upload). The
   app is already in **closed testing**, so this is satisfied.
2. **Create a Google Play service account** (Play Console → Setup → API access →
   create/link a Google Cloud service account → grant it **Release manager**), and
   download its **JSON key**.
3. **Add GitHub repository secrets** (Settings → Secrets and variables → Actions):
   - `ANDROID_KEYSTORE_BASE64` = `base64 -i ~/.android-keystores/amt-upload.jks`
   - `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`amt-upload`), `ANDROID_KEY_PASSWORD`
     (the values from `keystore.properties`)
   - `PLAY_SERVICE_ACCOUNT_JSON` = the full contents of the service-account JSON

   The release workflow recreates `keystore.properties` + the keystore from these at
   build time (nothing secret is committed). To upload to a different track, change
   `track: internal` in `android-release.yml`.

## Your side — Google Play Console (one-time)

1. **Create a Play Console account** — https://play.google.com/console — one-time
   USD $25. Use a Google account you'll keep.
2. **Create app** → name "Another Morse Trainer", language, "App", "Free".
3. **App signing** — accept Google Play App Signing (default). Upload this AAB and
   Play generates the signing key; your `amt-upload.jks` stays the upload key.
4. **Start with the Internal testing track** (fastest, no review wait) so you can
   sideload-equivalent test on your tablet via the Play install link before going
   public. Then promote to Closed/Open testing → Production.
5. **Upload** `app-release.aab` to the chosen track, add release notes, roll out.

## Store listing assets you'll need to provide

- **App icon**: 512×512 PNG (32-bit, with alpha). *(I can render this from the
  in-app adaptive icon if you want.)*
- **Feature graphic**: 1024×500 PNG/JPG.
- **Phone screenshots**: 2–8, 16:9 or 9:16. *(We have plenty from the emulator —
  Home, a quiz, Progress with the chart, Settings, Listen & Learn — I can collect
  clean ones.)*
- **Short description** (≤80 chars) and **full description**.
- **Privacy policy URL** — **required** because the app requests the microphone.
  You own `anothermorsetrainer.app`, so host one there (e.g.
  `anothermorsetrainer.app/privacy`).

## Required questionnaires (Play Console forms)

- **Data safety**: the app stores settings/stats **locally only** (SharedPreferences),
  no account, no analytics. The microphone (`RECORD_AUDIO`) is used **only** for the
  optional "Voice answers" feature; note that Android's `SpeechRecognizer` may send
  audio to the device's recognition provider (e.g. Google) to transcribe — disclose
  that the mic is used for app functionality and not stored by the app.
- **Content rating**: questionnaire → will rate Everyone.
- **Target audience / ads**: no ads; choose your audience (not directed at children
  unless you intend COPPA handling).
- **Permissions**: `VIBRATE`, `POST_NOTIFICATIONS` (reminders), `RECORD_AUDIO` (voice
  answers), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (Listen &
  Learn background playback). No special declaration form needed for these.

## Notes / optional polish before 1.0

- `isMinifyEnabled = false` — fine to ship; enabling R8 later shrinks the app.
- Real-hardware checks still pending on the tablet: voice-recognition accuracy and
  Listen & Learn lock-screen playback.
