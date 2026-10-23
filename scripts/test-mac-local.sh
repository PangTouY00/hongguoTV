#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT
swiftc -parse-as-library "$ROOT/desktop-mac/Sources/Models.swift" "$ROOT/desktop-mac/Sources/LocalLibrary.swift" "$ROOT/desktop-mac/tests/LibraryTests.swift" -o "$TESTDIR/library"
"$TESTDIR/library"
swiftc -parse-as-library "$ROOT/desktop-mac/Sources/MacUpdates.swift" "$ROOT/desktop-mac/tests/MacUpdateTests.swift" -o "$TESTDIR/updates"
"$TESTDIR/updates"
SOURCES=()
for source in "$ROOT/desktop-mac/Sources/"*.swift; do
    if [ "$(basename "$source")" != App.swift ]; then SOURCES+=("$source"); fi
done
swiftc -parse-as-library "${SOURCES[@]}" "$ROOT/desktop-mac/tests/CloudHistoryTests.swift" -o "$TESTDIR/cloud"
"$TESTDIR/cloud"
swiftc -parse-as-library "${SOURCES[@]}" "$ROOT/desktop-mac/tests/PlayerControlsTests.swift" -o "$TESTDIR/controls"
"$TESTDIR/controls"
swiftc -parse-as-library "${SOURCES[@]}" "$ROOT/desktop-mac/tests/HomeFeedTests.swift" -o "$TESTDIR/feed"
"$TESTDIR/feed"
