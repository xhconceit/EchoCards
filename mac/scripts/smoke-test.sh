#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h:h}"
APP="$ROOT/dist/EchoCards.app"
test -x "$APP/Contents/MacOS/EchoCardsMac"
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Contents/Info.plist")" = "com.orange.echocards.mac"
file "$APP/Contents/MacOS/EchoCardsMac" | grep -q arm64
codesign --verify --deep --strict "$APP"
open "$APP"
sleep 2
pgrep -f "$APP/Contents/MacOS/EchoCardsMac" >/dev/null
pkill -f "$APP/Contents/MacOS/EchoCardsMac" || true
echo "EchoCards.app smoke test passed"
