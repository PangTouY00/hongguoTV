#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Generate the independent Mac update manifest from a built bundle and its ZIP."""
import argparse
import hashlib
import json
import plistlib
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--app", type=Path, required=True)
parser.add_argument("--zip", type=Path, required=True)
args = parser.parse_args()
with (args.app / "Contents/Info.plist").open("rb") as source:
    metadata = plistlib.load(source)
digest = hashlib.sha256()
with args.zip.open("rb") as archive:
    for chunk in iter(lambda: archive.read(1024 * 1024), b""):
        digest.update(chunk)
manifest = dict(schema=1, bundleID=metadata["CFBundleIdentifier"], build=int(metadata["CFBundleVersion"]),
                version=metadata["CFBundleShortVersionString"], architecture="arm64",
                minMacOS=int(metadata["LSMinimumSystemVersion"].split(".")[0]), fileName=args.zip.name,
                size=args.zip.stat().st_size, sha256=digest.hexdigest(),
                notes="Mac 试验版：首页刷剧、下一部预加载、我的入口、相关作品封面、系统全屏及全屏内选集、清晰度和倍速。")
(args.zip.parent / "mac-update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
