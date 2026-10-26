#!/usr/bin/env python3
"""Derive the in-app update summary from a full changelog, within the manifest limit."""
import argparse
from pathlib import Path

# 与 AppUpdates 的 notes.take(1500) 和 prepare-kotlin-release.py 的清单上限保持一致。
LIMIT = 1500
TRUNCATED_TAIL = "\n\n完整更新日志见 GitHub Release 页面的版本说明。"


def shorten(text, limit=LIMIT):
    """按段落保留尽可能多的原文；放不下时追加指向完整说明的提示。"""
    text = text.strip()
    if len(text) <= limit:
        return text
    budget = limit - len(TRUNCATED_TAIL)
    kept = ""
    for part in text.split("\n\n"):
        candidate = f"{kept}\n\n{part}".strip() if kept else part.strip()
        if len(candidate) > budget:
            break
        kept = candidate
    if not kept:
        # 单个段落就超过上限时按行截断，避免把句子从中间劈开。
        lines = []
        for line in text.splitlines():
            candidate = "\n".join(lines + [line])
            if len(candidate) > budget:
                break
            lines.append(line)
        kept = "\n".join(lines).strip()
    if not kept:
        # 连一行都放不下时保留开头，仍然比整段丢掉更有用。
        kept = text[:budget].strip()
    # 去掉只剩下的标题行，避免摘要末尾出现没有内容的空章节。
    while kept.splitlines() and kept.splitlines()[-1].lstrip().startswith("#"):
        kept = kept.rsplit("\n", 1)[0].strip()
    return (kept + TRUNCATED_TAIL)[:limit]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notes-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--limit", type=int, default=LIMIT)
    args = parser.parse_args()
    summary = shorten(args.notes_file.read_text(encoding="utf-8"), args.limit)
    args.output.write_text(summary.strip() + "\n", encoding="utf-8")
    print(f"{args.output}: {len(summary)} 字符（上限 {args.limit}）")


if __name__ == "__main__":
    main()
