#!/usr/bin/env python3
"""Checks release-note TL;DR blocks against docs/RELEASING.md: the parse rules the upload server applies
(spec 2026-09-07-mod-download-changelog-design.md §4.1) and the style rules it cannot enforce.
Usage: scripts/check_tldr.py FILE...   Exit 1 if any file breaks a rule."""
import re
import sys

STRIP = [(r"\*\*(.+?)\*\*", r"\1"), (r"__(.+?)__", r"\1"), (r"\*(.+?)\*", r"\1"),
         (r"(?<!\w)_(.+?)_(?!\w)", r"\1"), (r"`(.+?)`", r"\1"), (r"\[(.+?)\]\(.+?\)", r"\1")]


def strip(text):
    for pattern, repl in STRIP:
        text = re.sub(pattern, repl, text)
    return text.strip()


def parse(body):
    lines = body.replace("\r\n", "\n").split("\n")
    i = 0
    while i < len(lines) and not lines[i].strip():
        i += 1
    if i == len(lines) or not re.match(r"^##\s+TL;DR\s*$", lines[i], re.I):
        return None, []
    headline, bullets = None, []
    for line in lines[i + 1:]:
        if re.match(r"^---\s*$", line) or re.match(r"^##\s", line):
            break
        m = re.match(r"^[-*]\s+(.+)$", line)
        if m:
            bullets.append(strip(m.group(1)))
        elif line.strip() and headline is None:
            headline = strip(line)
    return headline, bullets


def check(path):
    headline, bullets = parse(open(path, encoding="utf-8").read())
    problems = []
    if headline is None:
        problems.append("no TL;DR block or no headline")
    else:
        if len(headline) > 80:
            problems.append(f"headline is {len(headline)} characters (style: ~80)")
    if not 1 <= len(bullets) <= 5:
        problems.append(f"{len(bullets)} bullets (1-5)")
    for b in bullets:
        if len(b) > 80:
            problems.append(f"bullet is {len(b)} characters (style: ~80): {b}")
        if re.search(r"\b\d+\.\d+\.\d+\b", b):
            problems.append(f"version number in a bullet: {b}")
        if re.match(r"(?i)this release", b):
            problems.append(f"starts with 'This release': {b}")
    print(f"{'FAIL' if problems else 'ok  '} {path}: {headline!r} · {len(bullets)} bullets")
    for p in problems:
        print(f"       - {p}")
    return not problems


if __name__ == "__main__":
    sys.exit(0 if all([check(p) for p in sys.argv[1:]]) else 1)
