#!/usr/bin/env python3
"""把本次构建**实际跑过**的测试数打到标准输出，供流水线断言「这一 gate 不是空跑」。

只做这一件事，且刻意不读入任何参数：判定的对象就是当前工作目录下
`*/target/surefire-reports/TEST-*.xml` 里记录的 `tests` 属性之和。

为什么需要它：一道 gate 最危险的失败形态不是红，而是绿 —— 一个测试都没跑，
报告里照样写着「通过」。来源项目里那 6 个被 `@EnabledIfEnvironmentVariable`
关掉的测试正是这种形态（ADR-0008）。数字为 0 时由调用方判红，这里只负责把数字说出来。
"""

import glob
import sys
import xml.etree.ElementTree as ElementTree

total = 0
for report in sorted(glob.glob("*/target/surefire-reports/TEST-*.xml")):
    try:
        root = ElementTree.parse(report).getroot()
    except ElementTree.ParseError as failure:
        print("读不懂测试报告 %s：%s" % (report, failure), file=sys.stderr)
        sys.exit(2)
    total += int(root.get("tests", "0"))

print(total)
