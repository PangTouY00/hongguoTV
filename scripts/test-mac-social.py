#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Opt-in anonymous checks for the packaged Mac comments and timed-comment bridge."""
import json
from pathlib import Path
import subprocess
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parent.parent
resources = root / "outputs/mac/hongguoMac.app/Contents/Resources"
process = subprocess.Popen([str(resources / "runtime/bin/java"), "--add-modules", "jdk.httpserver", "-cp", str(resources / "lib/*"), "com.hongguotv.desktop.MainKt"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
try:
    base = process.stdout.readline().strip()
    assert base.startswith("http://127.0.0.1:")
    series, episode = "7503745441910017049", "7503780574541122622"
    seen, cursor = set(), ""
    for batch in [1, 2]:
        params = urllib.parse.urlencode(dict(series=series, episode=episode, cursor=cursor))
        with opener.open(base + "comment-page?" + params, timeout=30) as response:
            page = json.load(response)
        assert page["seriesID"] == series and page["episodeID"] == episode
        ids = {item["id"] for item in page["items"]}
        assert ids and len(ids) == len(page["items"]) and ids - seen
        assert page["hasMore"] and page["nextCursor"] and page["nextCursor"] != cursor
        print(f"App comments: batch={batch}, items={len(ids)}, new IDs={len(ids-seen)}, cursor advanced: PASS", flush=True)
        seen.update(ids)
        cursor = page["nextCursor"]
    for start in [0, 30000]:
        params = urllib.parse.urlencode(dict(series=series, episode=episode, start=start, duration=120000))
        with opener.open(base + "danmaku?" + params, timeout=30) as response:
            data = json.load(response)
        assert data["seriesID"] == series and data["episodeID"] == episode
        assert data["startMS"] == start and start < data["nextMS"] <= 120000
        assert data["items"] and len({item["id"] for item in data["items"]}) == len(data["items"])
        assert all(start <= item["timeMS"] < data["nextMS"] and item["text"] for item in data["items"])
        print(f"Timed comments: start={start}, next={data['nextMS']}, valid items={len(data['items'])}: PASS", flush=True)
    with opener.open(base + "comments?" + urllib.parse.urlencode(dict(series=series, episode=episode)), timeout=50) as response:
        data = json.load(response)
    assert data["seriesID"] == series and data["episodeID"] == episode and data["limited"]
    print(f"Public excerpts: episode={len(data['comments']['items'])}, series={len(data['reviews']['items'])}: PASS")
finally:
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
