#!/usr/bin/env python3
"""The slugs HEAD's designs.json publishes and the working copy's does not, comma-separated.

For intake_nightly.sh's "designs would drop" notification (backlog #104). A file rather
than inline `python3 -c`, because Python inside a bash function's indentation is one
stray indent away from an IndentationError that only a failing night would find.
"""
import json
import pathlib
import subprocess

DESIGNS = "src/main/resources/seed/designs.json"
REPO = pathlib.Path(__file__).resolve().parent.parent

head = json.loads(subprocess.check_output(["git", "-C", str(REPO), "show", f"HEAD:{DESIGNS}"]))
now = json.loads((REPO / DESIGNS).read_text())
print(", ".join(sorted({d["slug"] for d in head} - {d["slug"] for d in now})) or "(none)")
