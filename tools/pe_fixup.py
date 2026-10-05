#!/usr/bin/env python3
"""Make an arm-mingw32ce executable look like the head unit's own binaries.

The Windows CE loader is stricter than a desktop one, and binutils leaves
several fields that no Microsoft-built CE image carries.  Each fixup below was
chosen by diffing against Car/ResidentFlash/navigation/app/CMMBService.exe,
which is known to run on this device.

  1. COFF machine        0x01C0 -> 0x01C2.  Every factory binary is stamped
                         IMAGE_FILE_MACHINE_THUMB even though the code is ARM;
                         that is what the ARMV4I toolchain emits.
  2. Characteristics     -> RELOCS_STRIPPED | EXECUTABLE_IMAGE | 32BIT_MACHINE.
                         binutils also sets NET_RUN_FROM_SWAP and UP_SYSTEM_ONLY,
                         which no CE image sets.
  3. Checksum            -> 0.  Factory images carry 0, and any value binutils
                         computed is stale the moment fixup 1 rewrites a byte.
  4. Section flags       drop the IMAGE_SCN_ALIGN_* bits (0x00F00000).  Those
                         belong in object files, not in a linked image.
  5. Subsystem version   -> 4.0, matching G3NavHMI.exe and SPIPowerMgr.dll.
  6. Timestamp           -> 0.  The loader ignores it, and without it two builds
                         of the same source are byte-identical, which is what
                         lets CI compare the committed release/ copies.

Usage: pe_fixup.py <file.exe>
"""
import struct
import sys

MACHINE_ARM = 0x01C0
MACHINE_THUMB = 0x01C2
CE_CHARACTERISTICS = 0x0103
SCN_ALIGN_MASK = 0x00F00000
SUBSYS_VER = (4, 0)


def fixup(path: str) -> int:
    with open(path, "rb") as fh:
        data = bytearray(fh.read())

    if data[:2] != b"MZ":
        print(f"{path}: not a PE image", file=sys.stderr)
        return 1
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        print(f"{path}: no PE signature", file=sys.stderr)
        return 1

    coff = pe + 4
    machine, n_sections = struct.unpack_from("<HH", data, coff)
    opt_size = struct.unpack_from("<H", data, coff + 16)[0]
    opt = coff + 20
    changes = []

    if machine == MACHINE_ARM:
        struct.pack_into("<H", data, coff, MACHINE_THUMB)
        changes.append(f"machine 0x{MACHINE_ARM:04X} -> 0x{MACHINE_THUMB:04X}")
    elif machine != MACHINE_THUMB:
        print(f"{path}: unexpected machine 0x{machine:04X}, refusing to touch it",
              file=sys.stderr)
        return 1

    stamp = struct.unpack_from("<I", data, coff + 4)[0]
    if stamp:
        struct.pack_into("<I", data, coff + 4, 0)
        changes.append(f"timestamp 0x{stamp:08X} -> 0")

    chars = struct.unpack_from("<H", data, coff + 18)[0]
    if chars != CE_CHARACTERISTICS:
        struct.pack_into("<H", data, coff + 18, CE_CHARACTERISTICS)
        changes.append(f"characteristics 0x{chars:04X} -> 0x{CE_CHARACTERISTICS:04X}")

    checksum = struct.unpack_from("<I", data, opt + 64)[0]
    if checksum:
        struct.pack_into("<I", data, opt + 64, 0)
        changes.append(f"checksum 0x{checksum:X} -> 0")

    subsys_ver = struct.unpack_from("<HH", data, opt + 48)
    if subsys_ver != SUBSYS_VER:
        struct.pack_into("<HH", data, opt + 48, *SUBSYS_VER)
        changes.append(f"subsystem version {subsys_ver[0]}.{subsys_ver[1]} -> "
                       f"{SUBSYS_VER[0]}.{SUBSYS_VER[1]}")

    sec = opt + opt_size
    cleaned = 0
    for _ in range(n_sections):
        flags_off = sec + 36
        flags = struct.unpack_from("<I", data, flags_off)[0]
        if flags & SCN_ALIGN_MASK:
            struct.pack_into("<I", data, flags_off, flags & ~SCN_ALIGN_MASK)
            cleaned += 1
        sec += 40
    if cleaned:
        changes.append(f"cleared alignment bits on {cleaned} section(s)")

    if not changes:
        print(f"{path}: already in CE form")
        return 0

    with open(path, "wb") as fh:
        fh.write(data)
    for change in changes:
        print(f"{path}: {change}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(fixup(sys.argv[1]))
