#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_HOME="$JDK"
GRADLE="${HONGGUOTV_GRADLE:-gradle}"
DEST="$ROOT/outputs/mac"
APP="$DEST/HongguoTV.app"
"$GRADLE" -p "$ROOT/desktop-mac" :desktop:installDist --console=plain
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/lib"
find "$APP/Contents/Resources/lib" -maxdepth 1 -name '*.jar' -delete
cp "$ROOT/desktop-mac/backend/build/install/desktop/lib/"*.jar "$APP/Contents/Resources/lib/"
if [ ! -x "$APP/Contents/Resources/runtime/bin/java" ]; then
    "$JDK/bin/jlink" --add-modules java.se,jdk.httpserver,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --output "$APP/Contents/Resources/runtime"
fi
swiftc -parse-as-library -O -target "$(uname -m)-apple-macosx13.0" \
    "$ROOT/desktop-mac/Sources/HongguoMac.swift" -o "$APP/Contents/MacOS/HongguoTV"
cp "$ROOT/desktop-mac/Info.plist" "$APP/Contents/Info.plist"
cp "$ROOT/LICENSE" "$APP/Contents/Resources/LICENSE"
cp "$ROOT/kotlin-tv/NOTICE.md" "$APP/Contents/Resources/NOTICE.md"
codesign --force --deep --sign - "$APP"
echo "$APP"
