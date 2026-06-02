"""Minimal GitHub REST helpers: list open issues and create new ones.

Kept dependency-light (httpx) and synchronous; callers dispatch via asyncio.to_thread
so the Discord event loop is never blocked.
"""

from __future__ import annotations

import asyncio

import httpx

from config import settings

_API = "https://api.github.com"
_HEADERS = {
    "Authorization": f"Bearer {settings.github_token}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
}


def _list_open_issues_sync(limit: int = 50) -> list[dict]:
    """Return open issues as [{number, title}], excluding pull requests."""
    url = f"{_API}/repos/{settings.github_repo}/issues"
    params = {"state": "open", "per_page": str(min(limit, 100)), "sort": "created"}
    with httpx.Client(timeout=15.0) as client:
        resp = client.get(url, headers=_HEADERS, params=params)
        resp.raise_for_status()
        data = resp.json()
    # The issues endpoint also returns PRs; filter them out.
    return [
        {"number": item["number"], "title": item["title"]}
        for item in data
        if "pull_request" not in item
    ]


def _create_issue_sync(title: str, body: str, labels: list[str]) -> dict:
    """Create an issue and return {number, html_url}."""
    url = f"{_API}/repos/{settings.github_repo}/issues"
    # Always tag with the triage label so Discord-sourced issues are filterable.
    all_labels = sorted({*labels, settings.triage_label})
    payload = {"title": title, "body": body, "labels": all_labels}
    with httpx.Client(timeout=15.0) as client:
        resp = client.post(url, headers=_HEADERS, json=payload)
        resp.raise_for_status()
        data = resp.json()
    return {"number": data["number"], "html_url": data["html_url"]}


async def list_open_issues(limit: int = 50) -> list[dict]:
    return await asyncio.to_thread(_list_open_issues_sync, limit)


async def create_issue(title: str, body: str, labels: list[str]) -> dict:
    return await asyncio.to_thread(_create_issue_sync, title, body, labels)
