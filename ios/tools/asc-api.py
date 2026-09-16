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
                                           # App Review (release after approval);
                                           # ASC_NO_SUBMIT=1 stops before the
                                           # submission
  python3 tools/asc-api.py screenshots <version-string> <display-type> <dir>
                                           # upload the PNGs in <dir> as e.g.
                                           # APP_IPHONE_67 screenshots (idempotent
                                           # by file name)
  python3 tools/asc-api.py prepare <version-string> <metadata-json>
                                           # apply tools/store-metadata.json (age
                                           # rating, categories, listing text,
                                           # URLs, review contact) idempotently
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


def _changes(current: dict, wanted: dict) -> dict:
    """The subset of `wanted` whose values differ from `current` (missing
    counts as different). What `prepare` sends, so a re-run is a no-op."""
    return {k: v for k, v in wanted.items() if current.get(k) != v}


def _apply(kind: str, rid, current: dict, wanted: dict, label: str,
           create=None):
    """PATCH `wanted` onto record `rid` of `kind` where it differs from
    `current`, or POST it via `create(attrs)` when there is no record yet.
    Prints one line per record and exits non-zero on an API error."""
    if rid is None:
        st, d = create(wanted)
        print(f"{label}: created: HTTP {st}")
    else:
        delta = _changes(current, wanted)
        if not delta:
            print(f"{label}: unchanged")
            return
        st, d = call("PATCH", f"/v1/{kind}/{rid}",
                     {"data": {"type": kind, "id": rid, "attributes": delta}})
        print(f"{label}: set {', '.join(sorted(delta))}: HTTP {st}")
    if st >= 300:
        print(json.dumps(d, indent=2)); sys.exit(1)


def prepare(app: str, version_string: str, meta_path: str):
    """Apply the checked-in store metadata to App Store Connect.

    Everything App Review needs that is not the binary: the age rating
    questionnaire, categories, the app-level listing (name, subtitle,
    privacy policy URL), the version-level listing (description, keywords,
    support and marketing URLs, copyright), the content rights
    declaration, a price schedule and territory availability when the app
    has none yet, and the App Review contact.
    Idempotent: each record is read first and only the fields that differ
    are written, so a re-run prints "unchanged" down the column.

    The review contact is personal data and is not in the JSON: it comes
    from ASC_REVIEW_FIRST_NAME, ASC_REVIEW_LAST_NAME, ASC_REVIEW_PHONE and
    ASC_REVIEW_EMAIL. When any is unset the contact is left as it is, with
    a note, because an existing version keeps the contact from the last
    one and App Store Connect says plainly at submission if it is missing.

    The age rating answers are judgment calls recorded in the JSON: the
    only user text others see is a filtered 2 to 12 character leaderboard
    display name (no feed, chat or profiles), so userGeneratedContent is
    false; Contest mode simulates a radio contest with no prize, so
    contests is NONE.
    """
    with open(os.path.expanduser(meta_path), "r") as f:
        meta = json.load(f)

    # 1. The editable app info. An app has one per platform group; the one
    # whose state is editable is the one the next submission reads.
    st, d = call("GET", f"/v1/apps/{app}/appInfos?fields[appInfos]=state&limit=10")
    if st != 200:
        print(json.dumps(d, indent=2)); sys.exit(1)
    EDITABLE_INFO = {"PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED",
                     "READY_FOR_REVIEW"}
    info = next((i for i in d.get("data", [])
                 if i["attributes"].get("state") in EDITABLE_INFO), None)
    if not info:
        states = [i["attributes"].get("state") for i in d.get("data", [])]
        print(f"no editable app info (states: {states}); nothing to prepare.")
        sys.exit(1)
    info_id = info["id"]
    print(f"app info {info_id}: {info['attributes'].get('state')}")

    # 2. Age rating. The declaration shares the app info's id and always
    # exists; unanswered questions read as null, and App Review refuses a
    # submission while any is null.
    if meta.get("ageRating"):
        st, d = call("GET", f"/v1/appInfos/{info_id}/ageRatingDeclaration")
        decl = d.get("data") or {}
        if st != 200 or not decl:
            print(f"age rating declaration: HTTP {st}"); print(json.dumps(d, indent=2)); sys.exit(1)
        _apply("ageRatingDeclarations", decl["id"], decl.get("attributes", {}),
               meta["ageRating"], "age rating")

    # 3. Categories, which are relationships rather than attributes.
    cats = meta.get("categories") or {}
    if cats:
        st, d = call("GET", f"/v1/appInfos/{info_id}?include=primaryCategory,secondaryCategory"
                            f"&fields[appInfos]=primaryCategory,secondaryCategory")
        rels = (d.get("data") or {}).get("relationships") or {}
        have = {k: ((rels.get(f"{k}Category") or {}).get("data") or {}).get("id")
                for k in ("primary", "secondary")}
        want = {k: cats.get(k) for k in ("primary", "secondary") if cats.get(k)}
        delta = {k: v for k, v in want.items() if have.get(k) != v}
        if not delta:
            print("categories: unchanged")
        else:
            st, d = call("PATCH", f"/v1/appInfos/{info_id}",
                         {"data": {"type": "appInfos", "id": info_id,
                                   "relationships": {
                                       f"{k}Category": {"data": {"type": "appCategories", "id": v}}
                                       for k, v in delta.items()}}})
            print(f"categories: set {', '.join(f'{k}={v}' for k, v in sorted(delta.items()))}: HTTP {st}")
            if st >= 300:
                print(json.dumps(d, indent=2)); sys.exit(1)

    # 4. App-level listing text per locale: name, subtitle, privacy URL.
    if meta.get("appInfo"):
        st, d = call("GET", f"/v1/appInfos/{info_id}/appInfoLocalizations?limit=50")
        locs = {l["attributes"].get("locale"): l for l in d.get("data", [])}
        for locale, wanted in meta["appInfo"].items():
            loc = locs.get(locale)
            _apply("appInfoLocalizations", loc["id"] if loc else None,
                   loc["attributes"] if loc else {}, wanted, f"app info {locale}",
                   create=lambda attrs, locale=locale: call(
                       "POST", "/v1/appInfoLocalizations",
                       {"data": {"type": "appInfoLocalizations",
                                 "attributes": dict(attrs, locale=locale),
                                 "relationships": {"appInfo": {
                                     "data": {"type": "appInfos", "id": info_id}}}}}))

    # 5. The App Store version for this version string. `appstore` creates
    # or renames it before this runs; it is not created here because the
    # build attach decides which one is editable.
    st, d = call("GET", f"/v1/apps/{app}/appStoreVersions?filter[platform]=IOS"
                        f"&fields[appStoreVersions]=versionString,appVersionState,copyright&limit=50")
    ver = next((v for v in d.get("data", [])
                if v["attributes"].get("versionString") == version_string), None)
    if not ver:
        print(f"no App Store version {version_string}; run `appstore` first to create it.")
        sys.exit(1)
    vid = ver["id"]
    print(f"App Store version {version_string}: {ver['attributes'].get('appVersionState')} (id {vid})")
    if meta.get("version"):
        _apply("appStoreVersions", vid, ver["attributes"], meta["version"],
               f"version {version_string}")

    # 6. Version-level listing text per locale. What's New is deliberately
    # not here: it is per release and `appstore` sets it from the notes file.
    if meta.get("versionLocalizations"):
        st, d = call("GET", f"/v1/appStoreVersions/{vid}/appStoreVersionLocalizations?limit=50")
        locs = {l["attributes"].get("locale"): l for l in d.get("data", [])}
        for locale, wanted in meta["versionLocalizations"].items():
            if "whatsNew" in wanted:
                print(f"version {locale}: whatsNew belongs in the notes file, not the metadata; ignored.")
                wanted = {k: v for k, v in wanted.items() if k != "whatsNew"}
            loc = locs.get(locale)
            _apply("appStoreVersionLocalizations", loc["id"] if loc else None,
                   loc["attributes"] if loc else {}, wanted, f"version {locale}",
                   create=lambda attrs, locale=locale: call(
                       "POST", "/v1/appStoreVersionLocalizations",
                       {"data": {"type": "appStoreVersionLocalizations",
                                 "attributes": dict(attrs, locale=locale),
                                 "relationships": {"appStoreVersion": {
                                     "data": {"type": "appStoreVersions", "id": vid}}}}}))

    # 7. Content rights, price and availability. App-level, not per
    # version, and a price schedule or availability that exists is left
    # alone: changing either is a pricing decision to make in App Store
    # Connect, not something a re-run should silently redo.
    if "contentRightsDeclaration" in meta:
        st, d = call("GET", f"/v1/apps/{app}?fields[apps]=contentRightsDeclaration")
        _apply("apps", app, (d.get("data") or {}).get("attributes", {}),
               {"contentRightsDeclaration": meta["contentRightsDeclaration"]}, "content rights")
    pricing = meta.get("pricing")
    if pricing:
        st, d = call("GET", f"/v1/apps/{app}/appPriceSchedule?include=manualPrices,baseTerritory"
                            f"&fields[appPrices]=manual&limit[manualPrices]=5")
        if st == 200 and d.get("data"):
            base = ((d["data"].get("relationships") or {}).get("baseTerritory") or {}).get("data") or {}
            print(f"price schedule: exists (base territory {base.get('id')}); left as is")
        else:
            base = pricing.get("baseTerritory", "USA")
            st, d = call("GET", f"/v1/apps/{app}/appPricePoints?filter[territory]={base}"
                                f"&fields[appPricePoints]=customerPrice&limit=200")
            want_price = float(pricing.get("customerPrice", 0))
            point = next((p for p in d.get("data", [])
                          if float(p["attributes"].get("customerPrice") or -1) == want_price), None)
            if not point:
                print(f"price schedule: no {base} price point at {want_price}: HTTP {st}")
                print(json.dumps(d, indent=2)[:2000]); sys.exit(1)
            st, d = call("POST", "/v1/appPriceSchedules",
                         {"data": {"type": "appPriceSchedules",
                                   "relationships": {
                                       "app": {"data": {"type": "apps", "id": app}},
                                       "baseTerritory": {"data": {"type": "territories", "id": base}},
                                       "manualPrices": {"data": [{"type": "appPrices", "id": "${price0}"}]}}},
                          "included": [{"type": "appPrices", "id": "${price0}",
                                        "attributes": {"startDate": None},
                                        "relationships": {"appPricePoint": {
                                            "data": {"type": "appPricePoints", "id": point["id"]}}}}]})
            print(f"price schedule: created, {base} at {want_price}: HTTP {st}")
            if st >= 300:
                print(json.dumps(d, indent=2)); sys.exit(1)
    avail = meta.get("availability")
    if avail:
        st, d = call("GET", f"/v1/apps/{app}/appAvailabilityV2?fields[appAvailabilities]=availableInNewTerritories")
        if st == 200 and d.get("data"):
            print(f"availability: exists (availableInNewTerritories="
                  f"{d['data']['attributes'].get('availableInNewTerritories')}); left as is")
        else:
            st, d = call("GET", "/v1/territories?limit=200")
            terr = [t["id"] for t in d.get("data", [])]
            if not terr:
                print(f"availability: no territories listed: HTTP {st}"); sys.exit(1)
            if avail.get("territories") not in (None, "all"):
                terr = [t for t in terr if t in set(avail["territories"])]
            st, d = call("POST", "/v2/appAvailabilities",
                         {"data": {"type": "appAvailabilities",
                                   "attributes": {"availableInNewTerritories":
                                                  bool(avail.get("availableInNewTerritories", True))},
                                   "relationships": {
                                       "app": {"data": {"type": "apps", "id": app}},
                                       "territoryAvailabilities": {"data": [
                                           {"type": "territoryAvailabilities", "id": f"${{t{i}}}"}
                                           for i in range(len(terr))]}}},
                          "included": [{"type": "territoryAvailabilities", "id": f"${{t{i}}}",
                                        "attributes": {"available": True},
                                        "relationships": {"territory": {
                                            "data": {"type": "territories", "id": t}}}}
                                       for i, t in enumerate(terr)]})
            print(f"availability: created for {len(terr)} territories: HTTP {st}")
            if st >= 300:
                print(json.dumps(d, indent=2)[:3000]); sys.exit(1)

    # 8. App Review contact. Personal data, so from the environment.
    contact_env = {"contactFirstName": "ASC_REVIEW_FIRST_NAME",
                   "contactLastName": "ASC_REVIEW_LAST_NAME",
                   "contactPhone": "ASC_REVIEW_PHONE",
                   "contactEmail": "ASC_REVIEW_EMAIL"}
    contact = {k: os.environ.get(v, "").strip() for k, v in contact_env.items()}
    wanted = dict(meta.get("reviewDetail") or {})
    st, d = call("GET", f"/v1/appStoreVersions/{vid}/appStoreReviewDetail")
    detail = d.get("data")
    if all(contact.values()):
        wanted.update(contact)
    else:
        missing = [v for k, v in contact_env.items() if not contact[k]]
        if detail and detail.get("attributes", {}).get("contactEmail"):
            print(f"review contact: kept as is ({', '.join(missing)} unset)")
        else:
            print(f"review contact: NOT SET and {', '.join(missing)} unset; "
                  "App Review needs a contact. Export them and re-run.")
    if wanted:
        _apply("appStoreReviewDetails", detail["id"] if detail else None,
               detail.get("attributes", {}) if detail else {}, wanted, "review detail",
               create=lambda attrs: call(
                   "POST", "/v1/appStoreReviewDetails",
                   {"data": {"type": "appStoreReviewDetails", "attributes": attrs,
                             "relationships": {"appStoreVersion": {
                                 "data": {"type": "appStoreVersions", "id": vid}}}}}))


def screenshots(app: str, version_string: str, display_type: str, directory: str,
                locale: str = "en-US"):
    """Upload the PNGs in `directory` (sorted by name) as the screenshots of
    one display type on the version's localization, e.g. APP_IPHONE_67 for
    the 6.9-inch iPhone (1320x2868). Idempotent by file name: the set is
    created if missing and a file whose name is already in it is skipped, so
    a re-run uploads nothing. To replace a screenshot, delete it in App Store
    Connect (or rename the file) and re-run."""
    import hashlib
    files = sorted(f for f in os.listdir(os.path.expanduser(directory)) if f.lower().endswith(".png"))
    if not files:
        print(f"no PNGs in {directory}"); sys.exit(1)
    st, d = call("GET", f"/v1/apps/{app}/appStoreVersions?filter[platform]=IOS"
                        f"&fields[appStoreVersions]=versionString&limit=50")
    ver = next((v for v in d.get("data", [])
                if v["attributes"].get("versionString") == version_string), None)
    if not ver:
        print(f"no App Store version {version_string}"); sys.exit(1)
    st, d = call("GET", f"/v1/appStoreVersions/{ver['id']}/appStoreVersionLocalizations?limit=50")
    loc = next((l for l in d.get("data", []) if l["attributes"].get("locale") == locale), None)
    if not loc:
        print(f"no {locale} localization on {version_string}; run `prepare` first."); sys.exit(1)
    st, d = call("GET", f"/v1/appStoreVersionLocalizations/{loc['id']}/appScreenshotSets"
                        f"?fields[appScreenshotSets]=screenshotDisplayType&limit=50")
    sset = next((s for s in d.get("data", [])
                 if s["attributes"].get("screenshotDisplayType") == display_type), None)
    if sset:
        print(f"screenshot set {display_type}: exists ({sset['id']})")
    else:
        st, d = call("POST", "/v1/appScreenshotSets",
                     {"data": {"type": "appScreenshotSets",
                               "attributes": {"screenshotDisplayType": display_type},
                               "relationships": {"appStoreVersionLocalization": {
                                   "data": {"type": "appStoreVersionLocalizations", "id": loc["id"]}}}}})
        if st >= 300 or not d.get("data"):
            print(f"create screenshot set {display_type}: HTTP {st}"); print(json.dumps(d, indent=2)); sys.exit(1)
        sset = d["data"]
        print(f"screenshot set {display_type}: created ({sset['id']})")
    st, d = call("GET", f"/v1/appScreenshotSets/{sset['id']}/appScreenshots"
                        f"?fields[appScreenshots]=fileName,assetDeliveryState&limit=50")
    have = {s["attributes"].get("fileName"): s for s in d.get("data", [])}
    for name in files:
        if name in have:
            state = (have[name]["attributes"].get("assetDeliveryState") or {}).get("state")
            print(f"  {name}: already uploaded ({state})")
            continue
        path = os.path.join(os.path.expanduser(directory), name)
        with open(path, "rb") as f:
            blob = f.read()
        st, d = call("POST", "/v1/appScreenshots",
                     {"data": {"type": "appScreenshots",
                               "attributes": {"fileName": name, "fileSize": len(blob)},
                               "relationships": {"appScreenshotSet": {
                                   "data": {"type": "appScreenshotSets", "id": sset["id"]}}}}})
        if st >= 300 or not d.get("data"):
            print(f"  {name}: reserve: HTTP {st}"); print(json.dumps(d, indent=2)); sys.exit(1)
        shot = d["data"]
        for op in shot["attributes"].get("uploadOperations") or []:
            chunk = blob[op["offset"]:op["offset"] + op["length"]]
            req = urllib.request.Request(op["url"], data=chunk, method=op["method"])
            for h in op.get("requestHeaders") or []:
                req.add_header(h["name"], h["value"])
            with urllib.request.urlopen(req) as r:
                if r.status >= 300:
                    print(f"  {name}: chunk upload HTTP {r.status}"); sys.exit(1)
        st, d = call("PATCH", f"/v1/appScreenshots/{shot['id']}",
                     {"data": {"type": "appScreenshots", "id": shot["id"],
                               "attributes": {"uploaded": True,
                                              "sourceFileChecksum": hashlib.md5(blob).hexdigest()}}})
        state = ((d.get("data") or {}).get("attributes", {}).get("assetDeliveryState") or {}).get("state")
        print(f"  {name}: uploaded {len(blob)} bytes: HTTP {st} -> {state}")
        if st >= 300:
            print(json.dumps(d, indent=2)); sys.exit(1)


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

        # The release script runs `prepare` between the build attach and the
        # submission, because App Review refuses a version whose age rating,
        # categories or listing are missing, and those live on records that
        # only exist once the version does.
        if os.environ.get("ASC_NO_SUBMIT") == "1":
            print(f"ASC_NO_SUBMIT=1: version {version_string} ({build_number}) is attached and not submitted.")
            return

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

    elif cmd == "prepare":
        #   python3 tools/asc-api.py prepare 1.3.0 tools/store-metadata.json
        if len(sys.argv) < 4:
            print("usage: prepare <version-string> <metadata-json>")
            sys.exit(2)
        prepare(app, sys.argv[2], sys.argv[3])

    elif cmd == "screenshots":
        #   python3 tools/asc-api.py screenshots 1.3.0 APP_IPHONE_67 ~/shots/iphone69
        if len(sys.argv) < 5:
            print("usage: screenshots <version-string> <display-type> <png-directory>")
            sys.exit(2)
        screenshots(app, sys.argv[2], sys.argv[3], sys.argv[4])

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
