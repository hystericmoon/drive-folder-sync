"""Appends the failed tests from JUnit XML reports to the GitHub run summary (readable on a phone)."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

title, patterns = sys.argv[1], sys.argv[2:]
lines = []
for pattern in patterns:
    for path in sorted(glob.glob(pattern, recursive=True)):
        for case in ET.parse(path).getroot().iter("testcase"):
            for f in case.findall("failure") + case.findall("error"):
                msg = (f.get("message") or f.text or "").strip().splitlines()
                cls = (case.get("classname") or "").rsplit(".", 1)[-1]
                lines.append(f"- **{cls} › {case.get('name')}**: {msg[0][:200] if msg else ''}")
out = [f"### {title}", ""] + (lines or ["No test report found; see the step log."])
with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
    summary.write("\n".join(out) + "\n")
