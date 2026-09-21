#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h:h}"
cd "$ROOT"
swift build -c release

APP="${ROOT}/dist/EchoCards.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/arm64-apple-macosx/release/EchoCardsMac "$APP/Contents/MacOS/EchoCardsMac"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>EchoCards</string>
<key>CFBundleDisplayName</key><string>知声卡</string>
<key>CFBundleIdentifier</key><string>com.orange.echocards.mac</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleShortVersionString</key><string>0.1.0</string>
<key>CFBundleExecutable</key><string>EchoCardsMac</string>
<key>LSMinimumSystemVersion</key><string>26.0</string>
<key>NSMicrophoneUsageDescription</key><string>知声卡需要使用麦克风进行跟读识别。</string>
<key>NSSpeechRecognitionUsageDescription</key><string>知声卡需要语音识别来判断跟读是否完成。</string>
</dict></plist>
PLIST

codesign --force --deep --sign - "$APP"
codesign --verify --deep --strict "$APP"
echo "Packaged and verified: $APP"
