#!/usr/bin/env python3
"""将旧 store-probe.xlsx 转成 store_probe 的一次性导入 SQL。"""

from __future__ import annotations

import re
import sys
import zipfile
from xml.etree import ElementTree

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def text(node: ElementTree.Element | None) -> str:
    return "" if node is None else "".join(node.itertext()).strip()


def cell_value(cell: ElementTree.Element, shared: list[str]) -> str:
    value = cell.find(f"{NS}v")
    if cell.get("t") == "s" and value is not None:
        return shared[int(value.text or 0)]
    inline = cell.find(f"{NS}is")
    return text(inline if inline is not None else value)


def read_rows(path: str) -> list[dict[str, str]]:
    with zipfile.ZipFile(path) as book:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in book.namelist():
            root = ElementTree.fromstring(book.read("xl/sharedStrings.xml"))
            shared = [text(item) for item in root.findall(f"{NS}si")]
        sheet = ElementTree.fromstring(book.read("xl/worksheets/sheet1.xml"))
    rows: list[list[str]] = []
    for row in sheet.findall(f".//{NS}row"):
        values: dict[int, str] = {}
        for cell in row.findall(f"{NS}c"):
            match = re.match(r"([A-Z]+)", cell.get("r", ""))
            if not match:
                continue
            column = 0
            for letter in match.group(1):
                column = column * 26 + ord(letter) - ord("A") + 1
            values[column - 1] = cell_value(cell, shared)
        rows.append([values.get(i, "") for i in range(max(values, default=-1) + 1)])
    if not rows:
        return []
    headers = {name: index for index, name in enumerate(rows[0]) if name}
    required = {"store_id", "store_name", "remarks"}
    missing = required - headers.keys()
    if missing:
        raise ValueError(f"缺少列: {', '.join(sorted(missing))}")
    return [
        {name: row[index].strip() if index < len(row) else "" for name, index in headers.items()}
        for row in rows[1:]
        if row and headers["store_id"] < len(row) and row[headers["store_id"]].strip()
    ]


def sql(value: str) -> str:
    return "NULL" if not value else "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def main(path: str) -> None:
    stores = {row["store_id"]: row for row in read_rows(path)}
    print("START TRANSACTION;")
    for index, row in enumerate(stores.values(), 1):
        print(
            "INSERT INTO store_probe(id,store_id,store_name,remarks) VALUES "
            f"({1991000000000000000 + index},{sql(row.get('store_id', ''))},"
            f"{sql(row.get('store_name', ''))},{sql(row.get('remarks', ''))});"
        )
    print("COMMIT;")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(f"用法: {sys.argv[0]} /data/app/image-admin/data/store-probe.xlsx")
    main(sys.argv[1])
