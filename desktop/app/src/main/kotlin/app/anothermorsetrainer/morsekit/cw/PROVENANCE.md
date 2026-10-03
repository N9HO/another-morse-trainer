# morsekit/cw — CW decoder core (Kotlin port)

This package holds the CW audio decoder core: a decimating FIR front end,
pitch-search prepass, 3-bin Goertzel with dominance validation, adaptive
noise floor with hysteresis, and Mills Ratio-Weighted Estimation timing.

It is a **line-faithful Kotlin port** of the C99 core vendored into the iOS
app at `another-morse-trainer/Sources/CWDecoderCore`, which is itself
byte-identical to the Carrier Wave bridge firmware's host-tested copy at
`github.com/CarrierWaveApp/carrier_wave` commit
`b00268c9dae2226d7ff822afa7ce468b24bd013d` (branch `cw-decode-usb`,
directory `firmware/src/cw/`):

| Kotlin file    | C source      | Upstream SHA-256 of the C file |
|----------------|---------------|--------------------------------|
| `CwDecoder.kt` | `cw_decoder.c`| `f59e9eb677d81df87b6a7df931f8acb360c2e60c6973d383adc1fce5d77345f0` |
| `CwRwe.kt`     | `cw_rwe.c`    | `3939dda65857ad5ed1a09ce5e0a7fc71751f1a49cd5d602a45530fd2be0f0a66` |
| `CwMorse.kt`   | `cw_morse.c`  | `3b383083673b30609b74e0e1a341b29e27d1247a37ac36bccf1db3895028e8d2` |

The port keeps the C core's constants, defaults, control flow, and Float
arithmetic, so the three decoders (firmware, iOS, Android) hear the same
audio the same way. `CwDecoderTest` runs the same synthetic-PCM checks the
firmware bench (`MorseKitCheck`) runs against the real C core: clean copy,
mistuned pitch search, seeded noise, 48 kHz decimation, and reset re-arm.

**Never "fix" decode behavior only here.** Fix it upstream in the firmware,
let the iOS vendor re-sync, and mirror the change into this port — local
divergence would silently fork the decoders.

## License

MIT — Copyright (c) 2026 Jay Vana, the same license the vendored C core
carries (`another-morse-trainer/Sources/CWDecoderCore/LICENSE`).
