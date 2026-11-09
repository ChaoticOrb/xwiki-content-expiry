#!/usr/bin/env bash
# Builds the XAR and runs every live test against a running XWiki (see start-xwiki.sh).
set -euo pipefail
cd "$(dirname "$0")"
export NODE_PATH="${NODE_PATH:-$(npm root -g)}"
python3 ../../build.py
for t in install reset seed report settings access warn pageui job "$@"; do echo; echo "== $t =="; node "$t.js"; done
