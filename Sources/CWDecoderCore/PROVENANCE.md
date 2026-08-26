# CWDecoderCore

This directory receives the CW audio decoder core vendored from the
Carrier Wave bridge firmware (`CarrierWaveApp/carrier_wave`, branch
`cw-decode-usb`): a dependency-free C99 engine (decimating FIR front end,
pitch-search prepass, 3-bin Goertzel with dominance validation, adaptive
noise floor with hysteresis, and Mills Ratio-Weighted Estimation timing).

The build is already wired for it — an Xcode synchronized group plus a
bridging header on the app side, and (once the sources land) an SPM C
target benched by MorseKitCheck. Files are vendored **byte-identical** to
the firmware's host-tested copy; this file then records the exact source
commit and per-file SHA-256 checksums.
