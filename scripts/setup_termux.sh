#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
if [ ! -d /data/data/com.termux/files/usr ]; then
  echo 'This script must be run inside Android Termux.' >&2; exit 1
fi
SOURCE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$HOME/tablet-agent"
echo 'Installing local runtime dependencies (Python, Node.js, Git)'
pkg update -y
pkg install -y python git nodejs-lts
mkdir -p "$DEST/bridge" "$DEST/workspaces"
cp "$SOURCE/bridge/core.py" "$SOURCE/bridge/server.py" "$DEST/bridge/"
chmod 700 "$DEST" "$DEST/bridge" "$DEST/workspaces"
chmod 600 "$DEST/bridge/"*.py
cat <<EOF

Local bridge installed at $DEST/bridge
NEXT:
  1. Install Codex CLI with a supported native Termux package (check: pkg search codex),
     then authenticate using 'codex login' in Termux.
  2. Run: python ~/tablet-agent/bridge/server.py
  3. Paste the printed device token into Tablet Agent > Settings.
  4. Optional 'Start bridge' button: in ~/.termux/termux.properties set
     allow-external-apps=true and grant RUN_COMMAND permission to Tablet Agent.

If the Android OS kills Termux, a running task may be interrupted.
EOF
