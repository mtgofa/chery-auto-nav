#!/usr/bin/env python3
"""Refuse to ship a Windows CE executable the head unit cannot load.

The first build of CheryDiag.exe did not start on the car, and the reason was
visible in the import table the whole time: gcc had linked the shared libgcc,
so the image depended on libgcc_s_sjlj-1.dll, which does not exist on the
device.  The CE loader resolves every import before the first instruction runs,
so one missing dependency means nothing happens at all - no window, no error.

This script turns that into a build failure instead of a trip to the car.

Usage: pe_check.py <file.exe>
"""
from __future__ import annotations

import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pe_tool import PEImage  # noqa: E402

MACHINE_THUMB = 0x01C2
SUBSYS_CE_GUI = 9
CE_CHARACTERISTICS = 0x0103
MAX_STACK_RESERVE = 0x80000  # 512 KB; the factory binaries reserve 64 KB

# The only module a standalone CE program may depend on.  Anything else has to
# be brought in with LoadLibrary at run time, where a failure is recoverable.
ALLOWED_MODULES = {"coredll", "coredll.dll"}


def check(path: str) -> int:
    pe = PEImage(path)
    data = pe.data
    coff = pe.pe_off + 4
    opt = coff + 20

    problems: list[str] = []
    notes: list[str] = []

    if pe.machine != MACHINE_THUMB:
        problems.append(f"machine is 0x{pe.machine:04X}, expected 0x{MACHINE_THUMB:04X} "
                        f"(run tools/pe_fixup.py)")
    if pe.subsystem != SUBSYS_CE_GUI:
        problems.append(f"subsystem is {pe.subsystem}, expected {SUBSYS_CE_GUI} (CE GUI)")
    if pe.characteristics != CE_CHARACTERISTICS:
        problems.append(f"characteristics 0x{pe.characteristics:04X}, "
                        f"expected 0x{CE_CHARACTERISTICS:04X}")

    checksum = struct.unpack_from("<I", data, opt + 64)[0]
    if checksum:
        problems.append(f"checksum is 0x{checksum:X}; CE images carry 0")

    stack_reserve = struct.unpack_from("<I", data, opt + 72)[0]
    if stack_reserve > MAX_STACK_RESERVE:
        problems.append(f"stack reserve 0x{stack_reserve:X} is above "
                        f"0x{MAX_STACK_RESERVE:X}")

    imports = pe.imports()
    if not imports:
        problems.append("no import table at all")
    for dll, names in imports:
        if dll.lower() not in ALLOWED_MODULES:
            problems.append(f"links against {dll} ({len(names)} symbols) - "
                            f"the device only has coredll")
        else:
            notes.append(f"{dll}: {len(names)} imports")


    print(f"{path}")
    print(f"  machine 0x{pe.machine:04X}  subsystem {pe.subsystem}  "
          f"characteristics 0x{pe.characteristics:04X}  "
          f"stack 0x{stack_reserve:X}  checksum 0x{checksum:X}")
    for note in notes:
        print(f"  {note}")

    if problems:
        print()
        for problem in problems:
            print(f"  FAIL: {problem}", file=sys.stderr)
        return 1

    print("  OK: loadable shape for Windows CE ARM")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(check(sys.argv[1]))
