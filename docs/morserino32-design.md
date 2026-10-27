# Morserino-32 Pocket input (#351)

Status: researched, **not built**. Nobody on the project has the hardware, and
the one interface that would carry paddle timing does not exist on the device.
This note records what the Morserino-32 Pocket ("M32P") actually exposes, why
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

## 1. What the device exposes

| Interface | What it carries | Setting on the device |
|---|---|---|
| USB-C, serial (115200 baud) | A text stream: keyed characters (output of the device's own iambic keyer), decoded characters, generated characters, selected by *Serial Output*; plus the **M32 Serial Protocol**, a two-way line protocol (`PUT`/`GET` lines in, JSON objects out) for settings, menus and sending text to be keyed. | *Serial Output*: Nothing / Keyer / Decoded / Keyed+Decoded / Generated / All (default All). For the protocol: a client sends `PUT device/protocol/on`. |
| BLE "serial" (Nordic UART service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`; RX `...0002...` write, TX `...0003...` notify; advertises as `Morserino-32`) | The same stream and protocol as USB, over BLE. Notifications carry JSON objects interleaved with raw character echo. | *Bluetooth Use* = **BLE Serial**, then return to the main menu. Needs consent on the device: after `PUT device/protocol/on` the M32 shows "Allow connect? FN = yes", only at the main menu, 20 s timeout, a client back within 60 s is admitted unasked. Not available while WiFi modes are active. |
| BLE HID keyboard | Decoded characters typed as a US-layout keyboard. Accents are dropped (ä → ae). `<KA>` is Enter and `<HH>` Backspace in Generic Kbd mode. | *Bluetooth Use* = VBand Kbd / Decoded output / VBand+Decoded / Generic Kbd. **Only active in CW Keyer mode.** Cannot coexist with BLE Serial. |
| MIDI | **None.** The manual and the firmware notes mention no MIDI interface, USB or BLE. | — |

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
