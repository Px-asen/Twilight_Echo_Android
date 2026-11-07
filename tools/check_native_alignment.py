"""Check every APK native library's ELF and uncompressed ZIP page alignment."""

import argparse
import json
import struct
import zipfile
from pathlib import Path

PAGE_SIZE = 16384


def load_segments(data):
    if data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("not an ELF32/ELF64 library")
    order = "<" if data[5] == 1 else ">"
    is_64 = data[4] == 2
    phoff = struct.unpack_from(order + ("Q" if is_64 else "I"), data, 32 if is_64 else 28)[0]
    entry_size, count = struct.unpack_from(order + "HH", data, 54 if is_64 else 42)
    segments = []
    for index in range(count):
        header = struct.unpack_from(order + ("IIQQQQQQ" if is_64 else "IIIIIIII"),
                                    data, phoff + index * entry_size)
        if header[0] != 1:  # PT_LOAD
            continue
        offset, address = (header[2], header[3]) if is_64 else (header[1], header[2])
        alignment = header[7]
        segments.append({
            "offset": offset,
            "virtualAddress": address,
            "alignment": alignment,
            "aligned16KiB": alignment >= PAGE_SIZE and offset % PAGE_SIZE == address % PAGE_SIZE,
        })
    if not segments:
        raise ValueError("ELF has no PT_LOAD segments")
    return segments


def inspect_apk(path):
    libraries = []
    with zipfile.ZipFile(path) as apk, open(path, "rb") as raw:
        for entry in apk.infolist():
            if not (entry.filename.startswith("lib/") and entry.filename.endswith(".so")):
                continue
            raw.seek(entry.header_offset + 26)
            name_size, extra_size = struct.unpack("<HH", raw.read(4))
            data_offset = entry.header_offset + 30 + name_size + extra_size
            stored = entry.compress_type == zipfile.ZIP_STORED
            result = {
                "entry": entry.filename,
                "stored": stored,
                "zipDataOffset": data_offset,
                "zipAligned16KiB": data_offset % PAGE_SIZE == 0 if stored else None,
                "errors": [],
            }
            try:
                result["loadSegments"] = load_segments(apk.read(entry))
                if not all(segment["aligned16KiB"] for segment in result["loadSegments"]):
                    result["errors"].append("PT_LOAD alignment or offset/address congruence is below 16 KiB")
            except (ValueError, struct.error) as error:
                result["errors"].append(str(error))
            if stored and not result["zipAligned16KiB"]:
                result["errors"].append("uncompressed ZIP data is not aligned to 16 KiB")
            libraries.append(result)
    return {
        "apk": str(Path(path).resolve()),
        "pageSize": PAGE_SIZE,
        "nativeLibraries": len(libraries),
        "abis": sorted({library["entry"].split("/")[1] for library in libraries}),
        "passed": bool(libraries) and all(not library["errors"] for library in libraries),
        "libraries": libraries,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--json", type=Path, help="save the complete per-library report")
    args = parser.parse_args()
    report = inspect_apk(args.apk)
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{'PASS' if report['passed'] else 'FAIL'}: {report['nativeLibraries']} native libraries; "
          "16 KiB ELF / uncompressed ZIP alignment")
    if not report["nativeLibraries"]:
        print("No native libraries found in the APK")
    for library in report["libraries"]:
        for error in library["errors"]:
            print(f"  {library['entry']}: {error}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
