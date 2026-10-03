#!/usr/bin/env python3
"""Apply a release decision: bump versions, changelog, commit, tag, push.

The decision JSON comes from ask_bump.py (or --force-bump). Version numbers
are computed here from the live files — never trusted from the model.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_GRADLE = REPO_ROOT / "android-fido-client" / "app" / "build.gradle.kts"
DAEMON_PYPROJECT = REPO_ROOT / "linux-fido-daemon" / "pyproject.toml"
CHANGELOG = REPO_ROOT / "CHANGELOG.md"
ALLOWED_BUMP = ("major", "minor", "patch")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--decision", type=Path, required=True, help="Decision JSON from ask_bump.py")
    p.add_argument("--dry-run", action="store_true", help="Print actions; do not write or push")
    p.add_argument("--push", action="store_true", help="Commit, tag, and push with RELEASE_TOKEN")
    p.add_argument(
        "--token-env",
        default="RELEASE_TOKEN",
        help="Env var holding the git push token (fine-grained PAT / app)",
    )
    p.add_argument(
        "--git-user-name",
        default="github-actions[bot]",
    )
    p.add_argument(
        "--git-user-email",
        default="41981417+github-actions[bot]@users.noreply.github.com",
    )
    return p.parse_args()


def run(cmd: list[str], *, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        cwd=REPO_ROOT,
        check=check,
        text=True,
        capture_output=capture,
    )


def read_version_name() -> str:
    text = ANDROID_GRADLE.read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not m:
        raise SystemExit(f"versionName not found in {ANDROID_GRADLE}")
    return m.group(1)


def read_version_code() -> int:
    text = ANDROID_GRADLE.read_text(encoding="utf-8")
    m = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not m:
        raise SystemExit(f"versionCode not found in {ANDROID_GRADLE}")
    return int(m.group(1))


def read_pyproject_version() -> str:
    text = DAEMON_PYPROJECT.read_text(encoding="utf-8")
    m = re.search(r'^version\s*=\s*"([^"]+)"', text, re.MULTILINE)
    if not m:
        raise SystemExit(f"version not found in {DAEMON_PYPROJECT}")
    return m.group(1)


def parse_semver(version: str) -> tuple[int, int, int]:
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
    if not m:
        raise SystemExit(f"unsupported version format: {version!r}")
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def next_version(current: str, bump: str) -> str:
    major, minor, patch = parse_semver(current)
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    if bump == "patch":
        return f"{major}.{minor}.{patch + 1}"
    raise SystemExit(f"invalid bump {bump!r}")


def version_code_for(version: str) -> int:
    major, minor, patch = parse_semver(version)
    return major * 10000 + minor * 100 + patch


def set_android_versions(version_name: str, version_code: int) -> None:
    text = ANDROID_GRADLE.read_text(encoding="utf-8")
    new_text, n1 = re.subn(
        r'versionName\s*=\s*"[^"]+"',
        f'versionName = "{version_name}"',
        text,
        count=1,
    )
    new_text, n2 = re.subn(
        r"versionCode\s*=\s*\d+",
        f"versionCode = {version_code}",
        new_text,
        count=1,
    )
    if n1 != 1 or n2 != 1:
        raise SystemExit("failed to patch versionName/versionCode in build.gradle.kts")
    ANDROID_GRADLE.write_text(new_text, encoding="utf-8")


def set_pyproject_version(version: str) -> None:
    text = DAEMON_PYPROJECT.read_text(encoding="utf-8")
    new_text, n = re.subn(
        r'^version\s*=\s*"[^"]+"',
        f'version = "{version}"',
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if n != 1:
        raise SystemExit("failed to patch version in pyproject.toml")
    DAEMON_PYPROJECT.write_text(new_text, encoding="utf-8")


def changelog_body(raw: str, version: str) -> str:
    body = raw.strip() + "\n"
    # Force the heading to the recomputed version.
    body = re.sub(
        r"^##\s+v?[0-9][^\n]*\n",
        f"## v{version}\n",
        body,
        count=1,
        flags=re.MULTILINE,
    )
    if not body.startswith("## "):
        body = f"## v{version}\n\n{body}"
    return body


def update_changelog(version: str, body: str) -> None:
    entry = changelog_body(body, version)
    if not CHANGELOG.exists():
        CHANGELOG.write_text(
            "# Changelog\n\nAll notable changes to this project.\n\n" + entry + "\n",
            encoding="utf-8",
        )
        return
    text = CHANGELOG.read_text(encoding="utf-8")
    if "## v" not in text and "## " not in text:
        text = text.rstrip() + "\n\n" + entry + "\n"
    else:
        # Insert after the first heading block (title + intro).
        parts = re.split(r"(?m)^(##\s+)", text, maxsplit=1)
        if len(parts) == 1:
            text = text.rstrip() + "\n\n" + entry + "\n"
        else:
            # parts = [pre, '##\s+', rest] — re.split keeps the delimiter.
            prefix = parts[0]
            rest = parts[1] + parts[2] if len(parts) > 2 else parts[1]
            text = prefix + entry + "\n" + rest
    CHANGELOG.write_text(text, encoding="utf-8")


def git_remote_with_token(token: str) -> str:
    url = run(["git", "config", "--get", "remote.origin.url"], capture=True).stdout.strip()
    if url.startswith("https://"):
        return f"https://x-access-token:{token}@{url.removeprefix('https://')}"
    return url


def main() -> None:
    args = parse_args()
    decision = json.loads(args.decision.read_text(encoding="utf-8"))
    bump = decision.get("bump")
    if bump not in ALLOWED_BUMP:
        raise SystemExit(f"decision bump must be one of {ALLOWED_BUMP}, got {bump!r}")
    changelog = str(decision.get("changelog") or "").strip()
    if not changelog:
        raise SystemExit("decision.changelog is empty")

    current_name = read_version_name()
    current_code = read_version_code()
    current_py = read_pyproject_version()
    if current_name != current_py:
        raise SystemExit(
            f"version mismatch before bump: versionName={current_name} pyproject={current_py}"
        )

    version = next_version(current_name, bump)
    code = version_code_for(version)
    if code <= current_code:
        raise SystemExit(f"versionCode would not increase: {current_code} -> {code}")

    tag = f"v{version}"
    existing = run(["git", "tag", "-l", tag], capture=True).stdout.strip()
    if existing:
        raise SystemExit(f"tag already exists: {tag}")

    print(f"current={current_name} (code {current_code})")
    print(f"bump={bump} -> version={version} (code {code}) tag={tag}")
    print(f"rationale: {decision.get('rationale', '')}")

    if args.dry_run:
        print("dry-run: no files written, no commit/tag/push")
        return

    set_android_versions(version, code)
    set_pyproject_version(version)
    update_changelog(version, changelog)

    run(["git", "config", "user.name", args.git_user_name])
    run(["git", "config", "user.email", args.git_user_email])
    run(
        [
            "git",
            "add",
            str(ANDROID_GRADLE.relative_to(REPO_ROOT)),
            str(DAEMON_PYPROJECT.relative_to(REPO_ROOT)),
            str(CHANGELOG.relative_to(REPO_ROOT)),
        ]
    )
    status = run(["git", "status", "--porcelain"], capture=True).stdout.strip()
    if not status:
        raise SystemExit("nothing to commit after bump (files already at target version?)")

    run(["git", "commit", "-m", f"release: {tag}"])
    run(["git", "tag", "-a", tag, "-m", f"Release {tag}"])

    if not args.push:
        print("commit + tag created locally; skip push (pass --push)")
        return

    token = os.environ.get(args.token_env, "").strip()
    if not token:
        raise SystemExit(f"missing push token in env {args.token_env}")
    remote = git_remote_with_token(token)
    run(["git", "push", remote, "HEAD:master"])
    run(["git", "push", remote, tag])
    print(f"pushed master + {tag}")


if __name__ == "__main__":
    main()
