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

def report_build_failure() -> None:
    """没有测试报告时，失败在编译阶段。重跑一次编译，把真正的报错行报出来。"""
    import subprocess

    for task in (":core:compileTestKotlin", ":app:compileDebugUnitTestKotlin"):
        command = ["./kotlin-tv/gradlew", "-p", "kotlin-tv", task, "--console=plain"]
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=1200)
        except Exception as error:
            print("::error::重跑 %s 失败：%s" % (task, error))
            continue
        output = (result.stdout or "") + (result.stderr or "")
        lines = [line.strip() for line in output.splitlines() if line.startswith("e: ") or "error:" in line or "FAILURE:" in line]
        if not lines:
            print("::error::%s 重跑没有报错，失败可能在 lint 或更早的步骤" % task)
        for line in lines[:12]:
            print("::error::%s" % line[:400])


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
                    # 只有 message 时常常只是一个异常类名，真正的线索在堆栈里，两段都带上。
                    message = (bad.get("message") or "").strip().replace("\n", " ")
                    trace = " | ".join((bad.text or "").strip().splitlines()[:6]).replace("\n", " ")
                    detail = (message + " || " + trace)[:900]
                    print("::error::%s.%s: %s" % (case.get("classname"), case.get("name"), detail))
    if not found_failure:
        stage = "测试都通过了" if saw_report else "没有找到测试报告"
        print("::error::%s：失败发生在编译或 lint 阶段" % stage)
        if not saw_report:
            report_build_failure()
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
