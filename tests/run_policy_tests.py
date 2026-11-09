#!/usr/bin/env python3
"""Compile the engine's Policy class with javac and run its tests.

The Policy class in src/pages/ContentExpiry/Code/Engine.groovy (between the
POLICY-BEGIN and POLICY-END markers) is written so it is valid Java as well as
Groovy. This script copies it out as a top-level Java class, compiles it with
tests/PolicyTest.java and runs the tests. Needs a JDK (javac and java on PATH).

Usage: python3 tests/run_policy_tests.py
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENGINE = ROOT / "src" / "pages" / "ContentExpiry" / "Code" / "Engine.groovy"
TEST = ROOT / "tests" / "PolicyTest.java"


def extract_policy():
    text = ENGINE.read_text(encoding="utf-8")
    match = re.search(r"// POLICY-BEGIN\n(.*?)// POLICY-END", text, re.S)
    if not match:
        sys.exit("POLICY-BEGIN / POLICY-END markers not found in Engine.groovy")
    body = match.group(1)
    # Groovy traps that compile in Java but behave differently in Groovy.
    problems = []
    if "$" in body:
        problems.append("contains '$' (Groovy would treat the string as a GString)")
    if re.search(r"'[^']*'", body.replace("\\'", "")):
        problems.append("contains a single-quoted literal (a String in Groovy, a char in Java)")
    if re.search(r"[\w)\]]\s*/\s*[\w(]", re.sub(r"/\*.*?\*/|//[^\n]*", "", body, flags=re.S)):
        problems.append("uses '/' (integer division returns a decimal in Groovy; use Math.floorDiv)")
    if problems:
        sys.exit("Policy class is not Groovy-safe: " + "; ".join(problems))
    return body.replace("    static class Policy", "class Policy", 1)


def main():
    for tool in ("javac", "java"):
        if shutil.which(tool) is None:
            sys.exit(f"{tool} not found; install a JDK to run these tests")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        (tmp / "Policy.java").write_text(extract_policy(), encoding="utf-8")
        shutil.copy(TEST, tmp / "PolicyTest.java")
        subprocess.run(["javac", "-d", str(tmp), str(tmp / "Policy.java"), str(tmp / "PolicyTest.java")],
                       check=True)
        result = subprocess.run(["java", "-Duser.timezone=Europe/London", "-cp", str(tmp), "PolicyTest"])
        return result.returncode


if __name__ == "__main__":
    sys.exit(main())
