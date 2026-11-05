"""Check release DEX fields for the Pixel 7 startup regression.

Run after assembleRelease; debug unit tests do not exercise R8 or these
Firebase Performance reflection paths.

Windows example (Python 3):
    py -3 tools/verify-firebase-protobuf-release.py <mapping.txt> <release.apk> <apkanalyzer.bat>
"""

import argparse
from pathlib import Path
import re
import subprocess


def verify(mapping: str, apk: Path, apkanalyzer: Path) -> None:
    required = {
        "com.google.firebase.perf.v1.TraceMetric": "name_",
        "com.google.firebase.perf.v1.AndroidApplicationInfo": "packageName_",
    }
    found = set()
    for line in mapping.splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if match and match[1] in required:
            current, renamed = match.groups()
            field = required[current]
            # R8 omits unchanged primitive/string fields from the mapping.
            # Inspect the actual APK, rather than treating omission as success.
            code = subprocess.check_output(
                [str(apkanalyzer), "dex", "code", "--class", renamed, str(apk)],
                encoding="utf-8",
            )
            if not re.search(r"^\.field .*\b" + re.escape(field) + r":", code, re.MULTILINE):
                raise ValueError(f"{current}.{field} absent from release DEX ({renamed})")
            found.add(current)
    missing = required.keys() - found
    if missing:
        raise ValueError(f"Required protobuf classes missing from mapping: {sorted(missing)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mapping", type=Path)
    parser.add_argument("apk", type=Path)
    parser.add_argument("apkanalyzer", type=Path)
    args = parser.parse_args()
    verify(args.mapping.read_text(encoding="utf-8"), args.apk, args.apkanalyzer)
    print("PASS: Firebase Performance protobuf field names survive R8")
