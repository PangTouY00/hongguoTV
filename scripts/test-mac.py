#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Opt-in live smoke test for the built Mac helper and native video decoder. No account required."""
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parent.parent
resources = root / "outputs/mac/HongguoTV.app/Contents/Resources"
process = subprocess.Popen([str(resources / "runtime/bin/java"), "--add-modules", "jdk.httpserver", "-cp", str(resources / "lib/*"), "com.hongguotv.desktop.MainKt"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
try:
    base = process.stdout.readline().strip()
    assert base.startswith("http://127.0.0.1:")
    def get(path, **params):
        with opener.open(base + path + "?" + urllib.parse.urlencode(params), timeout=90) as response:
            return json.load(response)
    def rejected(url, status, headers=None):
        try:
            opener.open(urllib.request.Request(url, headers=headers or {}), timeout=10)
            raise AssertionError("Expected rejection")
        except urllib.error.HTTPError as error:
            assert error.code == status, error.code
    rejected(base.replace(base.split('/')[-2], "invalid") + "search?q=test", 403)
    rejected(base + "search?q=test", 403, {"Origin": "https://example.com"})
    first = get("search", q="大佬", page=1)
    second = get("search", q="大佬", page=2)
    assert first["items"] and second["items"]
    assert {s["id"] for s in second["items"]} - {s["id"] for s in first["items"]}
    detail = get("detail", id=first["items"][0]["id"])
    assert detail["episodes"]
    print("Search, pagination, details and private bridge access: PASS", flush=True)
    play = get("play", id=detail["episodes"][0])
    with opener.open(urllib.request.Request(play["url"], headers={"Range": "bytes=0-1"}), timeout=60) as response:
        assert response.status == 206 and len(response.read()) == 2
    rejected(play["url"], 416, {"Range": "bytes=999999999999-"})
    with tempfile.TemporaryDirectory(prefix="hongguotv-mac-test-") as temporary:
        decoder = Path(temporary) / "playback-check"
        subprocess.run(["swiftc", "-parse-as-library", str(root / "desktop-mac/tests/PlaybackSmoke.swift"), "-o", str(decoder)], check=True)
        result = subprocess.run([str(decoder), play["url"]], capture_output=True, text=True, timeout=100)
        # The local session URL is intentionally never printed, including compiler/runtime errors.
        if result.returncode != 0:
            raise RuntimeError("Native streamed playback test failed; session URL suppressed")
        print(result.stdout.strip(), flush=True)
    get("stop")
    rejected(play["url"], 404)
finally:
    process.stdin.close()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=10)
    assert process.returncode == 0
    print("Playback cleanup and helper shutdown: PASS")
