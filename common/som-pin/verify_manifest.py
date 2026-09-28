#!/usr/bin/env python3
"""Deterministic manifest verifier for the kuro-som-runtime package prototype.

Read-only. Never downloads, never executes packaged files.

Modes:
  --mode release (default): verifies an install package that must be fully
    self-contained under --root. Requires an externally supplied trusted
    manifest digest (--trusted-manifest-sha256 or --trusted-manifest-file)
    and FAILS without it. Rejects: absolute paths, path escapes, symlinks
    that resolve outside --root, local_reference fields, missing artifacts,
    missing declared license/notice files, malformed manifest.
    NOTE: a sidecar manifest.sha256 shipped next to the manifest only detects
    edits where the sidecar was left unchanged. It CANNOT authenticate a
    distribution where manifest and sidecar were replaced together. Only the
    out-of-band trusted digest (e.g. pinned inside the signed MOD artifact)
    binds the manifest to the publisher's intent.
  --mode dev: verifies prototype assets on this machine. Artifacts absent
    under --root may resolve through --local-map (JSON: path -> local path).
    Trust anchor optional (warn if absent). Absolute local paths allowed only
    via --local-map, never from the manifest itself.

Usage:
  python3 verify_manifest.py --root <dir> --mode release --trusted-manifest-sha256 <hex>
  python3 verify_manifest.py --root <dir> --mode dev --local-map local_inventory.json

Exit 0 = verified. Exit 1 = one or more failures. Exit 2 = usage error.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path, PurePosixPath

REQUIRED_ARTIFACT_FIELDS = ("path", "sha256", "size", "role")
_DRIVE_RE = re.compile(r"^[A-Za-z]:")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 22), b""):
            h.update(chunk)
    return h.hexdigest()


def fail_open(msg: str) -> int:
    print(f"FAIL: {msg}")
    return 1


def is_safe_relpath(rel) -> bool:
    if not isinstance(rel, str) or not rel:
        return False
    if _DRIVE_RE.match(rel) or rel.startswith(("/", "\\")) or "\\" in rel:
        return False  # absolute, drive-letter, UNC, or backslash form — never package-relative
    parts = PurePosixPath(rel).parts
    return all(part not in ("..", "") for part in parts)


def first_symlink_component(root: Path, rel: str) -> Path | None:
    """Return the first symlink in root/rel's component chain (incl. intermediate
    dirs and the leaf itself), or None. Stops at the first missing component."""
    cur = root
    for part in PurePosixPath(rel).parts:
        cur = cur / part
        if cur.is_symlink():
            return cur
        if not cur.exists():
            break
    return None


def resolves_inside(root: Path, target: Path) -> bool:
    try:
        target.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", required=True, help="package root directory to verify")
    ap.add_argument("--manifest", default=None, help="manifest.json path (default: <root>/manifest.json)")
    ap.add_argument("--mode", choices=["release", "dev"], default="release")
    ap.add_argument("--trusted-manifest-sha256", default=None,
                    help="externally supplied trusted sha256 of manifest.json")
    ap.add_argument("--trusted-manifest-file", default=None,
                    help="file containing trusted sha256 (e.g. inside a separately-anchored channel)")
    ap.add_argument("--local-map", default=None, help="dev mode only: JSON map of package path -> local file")
    a = ap.parse_args()

    root = Path(a.root)
    mpath = Path(a.manifest) if a.manifest else root / "manifest.json"
    failures, warnings = [], []

    if not root.is_dir():
        return fail_open(f"root not found: {root}")
    if not mpath.is_file():
        return fail_open(f"manifest not found: {mpath}")

    # --- parse manifest (malformed -> fail) ---
    try:
        manifest = json.loads(mpath.read_text(encoding="utf-8"))
    except Exception as e:
        return fail_open(f"manifest malformed JSON: {e}")
    if not isinstance(manifest, dict) or not isinstance(manifest.get("artifacts"), list):
        return fail_open("manifest malformed: missing 'artifacts' list")

    # --- trust anchor ---
    trusted = a.trusted_manifest_sha256
    if a.trusted_manifest_file:
        try:
            trusted = Path(a.trusted_manifest_file).read_text().split()[0].strip()
        except Exception as e:
            return fail_open(f"cannot read trusted manifest file: {e}")
    actual_digest = sha256(mpath)
    if a.mode == "release":
        if not trusted:
            failures.append("no trusted manifest digest supplied (--trusted-manifest-sha256 required in release mode)")
        elif actual_digest != trusted.lower():
            failures.append(f"manifest TAMPERED: sha256 {actual_digest} != trusted {trusted.lower()}")
        else:
            print(f"OK  manifest anchored: {actual_digest[:16]}… == trusted digest")
    else:
        if trusted:
            if actual_digest != trusted.lower():
                failures.append(f"manifest TAMPERED: sha256 {actual_digest} != trusted {trusted.lower()}")
            else:
                print(f"OK  manifest anchored: {actual_digest[:16]}… == trusted digest")
        else:
            warnings.append("dev mode without trusted digest — manifest edits are only caught by sidecar (limited)")

    # --- opportunistic sidecar check (both modes) ---
    sidecar = mpath.with_suffix(".sha256")
    if sidecar.is_file():
        expected = sidecar.read_text().split()[0].strip().lower()
        if expected != actual_digest:
            failures.append(f"sidecar mismatch: manifest.sha256 pins {expected[:16]}… but manifest is {actual_digest[:16]}…")
        else:
            print(f"OK  sidecar consistent: manifest.sha256 == {actual_digest[:16]}…")
    elif a.mode == "dev":
        warnings.append("no manifest.sha256 sidecar — accidental-edit detection unavailable")

    # --- dev-mode local map ---
    local_map = {}
    if a.local_map:
        if a.mode != "dev":
            return fail_open("--local-map is only allowed in --mode dev")
        try:
            raw_map = json.loads(Path(a.local_map).read_text(encoding="utf-8"))
            local_map = raw_map.get("paths", raw_map) if isinstance(raw_map, dict) else {}
        except Exception as e:
            return fail_open(f"local map malformed: {e}")

    # --- artifacts ---
    license_paths = set()
    for art in manifest["artifacts"]:
        # field-type check first: wrong types must fail closed, not traceback
        if (not isinstance(art, dict)
                or any(k not in art for k in REQUIRED_ARTIFACT_FIELDS)
                or not isinstance(art.get("path"), str)
                or not isinstance(art.get("sha256"), str)
                or not isinstance(art.get("size"), int)
                or isinstance(art.get("size"), bool)):
            failures.append(f"malformed artifact entry: {art!r:.80}")
            continue
        rel = art["path"]

        # path safety (rejects ../, absolute, drive-letter C:/..., UNC, backslash)
        if not is_safe_relpath(rel):
            failures.append(f"UNSAFE path: {rel!r}")
            continue
        # release mode: no machine-local references inside the manifest
        if "local_reference" in art:
            if a.mode == "release":
                failures.append(f"local_reference not allowed in release manifest: {rel}")
                continue
            warnings.append(f"{rel}: local_reference present (dev only)")

        target = root / rel
        resolved_via = None
        if a.mode == "release":
            # no symlink anywhere in the path chain, incl. intermediate dirs
            link = first_symlink_component(root, rel)
            if link is not None:
                failures.append(f"SYMLINK in release path: {rel} (component {link} -> {link.resolve()})")
                continue
            if not resolves_inside(root, target):
                failures.append(f"ESCAPE: {rel} resolves outside root")
                continue
        elif target.is_symlink() and not resolves_inside(root, target):
            failures.append(f"SYMLINK escape: {rel} -> {target.resolve()}")
            continue
        if not target.is_file() and a.mode == "dev":
            mapped = local_map.get(rel) or art.get("local_reference")
            if isinstance(mapped, str):
                cand = Path(mapped)
                if cand.is_file():
                    target, resolved_via = cand, str(cand)
        if not target.is_file():
            failures.append(f"MISSING {rel}")
            continue

        size = target.stat().st_size
        if size != art["size"]:
            failures.append(f"SIZE {rel}: {size} != {art['size']}")
        digest = sha256(target)
        if digest != art["sha256"].lower():
            failures.append(f"HASH {rel}: {digest} != {art['sha256']}")
        if size == art["size"] and digest == art["sha256"].lower():
            tag = f"  via {resolved_via}" if resolved_via else ""
            print(f"OK  {rel}  sha256={digest[:16]}…{tag}")

        lfs = art.get("license_files", [])
        if not isinstance(lfs, list):
            failures.append(f"malformed license_files (not a list): {rel}")
            continue
        for lf in lfs:
            if not isinstance(lf, str) or not is_safe_relpath(lf):
                failures.append(f"malformed/unsafe license path {lf!r} in {rel}")
                continue
            license_paths.add(lf)

    # --- license/notice enforcement ---
    declared = {art.get("path") for art in manifest["artifacts"] if isinstance(art, dict)}
    for lp in sorted(license_paths):
        if lp not in declared:
            failures.append(f"LICENSE file not declared as artifact: {lp}")
            continue
        if a.mode == "release":
            link = first_symlink_component(root, lp)
            if link is not None:
                failures.append(f"LICENSE SYMLINK in release path: {lp} (component {link})")
                continue
        if not (root / lp).is_file():
            failures.append(f"LICENSE file missing from package: {lp}")

    for w in warnings:
        print(f"WARN {w}")
    if failures:
        print("\nFAILURES:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"\nPASS: {len(manifest['artifacts'])} artifacts verified (mode={a.mode}, {mpath.name})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
