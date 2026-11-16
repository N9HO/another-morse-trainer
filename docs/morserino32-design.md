# Morserino-32 Pocket input (#351)

Status: researched; the MIDI note-30 mapping and app-timed raw paddles
(#380) are built (see the update below). Nobody on the project has the hardware. This note records what the Morserino-32 Pocket ("M32P") actually exposes, why
it does not fit the hardware-key abstraction as it stands, and the smallest
piece worth building if the maintainer wants it.

Sources (read 2026-10-26): the Pocket user manual V9.x
(`https://github.com/oe1wkl/Morserino-32/releases/latest/download/Morserino-32_User_Manual_Pocket_EN.pdf`,
sections 6.3.1, 6.3.2, 7.6, 7.6.1, 7.7), and
`devdocs/ble-serial/{README.md,ACCESS_CONTROL.md,ble_m32_test.py}` in
`oe1wkl/Morserino-32`. **Not read:** the formal "M32 Protocol description" in
the repository's `Documentation/Protocol Description` folder (not reachable
from the research environment; the manual and the bench scripts point to it).
Everything below about the JSON half of the protocol is therefore second-hand.

## Update 2026-11-15: USB MIDI exists (supersedes parts of sections 1, 2 and 5)

The sections below were written from the V9.x manual and say the Pocket has
**no MIDI**. That predates `oe1wkl/Morserino-32` **PR #224, "M32 Pocket: USB
Output"** (merged 2026-10-29), which adds a *USB Output* preference to the CW
Keyer options: Off (charge) / VBand / MIDI (default).

- **MIDI mode**: the Pocket enumerates as a USB composite device (CDC serial,
  HID keyboard, MIDI). **Channel 1; dit = note 20, dah = note 21, straight key
  = note 30** (the Lynovation CTR2-MIDI convention). Paddles, straight key and
  keyer memories are sent, with Paddle Polarity applied first. Note 30 is sent
  only with the Pocket's keyer set to **Straight Key mode** ("Straight Key mode
  sends note 30 and nothing on 20 or 21"); in its paddle keyer modes it sends
  only the raw contacts on 20 and 21. Only active in CW Keyer mode. Serial
  stays active alongside.
- **VBand mode**: a USB keyboard that sends **Left Ctrl** while keyed. Not
  supported here (a single already-keyer-timed contact on a modifier key that
  the desktop handlers ignore and iOS/Android treat as a shortcut modifier).
- **No BLE MIDI.** The older BLE-MIDI PR (#189) is stalled. Over Bluetooth the
  Pocket still offers only the BLE keyboard and BLE serial described below, so
  the Generic Kbd advice in section 5 stays for Bluetooth users.

What is built (#351): note 30 maps to the straight contact in all three
parsers (`MIDIKeyParser` in Swift, `MidiKeyParser` in the Android and desktop
Kotlin trees), pinned by `fixtures/midi-key-notes.json`. Notes 20 and 21
already mapped to dit and dah, and every tree already opens any class-compliant
MIDI device, so a Pocket in MIDI mode should be seen and its notes parsed over
USB. That is the whole change: no setting, no new screen.

Caveats, all open:

- **Unverified on real hardware.** Nobody on the project has seen a Pocket
  enumerate, on Android, WinMM, ALSA or CoreMIDI; whether the MIDI interface of
  a CDC+HID+MIDI composite is exposed on each is an assumption. The note
  numbers come from the PR page, not the manual, and released firmware may
  differ.
- **Firmware availability: none released yet.** PR #224 adds USB Output to
  the **firmware 10.0** changelog and builds it only in new `*-usbio`
  environments (`pocketwroom-usbio`, `pocketwroom-lora-usbio`), so even a
  10.0 release may ship it as a separate build. The releases page (read
  2026-11-15) shows **V8.2** as Latest and **V9.0-beta.1 to beta.3** as
  pre-releases, no 10.x: no released firmware has USB Output. The PR author
  tested on V9.1 hardware; the V10 branch was built but not flashed at
  submission. Today a Pocket user has to self-flash master or VK1HF's
  unofficial "V9.1 + USB Output" build; a Pocket on stock firmware has no
  MIDI.
- **Raw paddles are iambic-timed by the app (#380).** `MidiKeyInput` (and
  iOS `MIDIKeyRouter`) used to hold one key while *any* note was down, so a
  raw paddle keyed like a straight key and a squeeze was one long tone. Raw
  dit/dah contacts now go into `PaddleKeyerDriver` (the on-screen paddles'
  keyer) at the Settings paddle mode and speed; the routing is
  `MidiKeyParser.routeForNote` / `MIDIKeyParser.route`, pinned by
  `fixtures/midi-key-routing.json`. 20/21 (TinyMIDI, Pocket) and 61/62 always
  route to the keyer. 1/2 route to it **only with the adapter Keyer mode set
  to Passthrough**: Vail Adapter firmware from v4.4 (commit 5448935,
  2025-09-23) sends its own keyer-timed elements on notes 1 (dit) and 2 (dah)
  rather than note 0 (the MIDI spec still says 0), so in a keyer mode 1/2
  stay held keys, exactly as before. Pocket keyer-memory playback on 20/21 is
  re-timed by the app's keyer; at a much faster app speed than the Pocket's a
  memory dah can come out as two. Untested on hardware.
- A synth sending note 30 now keys. The parser was already this permissive for
  notes 0, 1, 2, 20, 21, 61 and 62.
- The wake sequence (Vail CCs) is sent to every MIDI output device, including a
  Pocket; the effect is unknown and expected to be ignored.

Guide wording (the guide is outside this repo): "Morserino-32 Pocket: with
firmware that has *USB Output* (not in any released firmware yet; slated for
10.0), set it to MIDI in the CW Keyer options, set the keyer to Straight Key
mode for a straight key, or a paddle keyer mode for a paddle, and use a USB
cable. A paddle's contacts are timed by the app's own keyer, in the Paddle
mode under Settings › On-screen key, at your practice speed. Untested by the
project."

## 1. What the device exposes

| Interface | What it carries | Setting on the device |
|---|---|---|
| USB-C, serial (115200 baud) | A text stream: keyed characters (output of the device's own iambic keyer), decoded characters, generated characters, selected by *Serial Output*; plus the **M32 Serial Protocol**, a two-way line protocol (`PUT`/`GET` lines in, JSON objects out) for settings, menus and sending text to be keyed. | *Serial Output*: Nothing / Keyer / Decoded / Keyed+Decoded / Generated / All (default All). For the protocol: a client sends `PUT device/protocol/on`. |
| BLE "serial" (Nordic UART service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`; RX `...0002...` write, TX `...0003...` notify; advertises as `Morserino-32`) | The same stream and protocol as USB, over BLE. Notifications carry JSON objects interleaved with raw character echo. | *Bluetooth Use* = **BLE Serial**, then return to the main menu. Needs consent on the device: after `PUT device/protocol/on` the M32 shows "Allow connect? FN = yes", only at the main menu, 20 s timeout, a client back within 60 s is admitted unasked. Not available while WiFi modes are active. |
| BLE HID keyboard | Decoded characters typed as a US-layout keyboard. Accents are dropped (ä → ae). `<KA>` is Enter and `<HH>` Backspace in Generic Kbd mode. | *Bluetooth Use* = VBand Kbd / Decoded output / VBand+Decoded / Generic Kbd. **Only active in CW Keyer mode.** Cannot coexist with BLE Serial. |
| MIDI | **None in the V9.x manual and the firmware it describes.** USB MIDI was added by PR #224 (see the update above); BLE MIDI does not exist. | *USB Output* = MIDI |

The classic Morserino-32 and the Pocket share the protocol. Which USB-serial
chip the Pocket uses (and so whether Windows needs a driver and Android sees it
as CDC-ACM) was **not established** from the sources.

## 2. What it does not expose

**Neither the serial stream nor the keyboard carries paddle state.** What
comes out is characters, after the device's own keyer has timed and decoded
them. There is no dit/dah/key-down/key-up event and no timestamp, in any
interface the documentation describes. A BLE keyboard is also invisible to the
app as anything but typed text (and iOS and Android route it to the focused
field).

This matters because AMT's hardware-key abstraction
(`MidiKeyMessage` / `MidiKeyInput` / `HardwareKey`, and the Swift and desktop
twins) is a stream of key-down and key-up events on a dit, dah or straight
contact, from which the in-app keyer, the sending analyser and the speed
estimator work. Characters cannot feed it. Using the M32P as a *keyer* in AMT
would need a different input: "the device already keyed it, here is the
letter", and a lot of AMT's sending analysis (timing, weighting, spacing)
would have nothing to analyse.

## 3. Platform constraints

- **iOS / iPadOS / Mac Catalyst.** USB serial: no (iPadOS can use DriverKit
  serial drivers only if the vendor ships one; nothing known for M32). BLE:
  CoreBluetooth works, and the manual says outright that "on iOS apps cannot
  open a classic serial connection over Bluetooth at all, while BLE works
  fine", which is why BLE Serial exists. Needs `NSBluetoothAlwaysUsageDescription`
  (the app already has BLE MIDI paths through the system sheet; CoreBluetooth
  scanning would be new).
- **Android.** BLE: `BluetoothLeScanner` plus GATT notifications, permissions
  already declared for BLE MIDI scanning. USB serial: `UsbManager` host mode,
  needing a CDC-ACM implementation (a vendor driver library for non-CDC
  chips; platform CDC-ACM is not exposed as a stream by `UsbManager`, so this
  is a new dependency or a few hundred lines of USB code).
- **Desktop JVM (Windows, Linux).** USB serial needs a JVM serial library (jSerialComm
  or similar: a native-library dependency, new Windows/Linux packaging and
  Flatpak device-permission work). BLE is not reachable: the same limit
  `PARITY.md` already records for BLE MIDI.
- **Common.** The BLE route needs a physical button press on the device per
  new client, and the M32P must be at the main menu. Neither is hard, but both
  must be explained in the UI. While a protocol session is open the M32 will
  not auto-power-off.

## 4. Options

1. **BLE Serial, listen only ("Morserino as a character source").** One
   transport that exists on iOS and Android and none on desktop: a parity
   exception. Delivers decoded/keyed characters to AMT's *typed-answer* path
   (copy-by-sending quizzes) and nothing else. Needs CoreBluetooth and
   Android GATT code that cannot be verified here.
2. **USB serial, listen only.** Desktop and Android only; needs a serial
   library on desktop and a CDC driver on Android; not iOS. Same limits.
3. **Keyboard mode.** Zero AMT code: the M32P is a Bluetooth keyboard, and AMT
   already accepts typed answers and has a keyboard path for keyed screens on
   desktop. Works today on any platform whose OS can pair a BLE keyboard. The
   cost is that it is only active in CW Keyer mode and gives no timing.
4. **Do nothing.** The Pocket's own trainer modes are the point of the device.

## 5. Recommendation

Do **not** add a driver now, and do not describe the M32P as a supported key.
It cannot be a hardware key in AMT's sense because it never sends paddle
events, and every transport needs per-platform code nobody can test.

What to tell users today (guide and README, no code): set *Bluetooth Use* to
**Generic Kbd** and pair the Morserino as a Bluetooth keyboard; its keyed
characters then arrive as typed text in any AMT answer field. This is
untested by the project and should be worded as "should work".

If the maintainer wants first-class support later, the order is: (a) the M32
stream framer as a pure function in each tree's `morsekit` (below), fixture
pinned; (b) one transport on one platform with a tester in hand (Android BLE,
since GATT notifications with a known service UUID is the smallest surface);
(c) a typed-answer source for it; (d) the others, with the desktop BLE gap
written into `PARITY.md`.

## 6. The one portable piece: stream framing

The BLE test script frames the device's byte stream like this (identical for
USB per the same script): bytes outside a JSON object are raw character echo;
a `{` at depth 0 starts an object; braces nest; string literals and backslash
escapes are respected; an object ends when depth returns to 0; unparseable
objects are dropped. Commands are lines ending `\n`, several may share one
GATT write, and the device answers one line per poll.

That is a pure function (bytes in; echo text and JSON objects out) and could be
fixture-pinned in all three trees. **It was not written**, for two reasons:
the only description is a script the repository itself marks as stale for
the handshake (it predates the consent step, though not the framing), and the
formal protocol document, which should define the echo/JSON boundary, the
`PUT device/protocol/on` reply and the chunking rules, could not be read. A
framer written from a stale script would be a guess that CI would pass and a
device might not.

## 7. What is left, exactly

Needs the maintainer:
- A decision whether first-class M32P support is wanted at all, given section 2.
- Someone with an M32P to confirm: which USB-serial chip the Pocket uses, that
  Generic Kbd mode works as a typed-answer source on iOS, Android and desktop,
  and what the Allow connect flow looks like from an app.

Needs reading, no hardware: the repository's `Documentation/Protocol
Description` document, to write and fixture-pin the framer and the echo
semantics.

Needs hardware and per-platform code: every transport in section 4.

## 8. Guide and README notes (when this is decided)

If the keyboard-mode advice is adopted: Settings and the guide's hardware-key
section need a short "Morserino-32" paragraph (it is not a key; here is the
keyboard-mode workaround); the three READMEs list it in the same words.
