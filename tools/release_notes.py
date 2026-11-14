#!/usr/bin/env python3
"""Helpers for the release workflow.

Usage:
    tools/release_notes.py --check v0.4.0   fail unless the tag matches src/package.json
    tools/release_notes.py v0.4.0           print the CHANGELOG.md section for that version
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def version_of(tag):
    return tag[1:] if tag.startswith("v") else tag


def check(tag):
    wanted = json.loads((ROOT / "src" / "package.json").read_text(encoding="utf-8"))["version"]
    if version_of(tag) != wanted:
        sys.exit(f"Tag {tag} does not match the version {wanted} in src/package.json")
    notes(tag)  # also fails if the changelog has no section for it


def notes(tag):
    version = version_of(tag)
    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    m = re.search(rf"^## {re.escape(version)}\b[^\n]*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not m or not m.group(1).strip():
        sys.exit(f"CHANGELOG.md has no section for {version}")
    return m.group(1).strip()


def main(argv):
    if len(argv) == 3 and argv[1] == "--check":
        check(argv[2])
        print("ok")
    elif len(argv) == 2:
        print(notes(argv[1]))
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
