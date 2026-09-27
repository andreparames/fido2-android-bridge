"""PROTOCOL.md conformance checks.

Every ```json block in the protocol spec must parse with json.loads so the
schema/example blocks can be reused as authoritative fixtures.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PROTOCOL_MD = REPO_ROOT / "PROTOCOL.md"

JSON_BLOCK_RE = re.compile(r"```json\n(.*?)```", re.DOTALL)


def test_protocol_md_exists() -> None:
    assert PROTOCOL_MD.is_file(), f"missing {PROTOCOL_MD}"


def test_protocol_md_json_blocks_parse() -> None:
    text = PROTOCOL_MD.read_text(encoding="utf-8")
    blocks = JSON_BLOCK_RE.findall(text)
    assert blocks, "no ```json blocks found in PROTOCOL.md"
    for block in blocks:
        json.loads(block)


def test_protocol_md_no_js_string_concatenation() -> None:
    text = PROTOCOL_MD.read_text(encoding="utf-8")
    concat = '" + "'
    for block in JSON_BLOCK_RE.findall(text):
        assert concat not in block, "JSON block contains JS string concatenation"