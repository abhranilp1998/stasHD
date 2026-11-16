"""Check 64-bit native ELF LOAD alignment using only Python's standard library."""
import argparse
import struct
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk")
args = parser.parse_args()
failures = []
checked = 0
with zipfile.ZipFile(args.apk) as apk:
    for name in apk.namelist():
        if not name.endswith(".so") or not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
            continue
        data = apk.read(name)
        if data[:6] != b"\x7fELF\x02\x01":
            failures.append(f"{name}: expected a 64-bit little-endian ELF")
            continue
        phoff = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        loads = []
        for index in range(count):
            offset = phoff + index * entry_size
            if struct.unpack_from("<I", data, offset)[0] == 1:
                file_offset, address = struct.unpack_from("<QQ", data, offset + 8)
                alignment = struct.unpack_from("<Q", data, offset + 48)[0]
                loads.append(alignment)
                if alignment < 16384 or file_offset % 16384 != address % 16384:
                    failures.append(f"{name}: LOAD segment does not support 16 KB pages")
        if not loads:
            failures.append(f"{name}: no LOAD segments")
        checked += 1
        print(f"{name}: LOAD alignments {loads}")
if failures:
    raise SystemExit("\n".join(failures))
print(f"Verified 16 KB ELF alignment for {checked} native libraries.")
