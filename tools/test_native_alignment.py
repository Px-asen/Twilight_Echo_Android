import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from check_native_alignment import PAGE_SIZE, inspect_apk


def elf(bits=64, alignment=PAGE_SIZE, address=0, segment_type=1):
    data = bytearray(256)
    data[:16] = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1, 1]) + bytes(9)
    if bits == 64:
        struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, 183, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
        struct.pack_into("<IIQQQQQQ", data, 64, segment_type, 5, 0, address, 0, 256, 256, alignment)
    else:
        struct.pack_into("<HHIIIIIHHHHHH", data, 16, 3, 40, 1, 0, 52, 0, 0, 52, 32, 1, 0, 0, 0)
        struct.pack_into("<IIIIIIII", data, 52, segment_type, 0, address, 0, 256, 256, 5, alignment)
    return data


class NativeAlignmentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "test.apk"

    def package(self, entries, zip_alignment=PAGE_SIZE, compressed=False):
        with zipfile.ZipFile(self.apk, "w") as apk:
            for name, data in entries:
                entry = zipfile.ZipInfo(name)
                entry.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
                padding = -(apk.fp.tell() + 30 + len(name.encode())) % zip_alignment
                if padding:
                    if padding < 4:
                        padding += zip_alignment
                    entry.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
                apk.writestr(entry, data)
        return inspect_apk(self.apk)

    def test_all_libraries_are_checked_including_elf32_and_elf64(self):
        report = self.package([("lib/arm64-v8a/liba.so", elf()), ("lib/x86/libb.so", elf(32))])
        self.assertTrue(report["passed"])
        self.assertEqual(2, report["nativeLibraries"])
        self.assertEqual(["arm64-v8a", "x86"], report["abis"])

    def test_one_4k_elf_rejects_the_whole_apk(self):
        report = self.package([("lib/arm64-v8a/liba.so", elf()),
                               ("lib/arm64-v8a/libquickjs.so", elf(alignment=4096))])
        self.assertFalse(report["passed"])
        self.assertEqual(1, len(report["libraries"][1]["errors"]))

    def test_offset_and_address_must_agree_at_16k(self):
        report = self.package([("lib/arm64-v8a/liba.so", elf(address=4096))])
        self.assertFalse(report["passed"])

    def test_4k_zip_alignment_is_insufficient_for_stored_library(self):
        report = self.package([("lib/arm64-v8a/liba.so", elf())], zip_alignment=4096)
        self.assertFalse(report["passed"])
        self.assertEqual(4096, report["libraries"][0]["zipDataOffset"])
        self.assertIn("uncompressed ZIP", report["libraries"][0]["errors"][0])

    def test_compressed_library_needs_elf_but_not_zip_page_alignment(self):
        report = self.package([("lib/arm64-v8a/liba.so", elf())], zip_alignment=1, compressed=True)
        self.assertTrue(report["passed"])
        self.assertIsNone(report["libraries"][0]["zipAligned16KiB"])

    def test_no_libraries_does_not_report_success(self):
        self.assertFalse(self.package([("assets/readme.txt", b"hello")])["passed"])

    def test_missing_load_segments_does_not_report_success(self):
        self.assertFalse(self.package([("lib/x86/liba.so", elf(32, segment_type=0))])["passed"])

    def test_cli_exits_nonzero_and_records_failing_entry(self):
        self.package([("lib/x86/libquickjs.so", elf(32, alignment=4096))])
        output = Path(self.directory.name) / "report.json"
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("check_native_alignment.py")),
                                 str(self.apk), "--json", str(output)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("lib/x86/libquickjs.so", result.stdout)
        self.assertIn('"passed": false', output.read_text())


if __name__ == "__main__":
    unittest.main()
