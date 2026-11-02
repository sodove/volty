"""Verify 16 KiB ELF LOAD alignment in the 64-bit libraries of an APK or AAB.

Run with Python 3 and the archive path. APK ZIP alignment is a separate check:
Android SDK build-tools/35.0.0/zipalign -c -P 16 4 app.apk
"""

import argparse
import struct
from pathlib import Path
from zipfile import ZipFile


PAGE_SIZE = 16 * 1024
SUPPORTED_ABIS = {"arm64-v8a", "x86_64"}


def check_elf(data: bytes) -> list[str]:
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("expected a little-endian ELF64 library")
    program_offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or program_offset + entry_size * count > len(data):
        raise ValueError("invalid ELF program header table")
    problems = []
    load_count = 0
    for index in range(count):
        kind, _, offset, address, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, program_offset + index * entry_size
        )
        if kind != 1:  # PT_LOAD
            continue
        load_count += 1
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            problems.append(f"LOAD[{index}] alignment={alignment:#x}, requires >=0x4000")
        if offset % PAGE_SIZE != address % PAGE_SIZE:
            problems.append(f"LOAD[{index}] file/virtual offsets disagree modulo 16 KiB")
    if not load_count:
        problems.append("no LOAD segments")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    checked = failures = 0
    with ZipFile(args.archive) as archive:
        for name in sorted(archive.namelist()):
            parts = name.split("/")
            if len(parts) < 3 or parts[-3] != "lib" or parts[-2] not in SUPPORTED_ABIS or not name.endswith(".so"):
                continue
            checked += 1
            try:
                problems = check_elf(archive.read(name))
            except (ValueError, struct.error) as error:
                problems = [str(error)]
            if problems:
                failures += 1
                print(f"FAIL {name}: {'; '.join(problems)}")
            else:
                print(f"PASS {name}")
    if not checked:
        parser.error("archive contains no supported 64-bit native libraries")
    print(f"Checked {checked} libraries; incompatible: {failures}")
    return int(failures != 0)


if __name__ == "__main__":
    raise SystemExit(main())
