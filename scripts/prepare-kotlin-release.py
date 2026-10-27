#!/usr/bin/env python3
"""Package the built APKs with an updater manifest, using their actual metadata.

一次发布可以带多个构建（同一个 versionCode / versionName，不同的 minSdk）：
`--apk` 是默认包（Android 6.0+ 构建），`--variant-apk` 追加其它构建（如 Android 4.4）。
清单顶层沿用默认包，给不认识 variants 的旧客户端；`variants` 让新客户端按系统版本选包。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

PACKAGE = "com.hongguotv.nativeapp"
# minSdk -> 文件名后缀。android8 保留给曾经发布过的 Android 8.0 单独构建。
ANDROID_BY_SDK = {19: "android44", 23: "android6", 26: "android8"}


def inspect(apk, aapt):
    """读 APK 的包名 / 版本 / minSdk，拒绝不符合发布规则的产物。"""
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
    fields = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    package, version = fields["name"], fields["versionName"]
    code = int(fields["versionCode"])
    # aapt 输出 sdkVersion，aapt2 输出 minSdkVersion，两种工具都要能读。
    sdk = int(re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", badging, re.M).group(1))
    if package != PACKAGE or not 1 <= code <= 2147483647:
        raise ValueError(f"{apk} 的包名或 versionCode 不支持发布")
    if not re.fullmatch(r"[0-9][0-9A-Za-z.+-]{0,63}", version):
        raise ValueError(f"{apk} 的 versionName 不支持发布：{version}")
    android = ANDROID_BY_SDK.get(sdk)
    if not android:
        raise ValueError(f"{apk} 的 minSdk 是 {sdk}，只支持 {sorted(ANDROID_BY_SDK)}")
    size = Path(apk).stat().st_size
    if not 1 <= size <= 80 * 1024 * 1024:
        raise ValueError(f"{apk} 超过更新清单 80 MB 上限")
    return dict(apk=Path(apk), version=version, code=code, sdk=sdk, android=android, size=size)


def prepare(apk, aapt, output, tag=None, notes="", release_notes=None, variants=()):
    builds = [inspect(apk, aapt)] + [inspect(item, aapt) for item in variants]
    base = builds[0]
    for build in builds[1:]:
        if (build["version"], build["code"]) != (base["version"], base["code"]):
            raise ValueError("同一次发布的所有构建必须使用相同的 versionName / versionCode")
    version, code = base["version"], base["code"]
    if tag is None:
        match = re.match(r"\d+\.\d+\.\d+", version)
        if not match:
            raise ValueError("versionName 不是语义版本，必须用 --tag 指定标签")
        tag = "kotlin-v" + match.group()
    if not re.fullmatch(r"kotlin-v[0-9][0-9A-Za-z.+-]{0,80}", tag):
        raise ValueError("不支持的发布标签")
    if len(notes) > 1500:
        raise ValueError("更新清单的简短说明不能超过 1500 字")

    suffix = "-" + base["android"]
    stem = version[: -len(suffix)] if version.endswith(suffix) else version
    destination = Path(output) / tag
    destination.mkdir(parents=True, exist_ok=True)

    entries = []
    for build in sorted(builds, key=lambda item: item["sdk"]):
        name = f"hongguotv-kotlin-{stem}-{build['android']}.apk"
        target = destination / name
        if build["apk"].resolve() != target.resolve():
            shutil.copyfile(build["apk"], target)
        entries.append(dict(minSdk=build["sdk"], size=target.stat().st_size,
                            sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                            apk=dict(name=name, size=target.stat().st_size,
                                     sha256=hashlib.sha256(target.read_bytes()).hexdigest())))

    # 顶层默认包取 minSdk 最高的构建（Android 6.0+），和 0.1.11 及以前的行为保持一致。
    primary = entries[-1]
    manifest = dict(schema=1, packageName=PACKAGE, versionCode=code, versionName=version,
                    minSdk=primary["minSdk"], tag=tag, apk=primary["apk"], notes=notes)
    if len(entries) > 1:
        # 低版本设备上的新客户端会读 variants：Android 4.4 选 minSdk 19 的包，6.0+ 选 23 的包。
        manifest["variants"] = [dict(minSdk=item["minSdk"], apk=item["apk"]) for item in entries]
    temporary = destination / "update.json.tmp"
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, destination / "update.json")
    if notes or release_notes:
        (destination / "release-notes.md").write_text((release_notes or notes).strip() + "\n", encoding="utf-8")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path, help="默认包（Android 6.0+ 构建）")
    parser.add_argument("--variant-apk", action="append", default=[], type=Path,
                        help="同一版本的其它构建（例如 Android 4.4），可重复")
    parser.add_argument("--aapt", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tag")
    parser.add_argument("--notes-file", type=Path, help="完整更新日志，写入 release-notes.md 资产")
    parser.add_argument("--update-notes-file", type=Path, help="写入 update.json 的短摘要，最多 1500 字")
    args = parser.parse_args()
    notes = args.notes_file.read_text(encoding="utf-8").strip() if args.notes_file else ""
    manifest_notes = args.update_notes_file.read_text(encoding="utf-8").strip() if args.update_notes_file else notes
    print(prepare(args.apk, args.aapt, args.output, args.tag, manifest_notes, notes or None, args.variant_apk))


if __name__ == "__main__":
    main()
