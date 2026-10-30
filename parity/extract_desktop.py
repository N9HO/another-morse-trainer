#!/usr/bin/env python3
"""Facts the desktop tree registers, read statically from Kotlin source.

    python3 parity/extract_desktop.py        # prints the facts as JSON

Registrations used: `enum class TrainingMode` (modes), the private
`sealed interface Route` in the main activity/entry file (routes), every
*Screen.kt (screens), `SettingsCatalog.entries`, the enums named in
parity/sources.json `optionLists`, and the README's `## Features` bullets.
Anything that stops matching raises ExtractionError (exit 2).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _extract as X  # noqa: E402
import _kotlin_tree  # noqa: E402

TREE = "desktop"


def extract(repo, sources):
    return _kotlin_tree.extract(TREE, repo, sources)


if __name__ == "__main__":
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with open(os.path.join(repo, "parity", "sources.json"), encoding="utf-8") as fh:
        src = json.load(fh)
    try:
        print(json.dumps(extract(repo, src), indent=1))
    except X.ExtractionError as exc:
        print("extract_desktop: %s" % exc, file=sys.stderr)
        sys.exit(2)
