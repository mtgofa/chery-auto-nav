#!/usr/bin/env python3
"""Inspect the Windows CE ARM binaries in the head-unit firmware dump.

This replaces the ~70 one-shot PowerShell scripts that used to live in the
repository root.  Everything they did - list exports, list imports, grep for
strings, find which module references a symbol - is a subcommand here, and it
runs on any machine with Python, not just Windows.

    tools/pe_tool.py info      Car/ResidentFlash/Main.exe
    tools/pe_tool.py exports   Car/ResidentFlash/navigation/app/SPIPowerMgr.dll
    tools/pe_tool.py imports   Car/ResidentFlash/Main.exe
    tools/pe_tool.py strings   Car/ResidentFlash/Main.exe --grep RVS
    tools/pe_tool.py refs      Car --symbol RVS_setSteerAngle
    tools/pe_tool.py inventory Car
"""
from __future__ import annotations

import argparse
import os
import struct
import sys
from typing import Iterable, Iterator, NamedTuple

MACHINES = {0x01C0: "ARM", 0x01C2: "ARM/Thumb (ARMV4I)", 0x014C: "x86", 0x0166: "MIPS"}
SUBSYSTEMS = {9: "Windows CE GUI", 2: "Windows GUI", 3: "Windows console"}


class Section(NamedTuple):
    name: str
    vaddr: int
    vsize: int
    raw_off: int
    raw_size: int


class PEImage:
    """Just enough PE parsing for these firmware images."""

    def __init__(self, path: str) -> None:
        self.path = path
        with open(path, "rb") as fh:
            self.data = fh.read()
        if self.data[:2] != b"MZ":
            raise ValueError("not a DOS/PE image")
        self.pe_off = struct.unpack_from("<I", self.data, 0x3C)[0]
        if self.data[self.pe_off:self.pe_off + 4] != b"PE\0\0":
            raise ValueError("no PE signature")

        coff = self.pe_off + 4
        (self.machine, n_sections, self.timestamp, _sym, _nsym,
         opt_size, self.characteristics) = struct.unpack_from("<HHIIIHH", self.data, coff)

        opt = coff + 20
        self.image_base = struct.unpack_from("<I", self.data, opt + 28)[0]
        self.subsystem = struct.unpack_from("<H", self.data, opt + 68)[0]
        self.subsys_ver = struct.unpack_from("<HH", self.data, opt + 48)
        n_dirs = struct.unpack_from("<I", self.data, opt + 92)[0]
        self.dirs = [struct.unpack_from("<II", self.data, opt + 96 + i * 8)
                     for i in range(n_dirs)]

        self.sections: list[Section] = []
        off = opt + opt_size
        for _ in range(n_sections):
            name = self.data[off:off + 8].rstrip(b"\0").decode("latin1")
            vsize, vaddr, raw_size, raw_off = struct.unpack_from("<IIII", self.data, off + 8)
            self.sections.append(Section(name, vaddr, vsize, raw_off, raw_size))
            off += 40

    # -- address helpers ---------------------------------------------------
    def rva_to_offset(self, rva: int) -> int | None:
        for s in self.sections:
            if s.vaddr <= rva < s.vaddr + max(s.vsize, s.raw_size):
                off = rva - s.vaddr + s.raw_off
                return off if off < len(self.data) else None
        return None

    def cstring(self, rva: int) -> str:
        off = self.rva_to_offset(rva)
        if off is None:
            return ""
        end = self.data.find(b"\0", off)
        return self.data[off:end].decode("latin1")

    # -- tables ------------------------------------------------------------
    def exports(self) -> tuple[str, list[tuple[int, str, int]]]:
        rva, _size = self.dirs[0] if self.dirs else (0, 0)
        if not rva:
            return "", []
        off = self.rva_to_offset(rva)
        if off is None:
            return "", []
        name_rva, ord_base, n_funcs, n_names, func_rva, name_tbl, ord_tbl = \
            struct.unpack_from("<IIIIIII", self.data, off + 12)
        dll = self.cstring(name_rva) if name_rva else ""

        addrs_off = self.rva_to_offset(func_rva)
        addrs = ([struct.unpack_from("<I", self.data, addrs_off + 4 * i)[0]
                  for i in range(n_funcs)] if addrs_off is not None else [])

        names: dict[int, str] = {}
        if n_names and name_tbl and ord_tbl:
            no = self.rva_to_offset(name_tbl)
            oo = self.rva_to_offset(ord_tbl)
            if no is not None and oo is not None:
                for i in range(n_names):
                    nr = struct.unpack_from("<I", self.data, no + 4 * i)[0]
                    ordinal = struct.unpack_from("<H", self.data, oo + 2 * i)[0]
                    names[ordinal] = self.cstring(nr)

        out = [(ord_base + i, names.get(i, ""), addr)
               for i, addr in enumerate(addrs) if addr]
        return dll, out

    def imports(self) -> list[tuple[str, list[str]]]:
        rva, _size = self.dirs[1] if len(self.dirs) > 1 else (0, 0)
        if not rva:
            return []
        off = self.rva_to_offset(rva)
        if off is None:
            return []
        result: list[tuple[str, list[str]]] = []
        while True:
            oft, _ts, _fc, name_rva, ft = struct.unpack_from("<IIIII", self.data, off)
            if not (oft or name_rva or ft):
                break
            dll = self.cstring(name_rva)
            thunk = self.rva_to_offset(oft or ft)
            names: list[str] = []
            if thunk is not None:
                i = 0
                while True:
                    value = struct.unpack_from("<I", self.data, thunk + 4 * i)[0]
                    if not value:
                        break
                    names.append(f"#{value & 0xFFFF}" if value & 0x80000000
                                 else self.cstring(value + 2))
                    i += 1
            result.append((dll, names))
            off += 20
        return result


def iter_strings(blob: bytes, minimum: int = 4) -> Iterator[str]:
    """ASCII and UTF-16LE strings, the way `strings -a` and `strings -el` do."""
    run = bytearray()
    for byte in blob:
        if 32 <= byte < 127:
            run.append(byte)
        else:
            if len(run) >= minimum:
                yield run.decode("latin1")
            run.clear()
    if len(run) >= minimum:
        yield run.decode("latin1")

    run.clear()
    for i in range(0, len(blob) - 1, 2):
        lo, hi = blob[i], blob[i + 1]
        if hi == 0 and 32 <= lo < 127:
            run.append(lo)
        else:
            if len(run) >= minimum:
                yield run.decode("latin1")
            run.clear()
    if len(run) >= minimum:
        yield run.decode("latin1")


def walk_binaries(root: str) -> Iterator[str]:
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if name.lower().endswith((".exe", ".dll")):
                yield os.path.join(dirpath, name)


# -- subcommands -----------------------------------------------------------
def cmd_info(args: argparse.Namespace) -> int:
    pe = PEImage(args.path)
    print(f"{pe.path}")
    print(f"  machine     0x{pe.machine:04X}  {MACHINES.get(pe.machine, '?')}")
    print(f"  subsystem   {pe.subsystem} {SUBSYSTEMS.get(pe.subsystem, '?')} "
          f"v{pe.subsys_ver[0]}.{pe.subsys_ver[1]}")
    print(f"  image base  0x{pe.image_base:08X}")
    for s in pe.sections:
        print(f"  section {s.name:<8} va=0x{s.vaddr:06X} vsize=0x{s.vsize:06X} "
              f"raw=0x{s.raw_off:06X}")
    return 0


def cmd_exports(args: argparse.Namespace) -> int:
    pe = PEImage(args.path)
    dll, funcs = pe.exports()
    if not funcs:
        print(f"{pe.path}: no export table")
        return 0
    print(f"{pe.path}  ({dll})  {len(funcs)} exports")
    for ordinal, name, addr in funcs:
        print(f"  #{ordinal:<5} {name or '<unnamed>':<52} rva=0x{addr:06X}")
    return 0


def cmd_imports(args: argparse.Namespace) -> int:
    pe = PEImage(args.path)
    for dll, names in pe.imports():
        print(f"{dll}  ({len(names)})")
        for name in names:
            print(f"    {name}")
    return 0


def cmd_strings(args: argparse.Namespace) -> int:
    with open(args.path, "rb") as fh:
        blob = fh.read()
    needle = args.grep.lower() if args.grep else None
    for text in iter_strings(blob, args.min):
        if needle is None or needle in text.lower():
            print(text)
    return 0


def cmd_refs(args: argparse.Namespace) -> int:
    """Which binaries under <root> mention this symbol or literal?"""
    needle = args.symbol.encode("latin1")
    wide = args.symbol.encode("utf-16-le")
    hits = 0
    for path in walk_binaries(args.root):
        with open(path, "rb") as fh:
            blob = fh.read()
        if needle in blob or wide in blob:
            print(path)
            hits += 1
    if not hits:
        print(f"(no binary under {args.root} mentions {args.symbol})", file=sys.stderr)
    return 0


def cmd_inventory(args: argparse.Namespace) -> int:
    lines: list[str] = ["=== firmware binary inventory ===", ""]
    for path in walk_binaries(args.root):
        rel = os.path.relpath(path, args.root)
        try:
            pe = PEImage(path)
        except ValueError as exc:
            lines.append(f"{rel}: {exc}")
            continue
        dll, funcs = pe.exports()
        imports = pe.imports()
        lines.append(f"{rel}")
        lines.append(f"    size={os.path.getsize(path)} machine=0x{pe.machine:04X} "
                     f"base=0x{pe.image_base:08X} exports={len(funcs)} "
                     f"imports={sum(len(n) for _d, n in imports)}")
        if imports:
            lines.append("    needs: " + ", ".join(d for d, _n in imports))
    text = "\n".join(lines) + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"wrote {args.out} ({len(lines)} lines)")
    else:
        print(text, end="")
    return 0


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("info", help="headers and sections")
    p.add_argument("path")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("exports", help="export table")
    p.add_argument("path")
    p.set_defaults(func=cmd_exports)

    p = sub.add_parser("imports", help="import table")
    p.add_argument("path")
    p.set_defaults(func=cmd_imports)

    p = sub.add_parser("strings", help="ASCII and UTF-16 strings")
    p.add_argument("path")
    p.add_argument("--grep", help="case-insensitive substring filter")
    p.add_argument("--min", type=int, default=4, help="minimum run length")
    p.set_defaults(func=cmd_strings)

    p = sub.add_parser("refs", help="find binaries mentioning a symbol")
    p.add_argument("root")
    p.add_argument("--symbol", required=True)
    p.set_defaults(func=cmd_refs)

    p = sub.add_parser("inventory", help="one-line summary of every EXE/DLL")
    p.add_argument("root")
    p.add_argument("--out")
    p.set_defaults(func=cmd_inventory)

    args = parser.parse_args(list(argv) if argv is not None else None)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
