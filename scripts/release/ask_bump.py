#!/usr/bin/env python3
"""Ask OpenCode Go for a semver bump + changelog.

Reads a commit log on stdin (or --log-file), calls the OpenCode Go
chat-completions API, and writes a decision JSON file.

The model only proposes. Version numbers are recomputed later by bump.py.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

DEFAULT_MODEL = "opencode-go/longcat-2.5-preview-free"
ZEN_URL = "https://opencode.ai/zen/go/v1/chat/completions"
ALLOWED_BUMP = ("major", "minor", "patch")

SYSTEM_PROMPT = """You are a release manager for a monorepo (Android client + Python daemon).
Given the current version and git history since the last release, decide a semver bump.

Rules:
- major: breaking protocol/API changes, or incompatible wire-format changes
- minor: new features, backwards-compatible protocol additions
- patch: fixes, docs, tests, CI, refactors with no user-visible feature

Reply with ONLY a JSON object, no markdown fences, no prose outside JSON:
{
  "bump": "major" | "minor" | "patch",
  "rationale": "one short sentence",
  "changelog": "markdown body for the release notes, starting with ## vNEXT\\n\\n then bullet groups (Added/Fixed/Changed) if relevant"
}

Do not invent a version number. Do not mention unreleased plans.
"""


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--current-version", required=True, help="Current versionName, e.g. 0.1.0")
    p.add_argument("--log-file", type=Path, help="Commit log file (default: stdin)")
    p.add_argument("--out", type=Path, required=True, help="Path to write decision JSON")
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--force-bump", choices=ALLOWED_BUMP, help="Override the model decision")
    p.add_argument(
        "--api-key-env",
        default="OPENCODE_GO_API_KEY",
        help="Env var holding the OpenCode Go API key",
    )
    return p.parse_args()


def read_log(args: argparse.Namespace) -> str:
    if args.log_file is not None:
        text = args.log_file.read_text(encoding="utf-8")
    else:
        text = sys.stdin.read()
    text = text.strip()
    if not text:
        raise SystemExit("empty commit log")
    return text


def extract_json(content: str) -> dict:
    content = content.strip()
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?\s*", "", content)
        content = re.sub(r"\s*```$", "", content)
    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        start = content.find("{")
        end = content.rfind("}")
        if start < 0 or end <= start:
            raise SystemExit(f"model did not return JSON:\n{content[:2000]}")
        data = json.loads(content[start : end + 1])
    if not isinstance(data, dict):
        raise SystemExit("model JSON is not an object")
    return data


def session_id() -> str:
    """Stable per-conversation id for OpenCode Go routing/prompt cache."""
    run_id = os.environ.get("GITHUB_RUN_ID", "").strip()
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "").strip()
    if run_id:
        return f"release-prepare-{run_id}" + (f"-{attempt}" if attempt else "")
    return f"release-prepare-{uuid.uuid4().hex}"


def call_opencode_go(api_key: str, model: str, user_prompt: str) -> dict:
    # OpenCode config uses opencode-go/<id>; the Zen HTTP API wants the bare id.
    model_id = model.removeprefix("opencode-go/")
    body = {
        "model": model_id,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        "temperature": 0.2,
    }
    # Best-effort structured output; some free models ignore response_format.
    body["response_format"] = {"type": "json_object"}

    req = urllib.request.Request(
        ZEN_URL,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": "fido2-android-bridge-release/1.0",
            # Required by OpenCode Go: MissingSessionID otherwise.
            "x-opencode-session": session_id(),
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"OpenCode Go HTTP {exc.code}: {detail[:2000]}") from exc
    except urllib.error.URLError as exc:
        raise SystemExit(f"OpenCode Go request failed: {exc}") from exc

    try:
        content = payload["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        raise SystemExit(f"unexpected API response shape: {json.dumps(payload)[:2000]}") from exc
    if not isinstance(content, str) or not content.strip():
        raise SystemExit("model returned empty content")
    return extract_json(content)


def validate_decision(data: dict, force_bump: str | None) -> dict:
    bump = force_bump or data.get("bump")
    if bump not in ALLOWED_BUMP:
        raise SystemExit(f"invalid bump {bump!r}; expected one of {ALLOWED_BUMP}")
    rationale = str(data.get("rationale") or "").strip()
    changelog = str(data.get("changelog") or "").strip()
    if not changelog:
        raise SystemExit("model returned empty changelog")
    return {"bump": bump, "rationale": rationale, "changelog": changelog}


def main() -> None:
    args = parse_args()
    api_key = os.environ.get(args.api_key_env, "").strip()
    if not api_key:
        raise SystemExit(f"missing API key in env {args.api_key_env}")

    log = read_log(args)
    user_prompt = (
        f"Current version: {args.current_version}\n\n"
        f"Commits since last release (merge-first log):\n\n{log}\n"
    )
    raw = call_opencode_go(api_key, args.model, user_prompt)
    decision = validate_decision(raw, args.force_bump)
    decision["model"] = args.model
    decision["current_version"] = args.current_version

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(decision, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: decision[k] for k in ("bump", "rationale", "model")}, indent=2))
    print(f"wrote {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
