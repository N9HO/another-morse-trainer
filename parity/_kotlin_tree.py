"""Shared by extract_android.py and extract_desktop.py: both trees are Kotlin
with the same layout, but each is read on its own and neither reads the other."""
import os

import _extract as X


def extract(tree, repo, sources):
    cfg = sources["trees"][tree]
    root = os.path.join(repo, cfg["root"])
    facts = {"tree": tree}
    m = cfg["modeEnum"]
    facts["modes"] = X.kotlin_enum(os.path.join(root, m["file"]), m["enum"])["ids"]
    r = cfg["routeType"]
    facts["routes"] = [X.norm(n) for n in X.kotlin_sealed_members(os.path.join(root, r["file"]), r["type"])]
    facts["screens"] = X.kotlin_screen_files(root)
    facts["settings"] = X.kotlin_settings_catalog(os.path.join(root, cfg["settingsCatalog"]))
    facts["readme"] = X.readme_features(os.path.join(repo, cfg["readme"]))
    opts = {}
    for key, spec in sources["optionLists"].items():
        loc = spec.get(tree)
        if loc is None:
            continue
        opts[key] = X.kotlin_enum(os.path.join(root, loc["file"]), loc["enum"], loc.get("labelArg"))
    facts["options"] = opts
    return facts
