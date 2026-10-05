#!/usr/bin/env python3
"""Fail-closed executable-content gate for distribution archives.

Scans a jar/zip (and every archive nested inside it at any depth) for Windows
executables. Intended to be wired into packaging so a distribution archive can
never ship a PE executable unnoticed.

Detection semantics (the same rules also gate the release pipeline; keep the
two implementations in sync):

  * FAIL on any member whose name ends in ``.exe`` -- checked on the NAME,
    independent of content, so a PE DLL renamed ``x.exe`` still fails.
  * FAIL on any member whose bytes are a real PE executable: ``MZ`` magic
    plus the ``PE\\0\\0`` signature at the offset stored at ``0x3c``, without
    the IMAGE_FILE_DLL (0x2000) characteristic. DLLs are reported as
    warnings only: they are normal JNI natives, not the ".exe files" a store
    moderation notice means.
  * FAIL on an ``MZ`` stub whose PE signature cannot be verified
    (truncated, missing, or pointing beyond the read bound): an executable
    header we cannot disprove must not pass a fail-closed check.
  * Other executable-ish extensions (dll/sys/scr/com/msi/cpl/ocx/drv) are
    reported as warnings, not failures.

Recursion:

  * A member is opened as a nested archive when its bytes start with a real
    zip magic (``PK\\x03\\x04`` local header or ``PK\\x05\\x06`` empty-archive
    EOCD) or its name ends in a real archive suffix
    (.jar/.zip/.war/.ear/.apk/.whl) -- a jar stored under a misleading
    extension is still inspected, while a non-zip payload (for example an
    ``.nbs`` score file, or bytes starting with the ``PK\\x07\\x08`` data
    descriptor) is not mistaken for one.
  * A member that looks like an archive but cannot be opened is an error,
    and errors fail the gate (we cannot prove it carries no executable).
  * Bounds: 64 KiB head read, at most 1 MiB re-read when a PE header points
    beyond it, 1 GiB maximum nested-archive inflation (the real nested
    runtime jar is ~470 MB and must still be inspected), recursion depth 8.
    Exceeding a bound is an error.

Exit codes: 0 = clean, 2 = executable findings, 3 = scan error / unreadable
input.

Usage:
    python3 tools/exe_scan_gate.py <archive> [<archive> ...] [--json-out PATH]

The tool only reads bytes; it never executes what it scans and prints only
entry names, sizes and classifications -- never payload content.
"""

from __future__ import annotations

import argparse
import io
import json
import struct
import sys
import zipfile

HEAD_READ = 65536
PE_SIG_READ_MAX = 1 << 20          # 1 MiB cap for the e_lfanew recheck
MAX_NESTED_INFLATE = 1 << 30       # 1 GiB -- nested runtime.jar is ~470 MB
MAX_DEPTH = 8

ZIP_MAGICS = (b"PK\x03\x04", b"PK\x05\x06")
ARCHIVE_SUFFIXES = (".jar", ".zip", ".war", ".ear", ".apk", ".whl")
EXE_SUFFIX = ".exe"
OTHER_EXEC_EXTS = ("dll", "sys", "scr", "com", "msi", "cpl", "ocx", "drv")


def _classify_head(head: bytes, name: str) -> tuple[str | None, bool]:
    """Return (kind, need_more_head_bytes). kind is None when clean/unknown."""
    name_l = name.lower()
    if head[:2] == b"MZ":
        if len(head) < 0x40:
            return ("MZ_TRUNCATED", False)
        pe_off = struct.unpack("<I", head[0x3C:0x40])[0]
        if pe_off + 24 > len(head):
            return (None, True)
        if head[pe_off : pe_off + 4] != b"PE\x00\x00":
            return ("MZ_NO_PE_SIG", False)
        machine = struct.unpack("<H", head[pe_off + 4 : pe_off + 6])[0]
        chars = struct.unpack("<H", head[pe_off + 22 : pe_off + 24])[0]
        kind = "PE_DLL" if chars & 0x2000 else "PE_EXE"
        return (f"{kind}(machine=0x{machine:04x})", False)
    if "." in name_l and name_l.rsplit(".", 1)[-1] in OTHER_EXEC_EXTS:
        return ("EXT:" + name_l.rsplit(".", 1)[-1], False)
    return (None, False)


def _is_failure(kind: str | None, name: str) -> bool:
    if name.lower().endswith(EXE_SUFFIX):
        return True
    if not kind:
        return False
    return kind.startswith("PE_EXE") or kind in ("MZ_NO_PE_SIG", "MZ_TRUNCATED")


def _looks_like_archive(head: bytes, name: str) -> bool:
    return head[:4] in ZIP_MAGICS or name.lower().endswith(ARCHIVE_SUFFIXES)


def scan_zip(blob: bytes, chain: str, out: dict, depth: int) -> None:
    if depth > MAX_DEPTH:
        out["errors"].append({"archive": chain, "error": "max-depth"})
        return
    try:
        zf = zipfile.ZipFile(io.BytesIO(blob))
    except Exception as exc:
        out["errors"].append({"archive": chain, "error": f"open:{exc}"[:160]})
        return
    n_entries = 0
    for info in zf.infolist():
        name = info.filename
        if name.endswith("/"):
            continue
        n_entries += 1
        try:
            with zf.open(name) as fh:
                head = fh.read(HEAD_READ)
        except Exception as exc:
            out["errors"].append(
                {"archive": chain, "entry": name, "error": str(exc)[:160]}
            )
            continue

        kind, need_more = _classify_head(head, name)
        if need_more:
            try:
                with zf.open(name) as fh:
                    head = fh.read(min(info.file_size, PE_SIG_READ_MAX))
                kind, need_more = _classify_head(head, name)
            except Exception as exc:
                out["errors"].append(
                    {"archive": chain, "entry": name, "error": str(exc)[:160]}
                )
                continue
            if need_more:
                kind = "MZ_NO_PE_SIG"

        if kind or name.lower().endswith(EXE_SUFFIX):
            out["flagged"].append(
                {
                    "archive": chain,
                    "entry": name,
                    "kind": kind or "EXT:exe",
                    "size": info.file_size,
                    "fail": _is_failure(kind, name),
                }
            )

        if _looks_like_archive(head, name):
            if info.file_size > MAX_NESTED_INFLATE:
                out["errors"].append(
                    {
                        "archive": chain,
                        "entry": name,
                        "error": f"nested archive too large ({info.file_size})",
                    }
                )
                continue
            try:
                data = zf.read(name)
            except Exception as exc:
                out["errors"].append(
                    {"archive": chain, "entry": name, "error": str(exc)[:160]}
                )
                continue
            out["nested"].append(
                {"archive": chain, "entry": name, "size": info.file_size}
            )
            scan_zip(data, f"{chain}!{name}", out, depth + 1)
    out["archives"].append({"archive": chain, "entries": n_entries})


def scan_path(path: str) -> dict:
    out = {"archives": [], "nested": [], "flagged": [], "errors": []}
    try:
        with open(path, "rb") as fh:
            blob = fh.read()
    except OSError as exc:
        out["errors"].append({"archive": path, "error": f"read:{exc}"[:160]})
        return out
    scan_zip(blob, path.rsplit("/", 1)[-1], out, 0)
    fails = [f for f in out["flagged"] if f["fail"]]
    out["exe_failures"] = len(fails)
    out["warnings"] = len(out["flagged"]) - len(fails)
    out["entries"] = sum(a["entries"] for a in out["archives"])
    return out


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("archives", nargs="+")
    ap.add_argument("--json-out", metavar="PATH")
    args = ap.parse_args(argv)

    report = {}
    worst = 0
    for path in args.archives:
        out = scan_path(path)
        report[path] = out
        if out["errors"]:
            worst = max(worst, 3)
        elif out["exe_failures"]:
            worst = max(worst, 2)
        print(
            f"{path.rsplit('/', 1)[-1]}: entries={out['entries']} "
            f"nested={len(out['nested'])} exe_failures={out['exe_failures']} "
            f"warnings={out['warnings']} errors={len(out['errors'])}",
            file=sys.stderr,
        )
        for f in out["flagged"]:
            if f["fail"]:
                print(
                    f"  FAIL {f['kind']} {f['archive']} -> {f['entry']} "
                    f"({f['size']} B)",
                    file=sys.stderr,
                )
        for e in out["errors"]:
            print(f"  ERROR {e.get('entry') or e['archive']}: {e['error']}", file=sys.stderr)

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=1)
    else:
        json.dump(report, sys.stdout, indent=1)
        sys.stdout.write("\n")

    if worst == 3:
        print("exe-scan-gate: ERROR -- archive(s) could not be fully inspected", file=sys.stderr)
    elif worst == 2:
        print("exe-scan-gate: FAIL -- executable content found", file=sys.stderr)
    else:
        print("exe-scan-gate: OK -- no executables", file=sys.stderr)
    return worst


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
