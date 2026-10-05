#!/usr/bin/env python3
"""Regression tests for tools/exe_scan_gate.py.

Runs without pytest: `python3 tools/tests/test_exe_scan_gate.py` executes the
same test_* functions and prints a summary. Every fixture is a synthetic zip
built in a temp directory -- no real mod jars are needed, so the suite can run
anywhere.

Covered properties:
  1. A clean jar (classes + resources + a native DLL) passes with rc 0.
  2. A real PE executable fails even under a non-.exe name (MZ + PE\\0\\0,
     characteristics without the DLL bit).
  3. A PE image WITH the DLL bit does not fail (DLLs are legal JNI natives).
  4. An entry merely *named* .exe fails even if its bytes are not PE.
  5. An exe inside a nested zip works at any depth, and a nested archive is
     found by its PK magic even when the member name does not end in a known
     archive suffix (the 0.1.0 pip-whl case).
  6. A member that looks like a nested archive but cannot be opened is an
     error and fails closed (rc 3), not silently skipped.
  7. A corrupt top-level input fails closed.
"""

from __future__ import annotations

import io
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
GATE = TOOLS / "exe_scan_gate.py"

sys.path.insert(0, str(TOOLS))
import exe_scan_gate  # noqa: E402


def _pe(dll: bool = False, machine: int = 0x8664) -> bytes:
    """Minimal MZ+PE blob: e_lfanew=0x80, PE sig, machine, characteristics."""
    b = bytearray(0xA0)
    b[0:2] = b"MZ"
    struct.pack_into("<I", b, 0x3C, 0x80)
    b[0x80:0x84] = b"PE\x00\x00"
    struct.pack_into("<H", b, 0x84, machine)
    struct.pack_into("<H", b, 0x96, 0x2000 if dll else 0x0102)
    return bytes(b)


def _zip_bytes(members: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in members.items():
            z.writestr(name, data)
    return buf.getvalue()


def _run_gate(*paths: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(GATE), *map(str, paths)],
        capture_output=True,
        text=True,
        check=False,
    )


def _write(tmp: Path, name: str, blob: bytes) -> Path:
    p = tmp / name
    p.write_bytes(blob)
    return p


def test_clean_jar_passes(tmp_path: Path):
    jar = _write(
        tmp_path,
        "clean.jar",
        _zip_bytes(
            {
                "com/example/Foo.class": b"\xca\xfe\xba\xbe" + b"\x00" * 64,
                "config.json": b"{}",
                "native/win-x86_64/cpu/tokenizers.dll": _pe(dll=True),
                "native/lib/linux-x86_64/libx.so": b"\x7fELF" + b"\x02" * 60,
            }
        ),
    )
    r = _run_gate(jar)
    assert r.returncode == 0, r.stderr
    out = exe_scan_gate.scan_path(str(jar))
    assert out["exe_failures"] == 0 and not out["errors"]
    assert out["warnings"] >= 1  # the DLL is reported as a warning


def test_pe_exe_under_data_name_fails(tmp_path: Path):
    jar = _write(tmp_path, "a.jar", _zip_bytes({"payload.bin": _pe()}))
    r = _run_gate(jar)
    assert r.returncode == 2
    assert "PE_EXE" in r.stderr


def test_dot_exe_name_fails_without_pe(tmp_path: Path):
    jar = _write(tmp_path, "b.jar", _zip_bytes({"tool.exe": b"not really a PE"}))
    r = _run_gate(jar)
    assert r.returncode == 2


def test_dll_does_not_fail(tmp_path: Path):
    jar = _write(tmp_path, "c.jar", _zip_bytes({"lib/helper.dll": _pe(dll=True)}))
    r = _run_gate(jar)
    assert r.returncode == 0, r.stderr


def test_nested_archive_by_magic_any_name(tmp_path: Path):
    """0.1.0 case: a pip .whl (zip) inside a runtime jar holding distlib .exe."""
    whl = _zip_bytes({"pip/_vendor/distlib/t64.exe": _pe()})
    inner = _zip_bytes({"META-INF/resources/libpython/pip.whl": whl})
    outer = _zip_bytes({"bundled/runtime.bin": inner})  # misleading name
    jar = _write(tmp_path, "d.jar", outer)
    r = _run_gate(jar)
    assert r.returncode == 2
    out = exe_scan_gate.scan_path(str(jar))
    kinds = [f["kind"] for f in out["flagged"]]
    assert any(k.startswith("PE_EXE") for k in kinds)
    assert len(out["nested"]) == 2  # runtime.bin + pip.whl


def test_broken_nested_archive_fails_closed(tmp_path: Path):
    blob = b"PK\x03\x04" + b"\x00" * 32  # magic ok, body not a real zip
    jar = _write(tmp_path, "e.jar", _zip_bytes({"packed.zip": blob}))
    r = _run_gate(jar)
    assert r.returncode == 3


def test_corrupt_toplevel_fails_closed(tmp_path: Path):
    bad = _write(tmp_path, "f.jar", b"this is not a zip at all")
    r = _run_gate(bad)
    assert r.returncode == 3


def test_mz_without_pe_signature_fails(tmp_path: Path):
    jar = _write(tmp_path, "g.jar", _zip_bytes({"stub.dat": b"MZ" + b"\x00" * 128}))
    r = _run_gate(jar)
    assert r.returncode == 2


def test_dll_named_exe_fails(tmp_path: Path):
    """C3 P2: a PE DLL renamed *.exe must fail on the NAME, even though its
    bytes are a legal DLL."""
    jar = _write(tmp_path, "h.jar", _zip_bytes({"bin/fake.exe": _pe(dll=True)}))
    r = _run_gate(jar)
    assert r.returncode == 2


def test_nbs_member_is_not_an_archive(tmp_path: Path):
    """C3 P3: a real .nbs (non-zip binary) inside a jar is a normal resource,
    not an archive to open -- earlier versions error-failed on it."""
    jar = _write(tmp_path, "i.jar", _zip_bytes({"songs/song.nbs": b"\x00" * 202}))
    r = _run_gate(jar)
    assert r.returncode == 0, r.stderr


def test_pk0708_blob_is_not_an_archive(tmp_path: Path):
    """C3 P3: a member starting with the zip DATA-DESCRIPTOR signature
    (PK\\x07\\x08) is not a nested archive."""
    jar = _write(tmp_path, "j.jar", _zip_bytes({"data/blob.bin": b"PK\x07\x08" + b"\x00" * 200}))
    r = _run_gate(jar)
    assert r.returncode == 0, r.stderr


def test_oversize_nested_archive_fails_closed(tmp_path: Path):
    """A nested archive above the inflation bound is an error, not a skip."""
    real_nested = _zip_bytes({"a.txt": b"hi"})
    jar = _write(tmp_path, "k.jar", _zip_bytes({"packed.jar": real_nested}))
    old = exe_scan_gate.MAX_NESTED_INFLATE
    try:
        exe_scan_gate.MAX_NESTED_INFLATE = 8  # tiny bound for the test
        out = exe_scan_gate.scan_path(str(jar))
    finally:
        exe_scan_gate.MAX_NESTED_INFLATE = old
    assert out["errors"], "oversize nested archive was not reported"
    assert "too large" in out["errors"][0]["error"]


def test_far_pe_signature_beyond_head_fails(tmp_path: Path):
    """C3 f11: e_lfanew beyond the 64 KiB head is resolved by a bounded
    re-read; a real PE there still fails."""
    b = bytearray(70000)
    b[0:2] = b"MZ"
    struct.pack_into("<I", b, 0x3C, 0xFFF8)
    b[0xFFF8:0xFFFC] = b"PE\x00\x00"
    struct.pack_into("<H", b, 0xFFFC, 0x8664)
    struct.pack_into("<H", b, 0xFFF8 + 22, 0x0102)
    jar = _write(tmp_path, "l.jar", _zip_bytes({"x.bin": bytes(b)}))
    r = _run_gate(jar)
    assert r.returncode == 2


def main() -> int:
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failures = 0
    for fn in fns:
        with tempfile.TemporaryDirectory() as td:
            try:
                fn(Path(td))
            except Exception as exc:  # noqa: BLE001
                failures += 1
                print(f"FAIL {fn.__name__}: {exc}")
            else:
                print(f"PASS {fn.__name__}")
    print(f"{len(fns) - failures}/{len(fns)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
