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
