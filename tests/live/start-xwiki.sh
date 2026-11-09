#!/usr/bin/env bash
# Downloads (once) and starts a throwaway XWiki 17.10 (Jetty + HSQLDB) for live tests.
# Usage: tests/live/start-xwiki.sh [install-dir]   (default: ../xwiki-live-test next to the repo)
# Needs Java 17+ and curl. Sets the superadmin password to the value in lib.js.
set -euo pipefail
VERSION="${XWIKI_VERSION:-17.10.14}"
DIR="${1:-$(cd "$(dirname "$0")/../../.." && pwd)/xwiki-live-test}"
PORT="${XWIKI_PORT:-8080}"
PASS="${XWIKI_SUPERADMIN_PASSWORD:-testpass123}"
NAME="xwiki-platform-distribution-flavor-jetty-hsqldb-$VERSION"
HOME_DIR="$DIR/$NAME"
mkdir -p "$DIR"
if [ ! -d "$HOME_DIR" ]; then
  URL="https://nexus.xwiki.org/nexus/content/repositories/releases/org/xwiki/platform/xwiki-platform-distribution-flavor-jetty-hsqldb/$VERSION/$NAME.zip"
  curl -fsSL -o "$DIR/$NAME.zip" "$URL"
  unzip -q -o "$DIR/$NAME.zip" -d "$DIR"
fi
CFG="$HOME_DIR/webapps/xwiki/WEB-INF/xwiki.cfg"
sed -i -E "s/^#? ?-?#? ?xwiki\.superadminpassword=.*/xwiki.superadminpassword=$PASS/" "$CFG"
PROPS="$HOME_DIR/webapps/xwiki/WEB-INF/xwiki.properties"
if ! grep -q "^mail.sender.port=2525" "$PROPS"; then
  printf '\n# live tests: send mail to the local SMTP sink (tests/live/smtp_sink.py)\nmail.sender.host=localhost\nmail.sender.port=2525\nmail.sender.from=wiki@example.test\n' >> "$PROPS"
fi
SINK_DIR="$(cd "$(dirname "$0")" && pwd)"
if ! (exec 3<>/dev/tcp/127.0.0.1/2525) 2>/dev/null; then
  nohup python3 "$SINK_DIR/smtp_sink.py" 2525 > "$DIR/smtp-sink.log" 2>&1 &
fi
cd "$HOME_DIR"
nohup ./start_xwiki.sh -p "$PORT" > "$DIR/xwiki.log" 2>&1 &
for i in $(seq 1 120); do
  grep -q "Server started" "$DIR/xwiki.log" 2>/dev/null && { echo "XWiki is up on http://localhost:$PORT/ (log: $DIR/xwiki.log)"; exit 0; }
  sleep 2
done
echo "XWiki did not start in time; see $DIR/xwiki.log" >&2; exit 1
