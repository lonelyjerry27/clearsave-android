#!/bin/bash
# Copies the shared web screens from the clearsave-web project into the Android app's assets.
# Run this after changing anything in clearsave-web/static.
set -e
cd "$(dirname "$0")/.."
SRC="${1:-../clearsave-web/static}"
DEST=app/src/main/assets
mkdir -p "$DEST"
for f in index.html app.css app.js; do
  cp "$SRC/$f" "$DEST/$f"
done
echo "Synced $(ls "$DEST" | wc -l | tr -d ' ') files from $SRC"
