#!/usr/bin/env python3
"""The desktop app's version strings, derived from one place and checked.

desktop/app/build.gradle.kts holds the two numbers everything else is made
from (CLAUDE.md, "Versions, tags, CI"):

  desktopVersionName  the version shared by every app, e.g. 2.0.0
  desktopVersionCode  desktop's own build counter, e.g. 4

and the packages need these, derived from them:

  MSI ProductVersion      MAJOR.MINOR.CODE     e.g. 2.0.4
  MSIX Identity Version   MAJOR.MINOR.CODE.0   e.g. 2.0.4.0

Why CODE and not the shared PATCH: Windows Installer compares only the first
three fields of ProductVersion, and jpackage derives the MSI ProductCode from
vendor, name and version, so two MSIs built at the same 2.0.0 would share a
ProductCode and the second would refuse to install over the first. The Store
likewise rejects an MSIX whose version is not higher than the last one
submitted. Taking the third field from the build counter makes every build a
higher version without moving the shared version string, which only the
maintainer moves (it sends the Apple builds back through review).

The AppStream metainfo's newest <release> must name the shared version, and
the MSIX manifest template must take its version from CI (@VERSION@), not a
hard-coded value.

Usage (stdlib only; run from anywhere):
  versions.py check                 check everything agrees; print the versions
  versions.py field NAME            print one of: name, code, msi, msix
  versions.py github-output         check, then append name/code/msi/msix to
                                    $GITHUB_OUTPUT
  versions.py tag TAG               check, then check that a release tag names
                                    this version; print its kind (release|beta)

Every failure exits 1 with a message saying which file to fix.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DESKTOP = Path(__file__).resolve().parent.parent
GRADLE = DESKTOP / "app" / "build.gradle.kts"
METAINFO = DESKTOP / "packaging" / "linux" / "app.anothermorsetrainer.AnotherMorseTrainer.metainfo.xml"
APPX = DESKTOP / "packaging" / "msix" / "AppxManifest.xml"


class VersionError(Exception):
    pass


def _gradle_value(text, name, pattern):
    m = re.search(r"^val " + name + r" = " + pattern + r"\s*$", text, re.M)
    if not m:
        raise VersionError(f"{GRADLE.relative_to(DESKTOP.parent)}: no line 'val {name} = ...' "
                           f"in the expected form; fix this script's pattern if it was reformatted")
    return m.group(1)


def read_versions():
    text = GRADLE.read_text(encoding="utf-8")
    name = _gradle_value(text, "desktopVersionName", r'"([^"]*)"')
    code_s = _gradle_value(text, "desktopVersionCode", r"(\d+)")
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", name)
    if not m:
        raise VersionError(f"desktopVersionName '{name}' is not MAJOR.MINOR.PATCH")
    major, minor = int(m.group(1)), int(m.group(2))
    code = int(code_s)
    # Windows Installer: major and minor 0-255, build 0-65535. jpackage also
    # wants major > 0. MSIX: each field 0-65535.
    if not 1 <= major <= 255 or minor > 255:
        raise VersionError(f"desktopVersionName '{name}': an MSI needs MAJOR 1-255 and MINOR 0-255")
    if not 1 <= code <= 65535:
        raise VersionError(f"desktopVersionCode {code}: must be 1-65535 (the MSI build field)")
    msi = f"{major}.{minor}.{code}"
    # The wiring: build.gradle.kts must hand that same value to the MSI.
    if not re.search(r"^\s*msiPackageVersion\s*=\s*desktopPackageVersion\s*$", text, re.M):
        raise VersionError("build.gradle.kts: windows { msiPackageVersion = desktopPackageVersion } is missing")
    if not re.search(r'^val desktopPackageVersion = desktopVersionName\.split\("\."\)\.take\(2\)'
                     r'\.joinToString\("\."\) \+ "\.\$desktopVersionCode"\s*$', text, re.M):
        raise VersionError("build.gradle.kts: desktopPackageVersion is no longer MAJOR.MINOR.desktopVersionCode; "
                           "change this script and desktop.yml with it, or put it back")
    return {"name": name, "code": str(code), "msi": msi, "msix": msi + ".0"}


def check(v):
    # AppStream lists releases newest first; Flathub wants one per version.
    root = ET.parse(METAINFO).getroot()
    releases = root.findall("./releases/release")
    if not releases:
        raise VersionError(f"{METAINFO.name}: no <release> entry")
    newest = releases[0].get("version")
    if newest != v["name"]:
        raise VersionError(f"{METAINFO.name}: newest <release version=\"{newest}\"> does not match "
                           f"desktopVersionName {v['name']}; add a <release> for {v['name']} at the top")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", releases[0].get("date") or ""):
        raise VersionError(f"{METAINFO.name}: the newest <release> needs date=\"YYYY-MM-DD\"")

    ns = {"m": "http://schemas.microsoft.com/appx/manifest/foundation/windows10"}
    identity = ET.parse(APPX).getroot().find("m:Identity", ns)
    if identity is None or identity.get("Version") != "@VERSION@":
        raise VersionError(f"{APPX.name}: <Identity Version> must be the @VERSION@ token, filled by CI "
                           f"with {v['msix']}; found {identity.get('Version') if identity is not None else 'no Identity'}")


def tag_kind(tag, v):
    name, code = re.escape(v["name"]), re.escape(v["code"])
    if re.fullmatch(rf"desktop-v{name}(-b{code})?", tag):
        return "release"
    if re.fullmatch(rf"desktop-beta-v{name}-b{code}", tag):
        return "beta"
    raise VersionError(f"tag '{tag}' does not name this build: expected desktop-v{v['name']}-b{v['code']} "
                       f"(or desktop-v{v['name']}) for a release, desktop-beta-v{v['name']}-b{v['code']} "
                       f"for a testing build. Bump desktopVersionCode, or tag the right commit.")


def main(argv):
    if not argv or argv[0] not in ("check", "field", "github-output", "tag"):
        print(__doc__, file=sys.stderr)
        return 2
    try:
        v = read_versions()
        if argv[0] == "field":
            print(v[argv[1]])
            return 0
        check(v)
        if argv[0] == "check":
            for k in ("name", "code", "msi", "msix"):
                print(f"{k:5} {v[k]}")
            print("Version strings agree: build.gradle.kts, metainfo, MSIX manifest.")
        elif argv[0] == "github-output":
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
                for k in ("name", "code", "msi", "msix"):
                    out.write(f"{k}={v[k]}\n")
        elif argv[0] == "tag":
            print(tag_kind(argv[1], v))
    except VersionError as e:
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::error::{e}")
        else:
            print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
