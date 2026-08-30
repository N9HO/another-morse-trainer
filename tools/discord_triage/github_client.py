"""Minimal GitHub REST helpers: list open issues and create new ones.

Kept dependency-light (httpx) and synchronous; callers dispatch via asyncio.to_thread
so the Discord event loop is never blocked.
"""

from __future__ import annotations

import asyncio
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


def _list_open_issues_sync(limit: int = 50) -> list[dict]:
    """Return open issues as [{number, title}], excluding pull requests."""
    url = f"{_API}/repos/{settings.github_repo}/issues"
    params = {"state": "open", "per_page": str(min(limit, 100)), "sort": "created"}
    with httpx.Client(timeout=15.0) as client:
        resp = client.get(url, headers=_HEADERS, params=params)
        _raise_for_status(resp, settings.github_repo)
        data = resp.json()
    # The issues endpoint also returns PRs; filter them out.
    return [
        {"number": item["number"], "title": item["title"]}
        for item in data
        if "pull_request" not in item
    ]


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


async def list_open_issues(limit: int = 50) -> list[dict]:
    return await asyncio.to_thread(_list_open_issues_sync, limit)


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
