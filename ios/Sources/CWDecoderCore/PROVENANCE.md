# CWDecoderCore

This directory holds the CW audio decoder core vendored from the
Carrier Wave bridge firmware (`CarrierWaveApp/carrier_wave`, branch
`cw-decode-usb`): a dependency-free C99 engine (decimating FIR front end,
pitch-search prepass, 3-bin Goertzel with dominance validation, adaptive
noise floor with hysteresis, and Mills Ratio-Weighted Estimation timing).

The build wires it twice on purpose: the Xcode app target compiles these
files directly (synchronized group + `MorseTrainer-Bridging-Header.h`), and
the SPM `CWDecoderCore` C target serves the `MorseKitCheck` audio bench.

## Vendored state

Byte-identical to the firmware's host-tested copy at
`github.com/CarrierWaveApp/carrier_wave` commit
`b00268c9dae2226d7ff822afa7ce468b24bd013d` (branch `cw-decode-usb`,
directory `firmware/src/cw/`). The files travelled over inside the
hash-carrying **CW Core Payload** transfer document and every file was
re-hashed on arrival; `shasum -a 256 <file>` must reproduce this table.

| File | SHA-256 |
|------|---------|
| `cw_decoder.c` | `f59e9eb677d81df87b6a7df931f8acb360c2e60c6973d383adc1fce5d77345f0` |
| `cw_decoder.h` | `6d7c2baea068305a819900fe5a10a4b2d136b793b411a0cc876ea36391a5a61e` |
| `cw_rwe.c` | `3939dda65857ad5ed1a09ce5e0a7fc71751f1a49cd5d602a45530fd2be0f0a66` |
| `cw_rwe.h` | `d6e1a38090aaaa675215374197b712da74be5b2d75ac25468c35f7942aa16a71` |
| `cw_morse.c` | `3b383083673b30609b74e0e1a341b29e27d1247a37ac36bccf1db3895028e8d2` |
| `cw_morse.h` | `79cfcb2342140fecf69fe4f7964a779c6cbe35fa090f3ca72eb356697e824ba1` |

Never patch these files in place. Fix upstream in the firmware, re-vendor,
and update this table — local divergence would silently fork the two
decoders.

## License

MIT — Copyright (c) 2026 Jay Vana. The full permission text lives in
`LICENSE` beside the sources; keep both together whenever they move again.
