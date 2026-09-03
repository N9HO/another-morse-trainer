"""Tests for recovering a thread's issue from its stamp, against a fake GitHub.

The thread -> issue map lives in memory, so a restart forgets it. `find_issue_
for_thread` is the backstop that reads the mapping back off GitHub — and in
production its search request was returning 422 while the code swallowed every
4xx with a bare `continue`, so a forgotten thread was told "no issue" and would
file a second one for a report it had already logged. Nothing in the suite
touched this module, so nothing said.

Run standalone or under pytest:

    DISCORD_BOT_TOKEN=x ANTHROPIC_API_KEY=x GITHUB_TOKEN=x \\
      GITHUB_REPO=owner/repo python3 test_github_client.py
"""

from __future__ import annotations

import os

os.environ.setdefault("DISCORD_BOT_TOKEN", "x")
os.environ.setdefault("ANTHROPIC_API_KEY", "x")
os.environ.setdefault("GITHUB_TOKEN", "x")
os.environ.setdefault("GITHUB_REPO", "n9ho/another-morse-trainer")

import github_client  # noqa: E402

REPO = "n9ho/another-morse-trainer"
THREAD = 1545067282982768681


def _issue(number: int, thread_id: int | None = None, **kw) -> dict:
    body = kw.get("body", "### Steps\n…")
    if thread_id is not None:
        body += f"\n\n<!-- discord-thread:{thread_id} -->"
    return {"number": number, "title": "QSO simulator freezes", "body": body}


class FakeResponse:
    def __init__(self, status_code: int, payload, text: str = ""):
        self.status_code, self._payload, self.text = status_code, payload, text

    def json(self):
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


class FakeGitHub:
    """Answers /search/issues and /repos/.../issues, and records every call."""

    def __init__(self, search, pages):
        self.search, self.pages = search, list(pages)
        self.calls: list[tuple[str, dict]] = []

    def __call__(self, *a, **kw):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def get(self, url, headers=None, params=None):
        self.calls.append((url, dict(params or {})))
        if "/search/issues" in url:
            if isinstance(self.search, Exception):
                raise self.search
            return self.search
        page = int((params or {}).get("page", 1))
        items = self.pages[page - 1] if page <= len(self.pages) else []
        return FakeResponse(200, items)

    @property
    def searched(self) -> int:
        return sum(1 for url, _ in self.calls if "/search/issues" in url)

    @property
    def scanned(self) -> int:
        return sum(1 for url, _ in self.calls if "/search/issues" not in url)


class fake_github:
    def __init__(self, search=None, pages=()):
        self.api = FakeGitHub(search if search is not None else FakeResponse(200, {"items": []}),
                              pages)

    def __enter__(self):
        self._saved = github_client.httpx.Client
        github_client.httpx.Client = self.api
        return self.api

    def __exit__(self, *exc):
        github_client.httpx.Client = self._saved
        return False


def _find(thread_id: int = THREAD):
    return github_client._find_issue_for_thread_sync(thread_id, [REPO])


# --- tests --------------------------------------------------------------------


def test_a_search_422_falls_back_to_scanning_recent_issues():
    """The production failure. Search answered 422 and the report was told
    there was no issue, so the thread filed a second one."""
    unprocessable = FakeResponse(422, {"message": "Validation Failed"})
    with fake_github(search=unprocessable, pages=[[_issue(41, THREAD)]]) as api:
        found = _find()

    assert found == {"number": 41, "repo": REPO}, f"the scan should have found it: {found}"
    assert api.searched == 1, "search is still tried first"
    assert api.scanned >= 1, "and the scan is what answers when it fails"


def test_a_search_hit_costs_no_scan():
    hit = FakeResponse(200, {"items": [_issue(41, THREAD)]})
    with fake_github(search=hit, pages=[[]]) as api:
        assert _find() == {"number": 41, "repo": REPO}
    assert api.scanned == 0, "search answered; the scan is wasted requests"


def test_a_network_error_on_search_still_scans():
    with fake_github(search=github_client.httpx.HTTPError("boom"),
                     pages=[[_issue(41, THREAD)]]) as api:
        assert _find() == {"number": 41, "repo": REPO}
    assert api.scanned >= 1


def test_an_unrelated_thread_is_not_adopted():
    with fake_github(pages=[[_issue(41, 999999999999999999)]]):
        assert _find() is None, "that issue belongs to a different thread"


def test_a_thread_id_that_prefixes_another_is_not_a_match():
    """`"discord-thread:123" in body` is true for a body stamped 1234, which
    would hand this thread somebody else's issue."""
    with fake_github(pages=[[_issue(41, 12345)]]):
        assert _find(1234) is None


def test_an_unstamped_issue_is_not_a_match():
    with fake_github(pages=[[_issue(41)]]):
        assert _find() is None


def test_a_pull_request_carrying_the_stamp_is_skipped():
    pr = _issue(42, THREAD)
    pr["pull_request"] = {"url": "…"}
    with fake_github(pages=[[pr, _issue(41, THREAD)]]):
        assert _find() == {"number": 41, "repo": REPO}


def test_the_scan_reads_a_second_page_before_giving_up():
    first = [_issue(n) for n in range(100, 100 + github_client.SCAN_PER_PAGE)]
    with fake_github(pages=[first, [_issue(41, THREAD)]]) as api:
        assert _find() == {"number": 41, "repo": REPO}
    assert api.scanned == 2


def test_a_short_page_ends_the_scan():
    """A page that isn't full is the last one; asking for another is a wasted
    request on every lookup that finds nothing."""
    with fake_github(pages=[[_issue(41)], [_issue(42, THREAD)]]) as api:
        assert _find() is None
    assert api.scanned == 1


def test_a_failed_lookup_is_logged_not_swallowed(capsys=None):
    import logging

    records: list[str] = []

    class Capture(logging.Handler):
        def emit(self, record):
            records.append(record.getMessage())

    handler = Capture()
    github_client.log.addHandler(handler)
    try:
        with fake_github(search=FakeResponse(422, {"message": "Validation Failed"}),
                         pages=[[]]):
            _find()
    finally:
        github_client.log.removeHandler(handler)

    assert any("422" in line for line in records), (
        f"a swallowed 422 is why this went unnoticed for months: {records}"
    )


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(dict(globals()).items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            print(f"ok   {name}")
        except AssertionError as err:
            failures += 1
            print(f"FAIL {name}: {err}")
    raise SystemExit(1 if failures else 0)
