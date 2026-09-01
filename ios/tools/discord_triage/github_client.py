"""Minimal GitHub REST helpers: read the issue corpus and create new issues.

Kept dependency-light (httpx) and synchronous; callers dispatch via asyncio.to_thread
so the Discord event loop is never blocked.
"""

from __future__ import annotations

import asyncio
import re
from typing import Optional

import httpx

from config import settings

_API = "https://api.github.com"
_HEADERS = {
    "Authorization": f"Bearer {settings.github_token}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
}


class GitHubError(RuntimeError):
    """A GitHub API call failed, carrying enough context to say why.

    The status + GitHub's own error message distinguish the common operational
    faults: 401 = bad/expired token, 404 = repo not granted to the token,
    403 = repo granted but missing the Issues permission.
    """

    def __init__(self, status: int, repo: str, detail: str):
        super().__init__(f"GitHub returned {status} for {repo}: {detail}")
        self.status = status
        self.repo = repo
        self.detail = detail


def _raise_for_status(resp: httpx.Response, repo: str) -> None:
    if resp.status_code < 400:
        return
    try:
        detail = resp.json().get("message") or resp.text[:200]
    except Exception:
        detail = resp.text[:200]
    raise GitHubError(resp.status_code, repo, detail)


# How much of each issue's corpus entry the model sees. Titles alone were enough
# to spot a duplicate but not to tell one apart from a near neighbour, and a
# closed issue's body is where "fixed in 1.12.2" lives.
OPEN_LIMIT = 50
CLOSED_LIMIT = 30
SNIPPET_CHARS = 240

# The bot's own thread stamps are noise in a snippet, and leaking one into the
# model's context invites it to echo a thread id at a reporter.
_MARKER_RE = re.compile(r"<!--\s*discord-thread:\d+\s*-->")


def _snippet(body: str | None) -> str:
    """The first SNIPPET_CHARS of an issue body, flattened to one line."""
    text = _MARKER_RE.sub("", body or "")
    text = " ".join(text.split())
    return text[:SNIPPET_CHARS]


def _fetch_issues_sync(state: str, limit: int, sort: str) -> list[dict]:
    """One page of issues in `state`, newest first, PRs filtered out."""
    url = f"{_API}/repos/{settings.github_repo}/issues"
    params = {
        "state": state,
        "per_page": str(min(limit, 100)),
        "sort": sort,
        "direction": "desc",
    }
    with httpx.Client(timeout=15.0) as client:
        resp = client.get(url, headers=_HEADERS, params=params)
        _raise_for_status(resp, settings.github_repo)
        data = resp.json()
    # The issues endpoint also returns PRs; filter them out.
    return [item for item in data if "pull_request" not in item]


def _issue_corpus_sync() -> list[dict]:
    """Open issues plus recently-closed ones, for dedup.

    Open issues answer "is this already tracked". Closed ones answer a question
    the bot could not previously ask: "was this already FIXED?" — the most
    common re-report there is, since a reporter on an old build hits a bug that
    was closed weeks ago. Without the closed half that arrives as a brand-new
    issue every time.

    Each entry: {number, title, state, state_reason, labels, snippet}. Closed
    issues are sorted by update time so the recently-fixed ones are the ones
    that fit in the window.
    """
    corpus: list[dict] = []
    for state, limit, sort in (("open", OPEN_LIMIT, "created"),
                               ("closed", CLOSED_LIMIT, "updated")):
        for item in _fetch_issues_sync(state, limit, sort):
            corpus.append({
                "number": item["number"],
                "title": item["title"],
                "state": state,
                "state_reason": item.get("state_reason") or "",
                "labels": [
                    lab["name"] if isinstance(lab, dict) else str(lab)
                    for lab in item.get("labels", [])
                ],
                "snippet": _snippet(item.get("body")),
            })
    return corpus


def _create_issue_sync(
    title: str, body: str, labels: list[str], repo: str | None = None
) -> dict:
    """Create an issue and return {number, html_url}."""
    target = repo or settings.github_repo
    url = f"{_API}/repos/{target}/issues"
    # Always tag with the triage label so Discord-sourced issues are filterable.
    all_labels = sorted({*labels, settings.triage_label})
    payload = {"title": title, "body": body, "labels": all_labels}
    with httpx.Client(timeout=15.0) as client:
        resp = client.post(url, headers=_HEADERS, json=payload)
        _raise_for_status(resp, target)
        data = resp.json()
    return {"number": data["number"], "html_url": data["html_url"]}


def _comment_issue_sync(number: int, body: str, repo: str | None = None) -> dict:
    """Add a comment to an existing issue. Returns {html_url}."""
    target = repo or settings.github_repo
    url = f"{_API}/repos/{target}/issues/{number}/comments"
    with httpx.Client(timeout=15.0) as client:
        resp = client.post(url, headers=_HEADERS, json={"body": body})
        _raise_for_status(resp, target)
        data = resp.json()
    return {"html_url": data["html_url"]}


def _find_issue_for_thread_sync(thread_id: int, repos: list[str]) -> Optional[dict]:
    """Find the issue already filed for a Discord thread, by its hidden stamp.

    Every issue the bot opens carries a `<!-- discord-thread:ID -->` marker in
    its body. The thread -> issue map lives in memory, so a restart forgets it;
    this reads the mapping back off GitHub, which is what stops a forgotten
    thread from filing a second issue for a report it already logged.

    Best effort: search is eventually consistent and rate limited, so a miss
    (or an error) just means "not found" and the caller carries on.

    Returns {"number": int, "repo": str} or None.
    """
    for repo in repos:
        if not repo:
            continue
        params = {
            "q": f'repo:{repo} in:body "discord-thread:{thread_id}"',
            "per_page": "5",
            # The issue-search endpoint's newer syntax mode; harmless otherwise.
            "advanced_search": "true",
        }
        try:
            with httpx.Client(timeout=15.0) as client:
                resp = client.get(f"{_API}/search/issues", headers=_HEADERS, params=params)
        except httpx.HTTPError:
            continue
        if resp.status_code >= 400:
            continue
        try:
            items = resp.json().get("items", [])
        except Exception:
            continue
        for item in items:
            if "pull_request" in item:
                continue
            # Search matches on tokens, so confirm the exact stamp is really in
            # the body before adopting the issue as this thread's.
            if f"discord-thread:{thread_id}" not in (item.get("body") or ""):
                continue
            return {"number": item["number"], "repo": repo}
    return None


def _check_repo_access_sync(repo: str) -> Optional[str]:
    """Probe whether the token can read `repo`'s issues.

    Returns None when access looks fine, else a short "status message" string
    describing the problem (e.g. "401 Bad credentials", "404 Not Found").
    """
    url = f"{_API}/repos/{repo}/issues"
    try:
        with httpx.Client(timeout=15.0) as client:
            resp = client.get(url, headers=_HEADERS, params={"per_page": "1"})
    except httpx.HTTPError as err:
        return f"network error: {err}"
    if resp.status_code < 400:
        return None
    try:
        detail = resp.json().get("message") or resp.text[:200]
    except Exception:
        detail = resp.text[:200]
    return f"{resp.status_code} {detail}"


async def issue_corpus() -> list[dict]:
    return await asyncio.to_thread(_issue_corpus_sync)


async def create_issue(
    title: str, body: str, labels: list[str], repo: str | None = None
) -> dict:
    return await asyncio.to_thread(_create_issue_sync, title, body, labels, repo)


async def comment_issue(number: int, body: str, repo: str | None = None) -> dict:
    return await asyncio.to_thread(_comment_issue_sync, number, body, repo)


async def find_issue_for_thread(thread_id: int, repos: list[str]) -> Optional[dict]:
    return await asyncio.to_thread(_find_issue_for_thread_sync, thread_id, repos)


async def check_repo_access(repo: str) -> Optional[str]:
    return await asyncio.to_thread(_check_repo_access_sync, repo)
