#!/usr/bin/env python3
"""Facts the iOS tree registers, read statically from Swift source.

    python3 parity/extract_ios.py        # prints the facts as JSON

Registrations used (all explicit in the app, none guessed):
  modes     `enum TrainingMode` cases in MorseTrainerApp/AppModel.swift
  screens   every *View.swift in MorseTrainerApp/ (iOS has no route enum)
  settings  `SettingsCatalog.entries` in Sources/MorseKit/SettingsCatalog.swift
  options   the enums named in parity/sources.json `optionLists`
  readme    bold lead-ins under `## Features` in ios/README.md
Anything that stops matching raises ExtractionError (exit 2).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _extract as X  # noqa: E402

TREE = "ios"


def extract(repo, sources):
    cfg = sources["trees"][TREE]
    root = os.path.join(repo, cfg["root"])
    facts = {"tree": TREE}
    m = cfg["modeEnum"]
    facts["modes"] = X.swift_enum(os.path.join(root, m["file"]), m["enum"])["ids"]
    facts["routes"] = []          # iOS has no route enum; screens are its view files
    facts["screens"] = X.swift_view_files(os.path.join(root, cfg["screenDir"]))
    facts["settings"] = X.swift_settings_catalog(os.path.join(root, cfg["settingsCatalog"]))
    facts["readme"] = X.readme_features(os.path.join(repo, cfg["readme"]))
    opts = {}
    for key, spec in sources["optionLists"].items():
        loc = spec.get(TREE)
        if loc is None:
            continue          # recorded in the manifest as absent on this tree
        opts[key] = X.swift_enum(os.path.join(root, loc["file"]), loc["enum"], loc.get("labels"))
    facts["options"] = opts
    return facts


if __name__ == "__main__":
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with open(os.path.join(repo, "parity", "sources.json"), encoding="utf-8") as fh:
        src = json.load(fh)
    try:
        print(json.dumps(extract(repo, src), indent=1))
    except X.ExtractionError as exc:
        print("extract_ios: %s" % exc, file=sys.stderr)
        sys.exit(2)
