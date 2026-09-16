#!/usr/bin/env python3
"""Minimal App Store Connect API client (ES256 JWT, no third-party deps beyond
`cryptography`). Reads credentials from the env exported by tools/asc-auth.sh.

Usage:
  source tools/asc-auth.sh
  python3 tools/asc-api.py builds          # recent builds + processing state
  python3 tools/asc-api.py groups          # list beta groups
  python3 tools/asc-api.py submit          # submit newest VALID build for beta review
  python3 tools/asc-api.py dist            # assign newest VALID build to the same
                                           # individual testers as the prior build
  python3 tools/asc-api.py notify <group>  # add newest VALID build to a group
  python3 tools/asc-api.py extgroup <name>   # find-or-create an external beta group
  python3 tools/asc-api.py publiclink <name> # enable + print its public TestFlight link
  python3 tools/asc-api.py appstore <version-string> <build> [notes-file]
                                           # attach the build to the App Store
                                           # version, set What's New, submit for
                                           # App Review (release after approval)
"""
import base64, json, os, sys, time, urllib.parse, urllib.request, urllib.error

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

API = "https://api.appstoreconnect.apple.com"


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def token() -> str:
    kid = os.environ["ASC_KEY_ID"]
    iss = os.environ["ASC_ISSUER_ID"]
    key_path = os.path.expanduser(os.environ["ASC_KEY_PATH"])
    with open(key_path, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    header = {"alg": "ES256", "kid": kid, "typ": "JWT"}
    payload = {"iss": iss, "iat": int(time.time()), "exp": int(time.time()) + 600,
               "aud": "appstoreconnect-v1"}
    signing_input = f"{_b64(json.dumps(header).encode())}.{_b64(json.dumps(payload).encode())}".encode()
    der = key.sign(signing_input, ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der)
    sig = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return f"{signing_input.decode()}.{_b64(sig)}"


def call(method: str, path: str, body=None):
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token()}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def newest_valid_build(app):
    """The newest non-expired build that's finished processing (VALID), or None."""
    st, d = call("GET", f"/v1/builds?filter[app]={app}&sort=-version&limit=10"
                        f"&fields[builds]=version,processingState,expired")
    for b in d.get("data", []):
        a = b["attributes"]
        if a.get("processingState") == "VALID" and not a.get("expired"):
            return b
    return None


def prior_build(app, exclude_id):
    """The newest VALID build that isn't `exclude_id` (to copy its tester set)."""
    st, d = call("GET", f"/v1/builds?filter[app]={app}&sort=-version&limit=10"
                        f"&fields[builds]=version,processingState,expired")
    for b in d.get("data", []):
        a = b["attributes"]
        if b["id"] != exclude_id and a.get("processingState") == "VALID" and not a.get("expired"):
            return b
    return None


def main():
    app = os.environ["ASC_APP_ID"]
    cmd = sys.argv[1] if len(sys.argv) > 1 else "builds"

    if cmd == "groups":
        st, d = call("GET", f"/v1/apps/{app}/betaGroups?limit=50")
        for g in d.get("data", []):
            a = g["attributes"]
            print(f'{g["id"]}  "{a.get("name")}"  internal={a.get("isInternalGroup")}  '
                  f'autoNotify={a.get("hasAccessToAllBuilds")} publicLink={a.get("publicLinkEnabled")}')
        if st != 200:
            print(d)

    elif cmd == "builds":
        st, d = call("GET", f"/v1/builds?filter[app]={app}&sort=-version&limit=5"
                            f"&fields[builds]=version,processingState,uploadedDate,expired")
        for b in d.get("data", []):
            a = b["attributes"]
            print(f'build {a.get("version")}  {a.get("processingState")}  '
                  f'uploaded={a.get("uploadedDate")}  id={b["id"]}')
        if st != 200:
            print(d)

    elif cmd == "wait":
        # Poll until the build with the given version (default: highest) is VALID.
        want = sys.argv[2] if len(sys.argv) > 2 else None
        for _ in range(60):
            st, d = call("GET", f"/v1/builds?filter[app]={app}&sort=-version&limit=5"
                                f"&fields[builds]=version,processingState")
            rows = d.get("data", [])
            target = (next((b for b in rows if b["attributes"].get("version") == want), None)
                      if want else (rows[0] if rows else None))
            state = target["attributes"].get("processingState") if target else "absent"
            print(f"  build {want or '(newest)'}: {state}", flush=True)
            if state == "VALID":
                return
            time.sleep(20)
        print("  gave up waiting for the build to process")

    elif cmd == "submit":
        b = newest_valid_build(app)
        if not b:
            print("No VALID build yet — still processing. Try again shortly.")
            return
        bid, ver = b["id"], b["attributes"]["version"]
        st, d = call("GET", f"/v1/builds/{bid}/buildBetaDetail")
        ext = (d.get("data") or {}).get("attributes", {}).get("externalBuildState")
        if ext in ("IN_BETA_TESTING", "IN_EXPORT_COMPLIANCE_REVIEW", "WAITING_FOR_BETA_REVIEW"):
            print(f"build {ver}: already {ext} — nothing to submit.")
            return
        st, d = call("POST", "/v1/betaAppReviewSubmissions",
                     {"data": {"type": "betaAppReviewSubmissions",
                               "relationships": {"build": {"data": {"type": "builds", "id": bid}}}}})
        state = (d.get("data") or {}).get("attributes", {}).get("betaReviewState")
        print(f"submit build {ver} for beta review: HTTP {st} -> {state or d}")

    elif cmd == "appstore":
        # Production release: put build <build> on App Store version
        # <version-string>, set What's New, and submit it to App Review with
        # automatic release once approved.
        #   python3 tools/asc-api.py appstore 1.3.0 35 tools/whatsnew/whatsnew-en-US
        # The version string must equal the build's CFBundleShortVersionString
        # (MARKETING_VERSION); App Store Connect rejects the pairing otherwise.
        # Idempotent: re-running after a partial failure picks up where it left
        # off, and a version already submitted or already live is reported, not
        # re-submitted.
        if len(sys.argv) < 4:
            print("usage: appstore <version-string> <build-number> [notes-file]")
            sys.exit(2)
        version_string, build_number = sys.argv[2], sys.argv[3]
        notes_path = sys.argv[4] if len(sys.argv) > 4 else None

        # 1. The build, which must have finished processing (`wait` does that).
        st, d = call("GET", f"/v1/builds?filter[app]={app}&filter[version]={build_number}"
                            f"&fields[builds]=version,processingState,expired,usesNonExemptEncryption&limit=5")
        build = next((b for b in d.get("data", [])
                      if b["attributes"].get("version") == build_number), None)
        if not build:
            print(f"build {build_number} is not in App Store Connect yet.")
            sys.exit(1)
        battrs = build["attributes"]
        if battrs.get("processingState") != "VALID" or battrs.get("expired"):
            print(f"build {build_number}: {battrs.get('processingState')} expired={battrs.get('expired')} — not attachable.")
            sys.exit(1)
        bid = build["id"]
        # Export compliance. Info.plist carries ITSAppUsesNonExemptEncryption,
        # so this is normally already false; a build left at null cannot be
        # submitted ("Missing Compliance"), so settle it here rather than in the
        # UI. The app only uses HTTPS, which is exempt.
        if battrs.get("usesNonExemptEncryption") is None:
            st, d = call("PATCH", f"/v1/builds/{bid}",
                         {"data": {"type": "builds", "id": bid,
                                   "attributes": {"usesNonExemptEncryption": False}}})
            print(f"build {build_number}: set usesNonExemptEncryption=false: HTTP {st}")

        # 2. The App Store version for this version string: reuse, rename or
        # create. App Store Connect allows one editable version per platform,
        # and a never-released app already has one (the placeholder made with
        # the app record, often "1.0"), so an editable version under another
        # string is renamed rather than joined by a second one, which the API
        # would refuse with a 409.
        st, d = call("GET", f"/v1/apps/{app}/appStoreVersions?filter[platform]=IOS"
                            f"&fields[appStoreVersions]=versionString,appVersionState,releaseType&limit=50")
        versions = d.get("data", [])
        ver = next((v for v in versions
                    if v["attributes"].get("versionString") == version_string), None)
        EDITABLE = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED",
                    "METADATA_REJECTED", "INVALID_BINARY"}
        if not ver:
            editable = next((v for v in versions
                             if v["attributes"].get("appVersionState") in EDITABLE), None)
            if editable:
                old_string = editable["attributes"].get("versionString")
                st, d = call("PATCH", f"/v1/appStoreVersions/{editable['id']}",
                             {"data": {"type": "appStoreVersions", "id": editable["id"],
                                       "attributes": {"versionString": version_string}}})
                print(f"renamed editable App Store version {old_string} -> {version_string}: HTTP {st}")
                if st >= 300:
                    print(json.dumps(d, indent=2)); sys.exit(1)
                ver = d.get("data") or editable
                ver["attributes"]["versionString"] = version_string
        if ver:
            vid, state = ver["id"], ver["attributes"].get("appVersionState")
            print(f"App Store version {version_string}: {state} (id {vid})")
            if state in ("WAITING_FOR_REVIEW", "IN_REVIEW", "WAITING_FOR_EXPORT_COMPLIANCE"):
                print(f"version {version_string} is already {state}; nothing to do."
                      " Cancel it in App Store Connect first to swap in a newer build.")
                return
            if state in ("PENDING_DEVELOPER_RELEASE", "PENDING_APPLE_RELEASE",
                         "PROCESSING_FOR_DISTRIBUTION", "READY_FOR_DISTRIBUTION",
                         "REPLACED_WITH_NEW_VERSION", "ACCEPTED"):
                print(f"version {version_string} is already {state}: it has shipped or is "
                      "about to. Bump MARKETING_VERSION for a new App Store release.")
                sys.exit(1)
            if state not in EDITABLE:
                print(f"version {version_string} is in an unexpected state {state}; refusing to guess.")
                sys.exit(1)
            st, d = call("PATCH", f"/v1/appStoreVersions/{vid}",
                         {"data": {"type": "appStoreVersions", "id": vid,
                                   "attributes": {"releaseType": "AFTER_APPROVAL"}}})
            print(f"version {version_string}: releaseType=AFTER_APPROVAL: HTTP {st}")
            st, d = call("PATCH", f"/v1/appStoreVersions/{vid}/relationships/build",
                         {"data": {"type": "builds", "id": bid}})
            print(f"version {version_string}: attach build {build_number}: HTTP {st}")
            if st >= 300:
                print(json.dumps(d, indent=2)); sys.exit(1)
        else:
            st, d = call("POST", "/v1/appStoreVersions",
                         {"data": {"type": "appStoreVersions",
                                   "attributes": {"platform": "IOS",
                                                  "versionString": version_string,
                                                  "releaseType": "AFTER_APPROVAL"},
                                   "relationships": {
                                       "app": {"data": {"type": "apps", "id": app}},
                                       "build": {"data": {"type": "builds", "id": bid}}}}})
            if st >= 300 or not d.get("data"):
                print(f"create App Store version {version_string}: HTTP {st}")
                print(json.dumps(d, indent=2)); sys.exit(1)
            vid = d["data"]["id"]
            print(f"created App Store version {version_string} with build {build_number} (id {vid})")

        # 3. What's New. Required by App Review on every update after the first,
        # and REFUSED on the first: App Store Connect has no What's New field
        # for a version that follows nothing (PATCH answers 409 STATE_ERROR,
        # "Attribute 'whatsNew' cannot be edited at this time"; seen on the
        # 1.3.0 launch). So skip it when no version has ever shipped, and treat
        # that same 409 as a warning rather than a failed release if the
        # state read misses a case.
        SHIPPED = {"READY_FOR_DISTRIBUTION", "REPLACED_WITH_NEW_VERSION",
                   "PENDING_DEVELOPER_RELEASE", "PENDING_APPLE_RELEASE",
                   "PROCESSING_FOR_DISTRIBUTION", "ACCEPTED"}
        first_release = not any(v["attributes"].get("appVersionState") in SHIPPED
                                for v in versions)
        if notes_path and first_release:
            print(f"version {version_string} is the app's first release: App Store Connect "
                  f"has no What's New field for it, so {notes_path} is not sent.")
        elif notes_path:
            with open(os.path.expanduser(notes_path), "r") as f:
                notes = f.read().strip()
            if not notes:
                print(f"{notes_path} is empty; write the release notes first."); sys.exit(1)
            st, d = call("GET", f"/v1/appStoreVersions/{vid}/appStoreVersionLocalizations"
                                f"?limit=50&fields[appStoreVersionLocalizations]=locale,whatsNew")
            locs = d.get("data", [])
            loc = next((l for l in locs if l["attributes"].get("locale") == "en-US"),
                       locs[0] if locs else None)
            if loc:
                st, d = call("PATCH", f"/v1/appStoreVersionLocalizations/{loc['id']}",
                             {"data": {"type": "appStoreVersionLocalizations", "id": loc["id"],
                                       "attributes": {"whatsNew": notes}}})
                print(f"version {version_string}: What's New ({loc['attributes'].get('locale')}): HTTP {st}")
            else:
                st, d = call("POST", "/v1/appStoreVersionLocalizations",
                             {"data": {"type": "appStoreVersionLocalizations",
                                       "attributes": {"locale": "en-US", "whatsNew": notes},
                                       "relationships": {"appStoreVersion": {
                                           "data": {"type": "appStoreVersions", "id": vid}}}}})
                print(f"version {version_string}: What's New (en-US, created): HTTP {st}")
            if st == 409 and any("whatsNew" in (e.get("detail") or "") for e in d.get("errors", [])):
                print("  What's New cannot be edited on this version (App Store Connect says so); "
                      "continuing without it. Set it in App Store Connect if the review asks.")
            elif st >= 300:
                print(json.dumps(d, indent=2)); sys.exit(1)

        # 4. Submit. A review submission is a container; the version is an item
        # in it. Reuse an open, unsubmitted one for this platform if a previous
        # run got that far, and stop if one is already with App Review.
        st, d = call("GET", f"/v1/reviewSubmissions?filter[app]={app}&filter[platform]=IOS"
                            f"&filter[state]=READY_FOR_REVIEW,WAITING_FOR_REVIEW,IN_REVIEW,UNRESOLVED_ISSUES"
                            f"&fields[reviewSubmissions]=state,submittedDate&limit=10")
        subs = d.get("data", [])
        busy = [x for x in subs if x["attributes"].get("state") in ("WAITING_FOR_REVIEW", "IN_REVIEW")]
        if busy:
            print(f"a review submission is already {busy[0]['attributes']['state']} "
                  f"(id {busy[0]['id']}). Wait for it or cancel it in App Store Connect.")
            sys.exit(1)
        stuck = [x for x in subs if x["attributes"].get("state") == "UNRESOLVED_ISSUES"]
        if stuck:
            print(f"a review submission has UNRESOLVED_ISSUES (id {stuck[0]['id']}); "
                  "resolve or cancel it in App Store Connect, then re-run.")
            sys.exit(1)
        open_sub = next((x for x in subs if x["attributes"].get("state") == "READY_FOR_REVIEW"), None)
        if open_sub:
            sid = open_sub["id"]
            print(f"reusing open review submission {sid}")
        else:
            st, d = call("POST", "/v1/reviewSubmissions",
                         {"data": {"type": "reviewSubmissions",
                                   "attributes": {"platform": "IOS"},
                                   "relationships": {"app": {"data": {"type": "apps", "id": app}}}}})
            if st >= 300 or not d.get("data"):
                print(f"create review submission: HTTP {st}")
                print(json.dumps(d, indent=2)); sys.exit(1)
            sid = d["data"]["id"]
            print(f"created review submission {sid}")
        st, d = call("GET", f"/v1/reviewSubmissions/{sid}/items?include=appStoreVersion&limit=20")
        have = (any((((i.get("relationships") or {}).get("appStoreVersion") or {}).get("data") or {}).get("id") == vid
                    for i in d.get("data", []))
                or any(x.get("type") == "appStoreVersions" and x.get("id") == vid
                       for x in d.get("included", [])))
        if not have:
            st, d = call("POST", "/v1/reviewSubmissionItems",
                         {"data": {"type": "reviewSubmissionItems",
                                   "relationships": {
                                       "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": sid}},
                                       "appStoreVersion": {"data": {"type": "appStoreVersions", "id": vid}}}}})
            print(f"add version {version_string} to submission {sid}: HTTP {st}")
            if st >= 300:
                print(json.dumps(d, indent=2)); sys.exit(1)
        st, d = call("PATCH", f"/v1/reviewSubmissions/{sid}",
                     {"data": {"type": "reviewSubmissions", "id": sid,
                               "attributes": {"submitted": True}}})
        state = (d.get("data") or {}).get("attributes", {}).get("state")
        print(f"submit {version_string} ({build_number}) for App Review: HTTP {st} -> {state or d}")
        if st >= 300:
            sys.exit(1)

    elif cmd == "dist":
        b = newest_valid_build(app)
        if not b:
            print("No VALID build yet — still processing. Try again shortly.")
            return
        bid, ver = b["id"], b["attributes"]["version"]
        prev = prior_build(app, bid)
        if not prev:
            print("No prior build to copy a tester set from; add testers in App Store Connect.")
            return
        st, d = call("GET", f"/v1/builds/{prev['id']}/individualTesters?limit=200&fields[betaTesters]=email")
        testers = [t["id"] for t in d.get("data", [])]
        if not testers:
            print(f"Prior build {prev['attributes']['version']} had no individual testers.")
            return
        st, _ = call("POST", f"/v1/builds/{bid}/relationships/individualTesters",
                     {"data": [{"type": "betaTesters", "id": tid} for tid in testers]})
        print(f"assign build {ver} to {len(testers)} testers (from build "
              f"{prev['attributes']['version']}): HTTP {st}")

    elif cmd == "notify":
        group = sys.argv[2]
        # newest non-expired build that's done processing
        st, d = call("GET", f"/v1/builds?filter[app]={app}&sort=-version&limit=10"
                            f"&fields[builds]=version,processingState,expired")
        valid = [b for b in d.get("data", [])
                 if b["attributes"].get("processingState") == "VALID"
                 and not b["attributes"].get("expired")]
        if not valid:
            print("No VALID (processed) build yet — still processing. Try again shortly.")
            print([f'{b["attributes"]["version"]}:{b["attributes"]["processingState"]}' for b in d.get("data", [])])
            return
        build = valid[0]
        bid, ver = build["id"], build["attributes"]["version"]
        st, d = call("POST", f"/v1/betaGroups/{group}/relationships/builds",
                     {"data": [{"type": "builds", "id": bid}]})
        print(f"add build {ver} -> group {group}: HTTP {st}")
        if d:
            print(json.dumps(d, indent=2))

    elif cmd == "extgroup":
        # Find-or-create an external beta group (groups created via the API are
        # external by default; public links only live on external groups).
        #   python3 tools/asc-api.py extgroup Discord
        name = sys.argv[2]
        st, d = call("GET", f"/v1/betaGroups?filter[app]={app}"
                            f"&filter[name]={urllib.parse.quote(name)}")
        g = next((x for x in d.get("data", [])
                  if x["attributes"].get("name") == name), None)
        if g:
            a = g["attributes"]
            print(f'exists {g["id"]}  "{name}"  internal={a.get("isInternalGroup")}  '
                  f'publicLink={a.get("publicLinkEnabled")}')
            return
        st, d = call("POST", "/v1/betaGroups",
                     {"data": {"type": "betaGroups",
                               "attributes": {"name": name},
                               "relationships": {"app": {"data": {"type": "apps", "id": app}}}}})
        g = d.get("data")
        if st >= 300 or not g:
            print(f"create group \"{name}\": HTTP {st}")
            print(json.dumps(d, indent=2))
            sys.exit(1)
        print(f'created {g["id"]}  "{name}"  internal={g["attributes"].get("isInternalGroup")}')

    elif cmd == "publiclink":
        # Enable (idempotently) and print the group's public TestFlight link.
        #   python3 tools/asc-api.py publiclink Discord
        name = sys.argv[2]
        st, d = call("GET", f"/v1/betaGroups?filter[app]={app}"
                            f"&filter[name]={urllib.parse.quote(name)}")
        g = next((x for x in d.get("data", [])
                  if x["attributes"].get("name") == name), None)
        if not g:
            print(f'no beta group named "{name}" — create it first: asc-api.py extgroup {name}')
            sys.exit(1)
        if g["attributes"].get("isInternalGroup"):
            print(f'"{name}" is an internal group; public links only exist on external groups.')
            sys.exit(1)
        if not g["attributes"].get("publicLinkEnabled"):
            st, d = call("PATCH", f"/v1/betaGroups/{g['id']}",
                         {"data": {"type": "betaGroups", "id": g["id"],
                                   "attributes": {"publicLinkEnabled": True}}})
            if st >= 300:
                print(f"enable public link on \"{name}\": HTTP {st}")
                print(json.dumps(d, indent=2))
                sys.exit(1)
            g = d.get("data") or g
        link = g["attributes"].get("publicLink")
        if link:
            print(link)
        else:
            print("(public link not in the response yet — re-run in a moment)")
            sys.exit(2)

    elif cmd == "whatsnew":
        # Set the TestFlight "What to Test" notes for a build.
        #   python3 tools/asc-api.py whatsnew <version> <path-to-notes-file>
        # Reads the notes from a file (so multi-line text survives the shell).
        want = sys.argv[2] if len(sys.argv) > 2 else None
        path = sys.argv[3] if len(sys.argv) > 3 else None
        if not want or not path:
            print("usage: whatsnew <version> <notes-file>")
            return
        with open(os.path.expanduser(path), "r") as f:
            notes = f.read().strip()
        if not notes:
            print("notes file is empty")
            return
        st, d = call("GET", f"/v1/builds?filter[app]={app}&sort=-version&limit=10"
                            f"&fields[builds]=version,processingState")
        target = next((b for b in d.get("data", [])
                       if b["attributes"].get("version") == want), None)
        if not target:
            print(f"build {want} not listed yet (still registering) — try again shortly.")
            sys.exit(2)
        bid = target["id"]
        # A build gets a default localization once it exists; patch it if present,
        # otherwise create an en-US one.
        st, d = call("GET", f"/v1/builds/{bid}/betaBuildLocalizations"
                            f"?limit=20&fields[betaBuildLocalizations]=locale,whatsNew")
        locs = d.get("data", [])
        loc = next((l for l in locs if l["attributes"].get("locale") == "en-US"),
                   locs[0] if locs else None)
        if loc:
            st, d = call("PATCH", f"/v1/betaBuildLocalizations/{loc['id']}",
                         {"data": {"type": "betaBuildLocalizations", "id": loc["id"],
                                   "attributes": {"whatsNew": notes}}})
            print(f"update what-to-test for build {want} ({loc['attributes'].get('locale')}): HTTP {st}")
        else:
            st, d = call("POST", "/v1/betaBuildLocalizations",
                         {"data": {"type": "betaBuildLocalizations",
                                   "attributes": {"locale": "en-US", "whatsNew": notes},
                                   "relationships": {"build": {"data": {"type": "builds", "id": bid}}}}})
            print(f"create what-to-test for build {want} (en-US): HTTP {st}")
        if st >= 300:
            print(json.dumps(d, indent=2))
            sys.exit(1)


if __name__ == "__main__":
    main()
