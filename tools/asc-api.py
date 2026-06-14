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
"""
import base64, json, os, sys, time, urllib.request, urllib.error

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
