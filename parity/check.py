#!/usr/bin/env python3
"""Parity guard: fail when iOS, Android and desktop drift apart unrecorded.

    python3 parity/check.py              # the CI check; exit 0 clean, 1 drift, 2 extraction failed
    python3 parity/check.py --self-test  # prove the check fails on deliberately drifted data
    python3 parity/check.py --gaps       # list the open gaps and exceptions the manifest records
    python3 parity/check.py --list-findings   # every raw divergence, before the manifest suppresses known ones

What it compares (see parity/manifest.json, parity/sources.json, PARITY.md):
  * every training mode, route and screen file each tree registers, against the
    manifest features that claim them (an unclaimed one is "unregistered");
  * each feature's per-platform status against what the tree actually has;
  * every enumerated option list (keyer modes, paddle modes, durations ...) across
    the trees, in order, and against the shared fixtures;
  * the Settings catalog: which settings each tree has, their titles and order;
  * the README feature headings of the three apps;
  * that every non-present status has a reason and a PARITY.md anchor, and that
    every recorded open gap is still open (a closed gap must be deleted).

Python 3 standard library only.  No network.  Reads source text, runs nothing.
"""
import copy
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import _extract as X  # noqa: E402
import extract_android  # noqa: E402
import extract_desktop  # noqa: E402
import extract_ios  # noqa: E402

TREES = ("ios", "android", "desktop")
KOTLIN = ("android", "desktop")
EXTRACTORS = {"ios": extract_ios, "android": extract_android, "desktop": extract_desktop}
STATUSES = ("present", "limited", "absent")
HAS = ("present", "limited")


class Finding:
    def __init__(self, key, msg):
        self.key, self.msg = key, msg

    def __repr__(self):
        return "%s: %s" % (self.key, self.msg)


def jload(rel):
    with open(os.path.join(REPO, rel), encoding="utf-8") as fh:
        return json.load(fh)


def slug(heading):
    s = re.sub(r"[^\w\- ]", "", heading.strip().lower())
    return s.replace(" ", "-")


def parity_anchors(text):
    anchors = set(re.findall(r'<a id="([^"]+)"', text))
    for h in re.findall(r"^#{1,6}\s+(.+?)\s*$", text, re.M):
        anchors.add(slug(h))
    return anchors


def norm_list(xs):
    return [X.norm(x) for x in xs]


# ----------------------------------------------------------------------------
# The comparison itself.  Pure: takes the manifest, sources, per-tree facts and
# fixture contents, returns findings.  --self-test feeds it drifted copies.
# ----------------------------------------------------------------------------

def evaluate(man, src, facts, fixtures, parity_md, repo=REPO):
    out = []

    def add(key, msg):
        out.append(Finding(key, msg))

    anchors = parity_anchors(parity_md)
    features = man["features"]

    # ---- 1. manifest structure ------------------------------------------
    seen = set()
    referenced_ex = set()
    for f in features:
        fid = f.get("id", "?")
        if fid in seen:
            add("manifest:duplicate:" + fid, "feature id %s appears twice" % fid)
        seen.add(fid)
        for t in TREES:
            st = f.get("status", {}).get(t)
            if st not in STATUSES:
                add("manifest:status:%s:%s" % (fid, t), "%s has no valid status for %s (%r)" % (fid, t, st))
                continue
            claims = f.get("claims", {}).get(t, {})
            nclaims = sum(len(v) for v in claims.values())
            notes = f.get("notes", {}).get(t, [])
            if st in HAS and nclaims == 0:
                add("manifest:noclaim:%s:%s" % (fid, t),
                    "%s is %s on %s but claims no mode, route, screen or file there" % (fid, st, t))
            if st == "absent" and nclaims:
                add("manifest:absent-claims:%s:%s" % (fid, t),
                    "%s is absent on %s but claims artifacts there" % (fid, t))
            if st != "present":
                if not notes:
                    add("manifest:noreason:%s:%s" % (fid, t),
                        "%s is %s on %s with no reason and PARITY.md anchor (notes.%s)" % (fid, st, t, t))
            for n in notes:
                if not n.get("reason") or not n.get("anchor") or n.get("state") not in ("open", "exception"):
                    add("manifest:badnote:%s:%s" % (fid, t),
                        "%s/%s: a note needs reason, anchor and state open|exception" % (fid, t))
                    continue
                frag = n["anchor"].partition("#")[2]
                if n["anchor"].split("#")[0] != "PARITY.md" or frag not in anchors:
                    add("manifest:anchor:%s:%s" % (fid, t),
                        "%s/%s: anchor %s does not exist in PARITY.md" % (fid, t, n["anchor"]))
                if frag.startswith("ex-"):
                    referenced_ex.add(frag)
        # claim symmetry: if one platform that has the feature claims a category, all do
        # (screens and files are not symmetric by design: iOS draws most modes
        # inside ContentView.swift where Kotlin has one file per screen)
        for cat, trees in (("modes", TREES), ("routes", KOTLIN)):
            have = [t for t in trees if f["status"].get(t) in HAS]
            c = [bool(f.get("claims", {}).get(t, {}).get(cat)) for t in have]
            if any(c) and not all(c):
                miss = [t for t, x in zip(have, c) if not x]
                add("manifest:asymmetry:%s:%s" % (fid, cat),
                    "%s claims %s on some platforms but not %s" % (fid, cat, ", ".join(miss)))
    for sid, s in man.get("settings", {}).items():
        for t in TREES:
            st = s["status"].get(t)
            if st != "present":
                notes = s.get("notes", {}).get(t, [])
                if not notes:
                    add("manifest:noreason:setting:%s:%s" % (sid, t), "setting %s is %s on %s without a note" % (sid, st, t))
                for n in notes:
                    frag = n.get("anchor", "").partition("#")[2]
                    if frag not in anchors or not n.get("reason"):
                        add("manifest:anchor:setting:%s:%s" % (sid, t), "setting %s/%s: bad reason or anchor" % (sid, t))
                    if frag.startswith("ex-"):
                        referenced_ex.add(frag)
    for g in man.get("gaps", []):
        for req in ("id", "summary", "state", "status", "reason", "anchor", "ref"):
            if not g.get(req):
                add("manifest:gap-field:" + g.get("id", "?"), "gap %s lacks %s" % (g.get("id", "?"), req))
        if g.get("anchor", "").partition("#")[2] not in anchors:
            add("manifest:gap-anchor:" + g.get("id", "?"), "gap %s anchor %s not in PARITY.md" % (g.get("id"), g.get("anchor")))
    for a in sorted(a for a in anchors if a.startswith("ex-")):
        if a not in referenced_ex:
            add("manifest:orphan-exception:" + a,
                "PARITY.md documents exception %s but no manifest entry references it" % a)

    # ---- 2. registrations vs claims -------------------------------------
    for t in TREES:
        fx = facts[t]
        for cat, label in (("modes", "mode"), ("routes", "route"), ("screens", "screen file")):
            if cat == "routes" and t == "ios":
                continue
            claimed = set()
            for f in features:
                claimed.update(f.get("claims", {}).get(t, {}).get(cat, []))
            extracted = set(fx[cat])
            for name in sorted(extracted - claimed):
                add("unregistered:%s:%s:%s" % (cat, t, name),
                    "%s '%s' exists in the %s tree but no manifest feature claims it "
                    "(add a feature to parity/manifest.json)" % (label, name, t))
            for name in sorted(claimed - extracted):
                add("missing:%s:%s:%s" % (cat, t, name),
                    "manifest claims %s '%s' on %s but the tree does not register it" % (label, name, t))
        root = os.path.join(repo, src["trees"][t]["root"])
        for f in features:
            for rel in f.get("claims", {}).get(t, {}).get("files", []):
                if not os.path.isfile(os.path.join(root, rel)):
                    add("missing:file:%s:%s" % (t, rel), "manifest claims file %s/%s (%s) but it does not exist" % (t, rel, f["id"]))

    # ---- 3. option lists ------------------------------------------------
    for key, spec in src["optionLists"].items():
        mode = spec.get("compare", "ids+labels")
        per = {t: facts[t]["options"].get(key) for t in TREES if t in spec}
        ref_t = "ios"
        for t in TREES:
            if t == ref_t or t not in per:
                continue
            a, b = per[ref_t], per[t]
            diffs = []
            if mode != "labels" and a["ids"] != b["ids"]:
                diffs.append("ids  %s: %s\n         %s: %s" % (ref_t, a["ids"], t, b["ids"]))
            if a["labels"] is not None and b["labels"] is not None and a["labels"] != b["labels"]:
                diffs.append("labels %s: %s\n         %s: %s" % (ref_t, a["labels"], t, b["labels"]))
            if diffs:
                add("options:%s:%s" % (key, t) if t != "desktop" else "options:%s:desktop" % key,
                    "option list '%s' differs between ios and %s\n      %s" % (key, t, "\n      ".join(diffs)))
    for key, spec in man.get("optionLists", {}).items():
        fx_rel = spec.get("fixture")
        if not fx_rel:
            continue
        fx = fixtures.get(fx_rel)
        if fx is None or spec["key"] not in fx or not fx[spec["key"]]:
            add("fixture:missing:" + key, "fixture %s has no list '%s'" % (fx_rel, spec["key"]))
            continue
        want = fx[spec["key"]]
        for t in TREES:
            got = facts[t]["options"].get(key)
            if got is None:
                add("fixture:%s:%s" % (key, t), "%s has no option list '%s'" % (t, key))
                continue
            if spec.get("labels"):
                have, w = got["labels"], want
            else:
                have, w = got["ids"], norm_list(want)
            if have != w:
                add("fixture:%s:%s" % (key, t),
                    "%s option list '%s' differs from %s\n      want %s\n      have %s" % (t, key, fx_rel, w, have))
        for t, p in spec.get("pickers", {}).items():
            root = os.path.join(repo, src["trees"][t]["root"])
            try:
                ok = X.probe(root, p["file"], p["pattern"])
            except X.ExtractionError as exc:
                add("picker:%s:%s" % (key, t), str(exc))
                continue
            if not ok:
                add("picker:%s:%s" % (key, t),
                    "%s/%s no longer builds the '%s' picker from the whole list (pattern %s)" % (t, p["file"], key, p["pattern"]))

    # ---- 4. settings catalog --------------------------------------------
    cat = {t: dict(facts[t]["settings"]) for t in TREES}
    order = {t: [i for i, _ in facts[t]["settings"]] for t in TREES}
    allids = set()
    for t in TREES:
        allids |= set(cat[t])
    for sid in sorted(allids | set(man.get("settings", {}))):
        exp = man.get("settings", {}).get(sid, {}).get("status", {t: "present" for t in TREES})
        for t in TREES:
            has = sid in cat[t]
            if exp[t] in HAS and not has:
                add("settings-missing:%s:%s" % (sid, t), "setting '%s' is in the manifest as %s on %s but not in its Settings catalog" % (sid, exp[t], t))
            if exp[t] == "absent" and has:
                add("settings-unexpected:%s:%s" % (sid, t), "setting '%s' is in the %s Settings catalog but the manifest says absent" % (sid, t))
        if sid not in man.get("settings", {}):
            holders = [t for t in TREES if sid in cat[t]]
            if len(holders) != 3:
                pass  # already reported above as settings-missing against the all-present default
        have = [t for t in TREES if sid in cat[t]]
        if len(have) > 1:
            titles = {t: cat[t][sid] for t in have}
            if len(set(titles.values())) > 1:
                add("settingTitle:" + sid, "setting '%s' is titled differently: %s" % (sid, titles))
    shared = [i for i in order["ios"] if all(i in cat[t] for t in KOTLIN)]
    for t in KOTLIN:
        mine = [i for i in order[t] if i in shared]
        if mine != shared:
            add("settingOrder:" + t, "Settings catalog order of shared settings differs between ios and %s" % t)

    # ---- 5. README headings ---------------------------------------------
    for t in TREES:
        claimed = set()
        for f in features:
            r = f.get("readme", {}).get(t)
            if isinstance(r, list):
                claimed.update(r)
        extracted = facts[t]["readme"]
        for h in extracted:
            if h not in claimed:
                add("readme-unclaimed:%s:%s" % (t, h), "%s README lists '%s' but no manifest feature claims that heading" % (t, h))
        for h in sorted(claimed - set(extracted)):
            add("readme-stale:%s:%s" % (t, h), "manifest expects '%s' in the %s README but it is not a feature heading there" % (h, t))
    for f in features:
        r = f.get("readme")
        if r is None:
            continue
        heads = {}
        for t in TREES:
            v = r.get(t)
            if v == "exempt":
                continue
            if f["status"].get(t) in HAS and not v:
                add("readme-missing:%s:%s" % (f["id"], t), "feature '%s' is %s on %s but its README has no heading for it" % (f["name"], f["status"][t], t))
            if v:
                heads[t] = tuple(v)
        if len(set(heads.values())) > 1:
            add("readme-name:" + f["id"], "feature '%s' is headed differently in the READMEs: %s" % (f["name"], {t: list(h) for t, h in heads.items()}))

    # ---- 6. gaps: suppress known findings, detect stale ones -------------
    keys = {fd.key for fd in out}
    suppressed, manual, stale = set(), [], []
    for g in man.get("gaps", []):
        d = g.get("detect", {"type": "manual"})
        if d["type"] == "finding":
            for k in d["keys"]:
                if k in keys:
                    suppressed.add(k)
                else:
                    stale.append(Finding("gap-stale:%s:%s" % (g["id"], k),
                                         "gap %s records divergence %s, which the check no longer sees; "
                                         "if it is closed delete the gap from parity/manifest.json" % (g["id"], k)))
        elif d["type"] == "probe":
            still = True
            for c in d["conditions"]:
                root = os.path.join(repo, src["trees"][c["tree"]]["root"])
                try:
                    hit = X.probe(root, c["file"], c["pattern"])
                except X.ExtractionError as exc:
                    stale.append(Finding("gap-stale:%s" % g["id"], "gap %s probe cannot run: %s" % (g["id"], exc)))
                    still = None
                    break
                if hit != c["matches"]:
                    still = False
            if still is False:
                stale.append(Finding("gap-stale:" + g["id"],
                                     "gap %s no longer holds in the source (probe changed): it looks closed - "
                                     "delete it from parity/manifest.json and PARITY.md notes" % g["id"]))
        else:
            manual.append(g)
    result = [fd for fd in out if fd.key not in suppressed] + stale
    return result, sorted(suppressed), manual, out


# ----------------------------------------------------------------------------

def gather(src):
    facts = {}
    for t in TREES:
        facts[t] = EXTRACTORS[t].extract(REPO, src)
    return facts


def load_all():
    man = jload("parity/manifest.json")
    src = jload("parity/sources.json")
    fixtures = {}
    for spec in man.get("optionLists", {}).values():
        rel = spec.get("fixture")
        if rel and rel not in fixtures:
            try:
                fixtures[rel] = jload(rel)
            except (OSError, ValueError) as exc:
                raise X.ExtractionError("fixture %s: %s" % (rel, exc))
    with open(os.path.join(REPO, "PARITY.md"), encoding="utf-8") as fh:
        pmd = fh.read()
    return man, src, fixtures, pmd


def report(findings, suppressed, manual, man):
    for fd in findings:
        print("FAIL  %s\n      %s" % (fd.key, fd.msg))
    opn = [g for g in man.get("gaps", []) if g["state"] == "open"]
    print()
    print("%d divergence(s) not recorded in parity/manifest.json" % len(findings))
    print("%d open gap(s) recorded (%d machine-checked, %d manual); %d detected divergence(s) are covered by them"
          % (len(opn), len(opn) - len(manual), len(manual), len(suppressed)))
    if findings:
        print("To fix: make the trees agree, or record the difference in parity/manifest.json "
              "(reason + PARITY.md anchor). See PARITY.md and CLAUDE.md.")


def cmd_gaps(man):
    print("Open gaps (PARITY.md#open-gaps):")
    for g in man["gaps"]:
        st = " ".join("%s=%s" % (t[:3], g["status"][t]) for t in TREES)
        print("  %-22s [%s] %s  (%s)" % (g["id"], st, g["summary"][:110], g["detect"]["type"]))
    print("\nPlatform-limitation exceptions recorded on features:")
    for f in man["features"]:
        for t in TREES:
            for n in f.get("notes", {}).get(t, []):
                print("  %-22s %-8s %-8s %s" % (f["id"], t, f["status"][t], n["anchor"].partition("#")[2] or n["anchor"]))


# ----------------------------------------------------------------------------
# Negative control: the checker must FAIL on drifted data.
# ----------------------------------------------------------------------------

def self_test():
    man, src, fixtures, pmd = load_all()
    facts = gather(src)
    base, _, _, _ = evaluate(man, src, facts, fixtures, pmd)
    if base:
        print("self-test: the real data is not clean, fix that first:")
        for fd in base[:10]:
            print("  ", fd)
        return 1
    print("self-test: baseline clean (0 unrecorded divergences). Applying drift:")
    cases = []

    def case(name, expect, mutate):
        cases.append((name, expect, mutate))

    def m_readme(man, facts, fx, pm):
        facts["android"]["readme"].remove("Reference")
    case("README heading removed from the Android feature list", "readme-", m_readme)

    def m_keyer_swap(man, facts, fx, pm):
        o = facts["android"]["options"]["keyerModes"]
        o["labels"][2], o["labels"][3] = o["labels"][3], o["labels"][2]
        o["ids"][2], o["ids"][3] = o["ids"][3], o["ids"][2]
    case("Android keyer-mode list reordered (Bug <-> Electric Bug)", "fixture:keyerModes:android", m_keyer_swap)

    def m_keyer_missing(man, facts, fx, pm):
        facts["desktop"]["options"]["keyerModes"]["labels"].pop()
        facts["desktop"]["options"]["keyerModes"]["ids"].pop()
    case("desktop loses the Keyahead keyer mode", "fixture:keyerModes:desktop", m_keyer_missing)

    def m_option(man, facts, fx, pm):
        facts["ios"]["options"]["revealModes"]["ids"].append("sometimes")
    case("iOS gains a reveal mode the others lack", "options:revealModes", m_option)

    def m_newmode(man, facts, fx, pm):
        facts["ios"]["modes"].append("zzznewmode")
    case("a new mode added to iOS only, no manifest entry", "unregistered:modes:ios:zzznewmode", m_newmode)

    def m_newroute(man, facts, fx, pm):
        facts["desktop"]["routes"].append("zzzscreen")
    case("a new route added to desktop only", "unregistered:routes:desktop:zzzscreen", m_newroute)

    def m_newscreen(man, facts, fx, pm):
        facts["android"]["screens"].append("ZzzScreen.kt")
    case("a new screen file added to Android only", "unregistered:screens:android:ZzzScreen.kt", m_newscreen)

    def m_dropfeat(man, facts, fx, pm):
        man["features"][:] = [f for f in man["features"] if f["id"] != "dungeon"]
    case("manifest entry for a game deleted", "unregistered:modes:", m_dropfeat)

    def m_status(man, facts, fx, pm):
        for f in man["features"]:
            if f["id"] == "invaders":
                f["status"]["android"] = "absent"
    case("manifest says Android lacks Invaders but the tree has it", "manifest:absent-claims:invaders:android", m_status)

    def m_noreason(man, facts, fx, pm):
        for f in man["features"]:
            if f["id"] == "haptics":
                f["notes"] = {}
    case("a platform-absent feature loses its reason and anchor", "manifest:noreason:haptics:desktop", m_noreason)

    def m_anchor(man, facts, fx, pm):
        for f in man["features"]:
            if f["id"] == "haptics":
                f["notes"]["desktop"][0]["anchor"] = "PARITY.md#no-such-anchor"
    case("a reason points at a PARITY.md anchor that does not exist", "manifest:anchor:haptics:desktop", m_anchor)

    def m_setting(man, facts, fx, pm):
        facts["desktop"]["settings"].append(("zzzSetting", "Zzz"))
    case("a setting added to desktop only", "settings-missing:zzzSetting:", m_setting)

    def m_setting_gone(man, facts, fx, pm):
        facts["ios"]["settings"][:] = [s for s in facts["ios"]["settings"] if s[0] != "haptics"]
    case("iOS drops the Haptic feedback setting", "settings-missing:haptics:ios", m_setting_gone)

    def m_title(man, facts, fx, pm):
        facts["android"]["settings"][:] = [(i, "Renamed" if i == "bandNoise" else t) for i, t in facts["android"]["settings"]]
    case("a shared setting retitled on Android", "settingTitle:bandNoise", m_title)

    def m_fixture(man, facts, fx, pm):
        fx["fixtures/keyer-modes.json"]["keyerModes"].append("Cootie")
    case("the shared keyer-mode fixture gains an entry the apps lack", "fixture:keyerModes:", m_fixture)

    def m_stale(man, facts, fx, pm):
        # A synthetic record, so the test does not depend on any real gap being open:
        # its probe asserts 'Stats' is absent from StatsScreen.kt, which the source contradicts.
        man["gaps"].append({"id": "selftest-closed", "kind": "behavior", "summary": "synthetic",
                            "state": "open", "status": {"ios": "present", "android": "absent", "desktop": "absent"},
                            "reason": "synthetic", "anchor": "PARITY.md#open-gaps",
                            "detect": {"type": "probe", "conditions": [
                                {"tree": "android", "file": "StatsScreen.kt", "pattern": "Stats", "matches": False}]}})
    case("a recorded gap whose source no longer matches (closed but not removed)", "gap-stale:selftest-closed", m_stale)

    def m_orphan(man, facts, fx, pm):
        pm_add = pm + '\n<a id="ex-invented"></a>\n'
        return pm_add
    case("a PARITY.md exception with no manifest entry", "manifest:orphan-exception:ex-invented", m_orphan)

    failed = 0
    for name, expect, mutate in cases:
        m2, f2, x2, p2 = copy.deepcopy(man), copy.deepcopy(facts), copy.deepcopy(fixtures), pmd
        r = mutate(m2, f2, x2, p2)
        if isinstance(r, str):
            p2 = r
        found, _, _, _ = evaluate(m2, src, f2, x2, p2)
        hit = [fd for fd in found if fd.key.startswith(expect)]
        if hit:
            print("  caught   %-70s -> %s" % (name, hit[0].key))
        else:
            failed += 1
            print("  MISSED   %-70s (wanted a finding starting %s)" % (name, expect))

    # a pattern that stops matching must raise, not return nothing
    try:
        X.swift_enum(os.path.join(REPO, "ios/MorseTrainerApp/AppModel.swift"), "NoSuchEnum")
        failed += 1
        print("  MISSED   extractor returned nothing for a missing enum instead of failing")
    except X.ExtractionError:
        print("  caught   %-70s -> ExtractionError" % "extractor pattern stops matching")
    try:
        X.readme_features(os.path.join(REPO, "PARITY.md"))
        failed += 1
        print("  MISSED   README extractor accepted a file with no Features section")
    except X.ExtractionError:
        print("  caught   %-70s -> ExtractionError" % "README without a Features section")
    if failed:
        print("self-test FAILED: %d drift(s) went undetected" % failed)
        return 1
    print("self-test OK: all %d drifts detected, the check is not passing on nothing" % (len(cases) + 2))
    return 0


def main(argv):
    if "--self-test" in argv:
        return self_test()
    try:
        man, src, fixtures, pmd = load_all()
        if "--gaps" in argv:
            cmd_gaps(man)
            return 0
        facts = gather(src)
    except X.ExtractionError as exc:
        print("EXTRACTION FAILED (the guard would otherwise pass on nothing):\n  %s" % exc)
        return 2
    findings, suppressed, manual, raw = evaluate(man, src, facts, fixtures, pmd)
    if "--list-findings" in argv:
        for fd in raw:
            print("%s\n      %s" % (fd.key, fd.msg))
        return 0
    print("parity check: %d modes / %d routes / %d screens / %d option lists / %d settings / %d manifest features"
          % (len(facts["ios"]["modes"]), len(facts["android"]["routes"]), len(facts["android"]["screens"]),
             len(src["optionLists"]), len(facts["ios"]["settings"]), len(man["features"])))
    report(findings, suppressed, manual, man)
    if "--fail-on-open" in argv and any(g["state"] == "open" for g in man["gaps"]):
        return 1
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
