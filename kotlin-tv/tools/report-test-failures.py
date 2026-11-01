#!/usr/bin/env python3
"""把 Gradle 测试报告里的失败用例打印成 GitHub Actions 注解。

CI 的失败注解原本只有一行「Process completed with exit code 1」，看不出是哪一个测试。
这个脚本在测试步骤失败后运行，读 Gradle 写下的 JUnit XML，把失败用例与原因逐条输出成
::error::。找不到任何失败用例时说明失败发生在编译或 lint 阶段，并以非零退出结束。
"""
import glob
import sys
import xml.etree.ElementTree as ET

PATTERNS = ["kotlin-tv/**/build/test-results/**/*.xml"]

def main() -> int:
    found_failure = False
    saw_report = False
    for pattern in PATTERNS:
        for path in sorted(glob.glob(pattern, recursive=True)):
            try:
                root = ET.parse(path).getroot()
            except Exception:
                continue
            saw_report = True
            for case in root.iter("testcase"):
                for bad in list(case.iter("failure")) + list(case.iter("error")):
                    found_failure = True
                    detail = (bad.get("message") or "").strip().replace("\n", " ")[:400]
                    print("::error::%s.%s: %s" % (case.get("classname"), case.get("name"), detail))
    if not found_failure:
        stage = "测试都通过了" if saw_report else "没有找到测试报告"
        print("::error::%s：失败发生在编译或 lint 阶段" % stage)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
