#!/usr/bin/env python3
"""Minimal App Store Connect API client (ES256 JWT, no third-party deps beyond
`cryptography`). Reads credentials from the env exported by tools/asc-auth.sh.

Usage:
  source tools/asc-auth.sh
  python3 tools/asc-api.py groups          # list beta groups
  python3 tools/asc-api.py builds          # recent builds + processing state
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


if __name__ == "__main__":
    main()
