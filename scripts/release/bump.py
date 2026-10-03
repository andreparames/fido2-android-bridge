#!/usr/bin/env python3
"""Apply a release decision: bump versions, changelog, commit, tag, push.

The decision JSON comes from ask_bump.py. Version numbers are computed here
from the live files — never trusted from the model.

Remote write order (intentional):
  1. Fail if the target tag already exists on origin (leave it alone).
  2. Apply local version bump + changelog, commit, create local tag.
  3. Push the tag first. If that push fails (e.g. race), abort — master is
     not updated.
  4. Push the version-bump commit to master second (silent; GITHUB_TOKEN
     in CI does not trigger another workflow run).

Both pushes use the checkout credentials (workflow GITHUB_TOKEN). Builds and
the GitHub Release happen in the same CI job after this script returns.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_GRADLE = REPO_ROOT / "android-fido-client" / "app" / "build.gradle.kts"
DAEMON_PYPROJECT = REPO_ROOT / "linux-fido-daemon" / "pyproject.toml"
CHANGELOG = REPO_ROOT / "CHANGELOG.md"
ALLOWED_BUMP = ("major", "minor", "patch")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--decision", type=Path, required=True, help="Decision JSON from ask_bump.py")
    p.add_argument(
        "--skip-push",
        action="store_true",
        help="Local only: edit files, commit, tag; do not push (for tests)",
    )
    p.add_argument(
        "--out",
        type=Path,
        help="Write JSON {bump,version,version_code,tag} here for CI",
    )
    p.add_argument("--git-user-name", default="github-actions[bot]")
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


def remote_tag_exists(tag: str) -> bool:
    proc = run(
        ["git", "ls-remote", "--tags", "origin", f"refs/tags/{tag}"],
        check=False,
        capture=True,
    )
    if proc.returncode != 0:
        raise SystemExit(f"git ls-remote failed: {proc.stderr.strip() or proc.stdout.strip()}")
    return bool(proc.stdout.strip())


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
    # Replace the first markdown H2 (model may write "## vNEXT" or "## 0.2.0").
    body = re.sub(
        r"^##\s+[^\n]*\n",
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
    marker = "<!-- Entries are prepended by scripts/release/bump.py on each release. -->"
    if marker in text:
        head, _, tail = text.partition(marker)
        text = head + marker + "\n\n" + entry + "\n" + tail.lstrip("\n")
    else:
        parts = re.split(r"(?m)^(##\s+)", text, maxsplit=1)
        if len(parts) == 1:
            text = text.rstrip() + "\n\n" + entry + "\n"
        else:
            prefix = parts[0]
            rest = parts[1] + parts[2] if len(parts) > 2 else parts[1]
            text = prefix + entry + "\n" + rest
    CHANGELOG.write_text(text, encoding="utf-8")


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

    if remote_tag_exists(tag):
        raise SystemExit(
            f"tag {tag} already exists on origin; refusing to overwrite. "
            f"Pick a new version or delete the remote tag deliberately."
        )
    local = run(["git", "tag", "-l", tag], capture=True).stdout.strip()
    if local:
        raise SystemExit(f"tag {tag} already exists locally; refusing to overwrite")

    print(f"current={current_name} (code {current_code})")
    print(f"bump={bump} -> version={version} (code {code}) tag={tag}")
    print(f"rationale: {decision.get('rationale', '')}")

    if args.out is not None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(
            json.dumps(
                {
                    "bump": bump,
                    "version": version,
                    "version_code": code,
                    "tag": tag,
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )

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

    if args.skip_push:
        print("skip-push: local commit + tag created; not pushing")
        return

    print(f"pushing tag {tag} first…")
    run(["git", "push", "origin", f"refs/tags/{tag}"])
    print("pushing version bump to master (silent)…")
    run(["git", "push", "origin", "HEAD:master"])
    print(f"pushed tag {tag} + master")


if __name__ == "__main__":
    main()
