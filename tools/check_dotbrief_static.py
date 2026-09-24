#!/usr/bin/env python3
"""DotBrief 静态自检（不编译）：

1. 每个 .kt 的括号/花括号/方括号是否配平（跳过字符串、字符字面量、注释）；
2. Kotlin 里引用的 R.string / R.layout / R.id / R.drawable 是否真的存在；
3. 反向提示：新加的 string 有没有被任何代码引用（只报新加的，避免历史噪音）。

用法：python3 tools/check_dotbrief_static.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main"
RES = SRC / "res"

problems: list[str] = []


def strip_code(line: str, in_block: bool) -> tuple[str, bool]:
    """去掉注释与字符串/字符字面量，只留下参与括号匹配的骨架。"""
    out = []
    i = 0
    n = len(line)
    while i < n:
        if in_block:
            end = line.find("*/", i)
            if end == -1:
                return "".join(out), True
            i = end + 2
            in_block = False
            continue
        ch = line[i]
        nxt = line[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if ch == "/" and nxt == "/":
            break
        if line.startswith('"""', i):
            end = line.find('"""', i + 3)
            if end == -1:
                return "".join(out), in_block
            i = end + 3
            continue
        if ch == '"':
            i += 1
            while i < n:
                if line[i] == "\\":
                    i += 2
                    continue
                if line[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if ch == "'":
            i += 1
            while i < n:
                if line[i] == "\\":
                    i += 2
                    continue
                if line[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        if ch == "`":
            i += 1
            while i < n and line[i] != "`":
                i += 1
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out), in_block


def check_balance(path: Path) -> None:
    pairs = {")": "(", "]": "[", "}": "{"}
    stack: list[str] = []
    in_block = False
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        code, in_block = strip_code(raw, in_block)
        for ch in code:
            if ch in "([{":
                stack.append(ch)
            elif ch in pairs:
                if not stack or stack[-1] != pairs[ch]:
                    problems.append(f"{path.relative_to(ROOT)}:{lineno} 括号不匹配：多余的 {ch}")
                    return
                stack.pop()
    if stack:
        problems.append(f"{path.relative_to(ROOT)} 结尾有未闭合的：{''.join(stack)}")


def load_strings() -> set[str]:
    names: set[str] = set()
    for xml in RES.glob("values*/strings.xml"):
        names |= set(re.findall(r'<string name="([^"]+)"', xml.read_text(encoding="utf-8")))
    return names


def load_layout_ids() -> dict[str, set[str]]:
    ids: dict[str, set[str]] = {}
    for xml in RES.glob("layout/*.xml"):
        ids[xml.stem] = set(re.findall(r'android:id="@\+?id/([A-Za-z0-9_]+)"', xml.read_text(encoding="utf-8")))
    return ids


def main() -> int:
    kt_files = sorted(SRC.rglob("*.kt")) + sorted((ROOT / "app/src/test").rglob("*.kt"))
    for path in kt_files:
        check_balance(path)

    strings = load_strings()
    layouts = load_layout_ids()
    layout_names = {p.stem for p in RES.glob("layout/*.xml")}
    drawables = {
        p.stem
        for folder in ("drawable", "mipmap-anydpi-v26", "drawable-anydpi-v26")
        for p in (RES / folder).glob("*")
        if p.is_file()
    }
    drawables |= {re.sub(r"\.(xml|png|webp)$", "", p.name) for p in (RES / "drawable").glob("*")}

    referenced: set[str] = set()
    for path in kt_files:
        text = path.read_text(encoding="utf-8")
        referenced |= set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", text))
        for layout in re.findall(r"R\.layout\.([A-Za-z0-9_]+)", text):
            if layout not in layout_names:
                problems.append(f"{path.relative_to(ROOT)} 引用了不存在的布局 R.layout.{layout}")
        for drawable in re.findall(r"R\.drawable\.([A-Za-z0-9_]+)", text):
            if drawable not in drawables:
                problems.append(f"{path.relative_to(ROOT)} 引用了不存在的图 R.drawable.{drawable}")
        for widget_id in re.findall(r"R\.id\.([A-Za-z0-9_]+)", text):
            if not any(widget_id in layouts.get(name, ()) for name in layouts):
                problems.append(f"{path.relative_to(ROOT)} 引用了任何布局里都没有的 R.id.{widget_id}")

    for name in sorted(referenced):
        if name not in strings:
            problems.append(f"代码引用了不存在的字符串 R.string.{name}")

    # 新增的自动更新相关文案有没有真的被用上
    for name in sorted(strings):
        if name.startswith(("update_", "action_refresh_brief", "msg_time_", "block_last_issue", "label_update")):
            if name not in referenced:
                problems.append(f"字符串 {name} 定义了但没有任何代码引用")

    if problems:
        print("发现问题：")
        for problem in problems:
            print("  -", problem)
        return 1

    print(f"自检通过：{len(kt_files)} 个 Kotlin 文件括号配平，资源引用与字符串引用全部对得上。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
