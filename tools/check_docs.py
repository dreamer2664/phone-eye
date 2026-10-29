#!/usr/bin/env python3
"""Docs may not drift from the code (pc-eye's rule, inherited).

Run from anywhere:  python3 tools/check_docs.py

Checks, mechanically:
  1. every route in Server.kt ROUTES is documented in PHONE-EYE.md and docs/API.md
  2. every /act action in Server.kt ACTIONS is documented in both
  3. versionName (app/build.gradle.kts) == Core.VERSION, and CHANGELOG.md has that release
  4. the cloudflared pin (version + SHA-256) matches between Gradle and scripts/fetch-cloudflared.sh
  5. README points agents at PHONE-EYE.md and humans at the release APK

A failure here is a build failure in CI. Fix the docs or the code until they agree —
they are the same fact stated twice, and the second copy rots first.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
failures: list[str] = []


def read(rel: str) -> str:
    p = ROOT / rel
    if not p.exists():
        failures.append(f"missing file: {rel}")
        return ""
    return p.read_text(encoding="utf-8")


server = read("app/src/main/java/com/dreamer2664/phoneeye/Server.kt")
phone_eye = read("PHONE-EYE.md")
api = read("docs/API.md")
readme = read("README.md")
changelog = read("CHANGELOG.md")
gradle = read("app/build.gradle.kts")
core = read("app/src/main/java/com/dreamer2664/phoneeye/Core.kt")
fetch = read("scripts/fetch-cloudflared.sh")

# 1. routes
m = re.search(r"val ROUTES = listOf\((.*?)\)", server, re.S)
routes = re.findall(r'"(/[^"]*)"', m.group(1)) if m else []
if len(routes) < 10:
    failures.append(f"could not parse ROUTES from Server.kt (found {len(routes)})")
for route in routes:
    if route == "/":
        continue  # "/" appears everywhere; the health route is covered by eye
    for name, doc in (("PHONE-EYE.md", phone_eye), ("docs/API.md", api)):
        if route not in doc:
            failures.append(f"route {route} is in Server.kt but missing from {name}")

# 2. actions
m = re.search(r"val ACTIONS.*?linkedMapOf\((.*?)\)\s*\n\s*val KEYS", server, re.S)
actions = re.findall(r'"([a-z_]+)" to \(', m.group(1)) if m else []
if len(actions) < 5:
    failures.append(f"could not parse ACTIONS from Server.kt (found {actions})")
for action in actions:
    for name, doc in (("PHONE-EYE.md", phone_eye), ("docs/API.md", api)):
        if action not in doc:
            failures.append(f"action {action!r} is in Server.kt but missing from {name}")

# 3. version agreement
gv = re.search(r'versionName = "([^"]+)"', gradle)
cv = re.search(r'VERSION = "([^"]+)"', core)
if not gv or not cv:
    failures.append("version pins not found in app/build.gradle.kts / Core.kt")
else:
    if gv.group(1) != cv.group(1):
        failures.append(f"version mismatch: gradle {gv.group(1)} != Core.VERSION {cv.group(1)}")
    if f"## {gv.group(1)}" not in changelog:
        failures.append(f"CHANGELOG.md has no '## {gv.group(1)}' section")

# 4. cloudflared pin agreement
gv2 = re.search(r'cloudflaredVersion = "([^"]+)"', gradle)
gs = re.search(r'cloudflaredSha256 = "([0-9a-f]{64})"', gradle)
if not gv2 or not gs:
    failures.append("cloudflared pin not found in app/build.gradle.kts")
else:
    if gv2.group(1) not in fetch:
        failures.append(f"scripts/fetch-cloudflared.sh does not name cloudflared {gv2.group(1)}")
    if gs.group(1) not in fetch:
        failures.append("scripts/fetch-cloudflared.sh does not carry the same cloudflared SHA-256 as Gradle")

# 5. README pointers
if "PHONE-EYE.md" not in readme:
    failures.append("README.md does not point agents at PHONE-EYE.md")
if "releases" not in readme:
    failures.append("README.md does not tell humans where the APK lives (GitHub Releases)")

if failures:
    print("check_docs: FAILED")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

print(f"check_docs: OK — {len(routes)} routes and {len(actions)} actions documented in PHONE-EYE.md + docs/API.md; "
      f"version + cloudflared pins agree.")
