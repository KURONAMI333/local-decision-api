# som-pin — SOM publisher pin packet (dev tooling)

**Status (1.0.0): the packet bytes have been promoted into the
shipped classpath.** The canonical copies now live at
`common/src/main/resources/localinferenceapi/som/` (`manifest.json` +
`licenses/*`), packaged into the loader jars. This directory keeps only the
dev-side verification tooling:

- `manifest.sha256` — sha256 of the shipped `manifest.json`
  (`3744d73d6a1d35a802f737d09e7e856723c5f9cb6555c88b4ebe82f03f78c59c`).
  **Not a trust anchor.** The real trust anchor is `SomPin.MANIFEST_SHA256`,
  the same digest compiled into product code; this sidecar exists for
  reproducible dev verification.
- `verify_manifest.py` — manifest/package verifier copied verbatim from
  `som-windows-package-prototype` (hardened: release mode requires an
  externally-supplied trusted digest; rejects absolute/`..`/backslash paths,
  escaping symlinks, missing artifacts and missing notices).

The product-side trust chain (`internal.som`: `SomPin` → `ManifestVerifier` →
`Stager`/`SomPackage`) re-implements this verifier's semantics in Java. The
production wiring is live: `SomWiring` constructs `LlamaGate`, and the native
route is on by default in 1.0.0 — `-Dlocalinferenceapi.som.native=false`
opts back out to the bundled ONNX worker alone.

## Packet contents (JevK5 pin, 2026-09-28)

10 pinned artifacts: 4 runtime archives (llama.cpp `b10964` —
macos-arm64/macos-x64 tar.gz, windows vulkan/cpu zip), 1 model weights file
(JevK5-4B v0.3 Q5_K_M GGUF @ HF revision `ec67b0bf`, sha256
`b1df9869dd14c0f5b243fa956dd985f0141c3d23ec4847da4c503c73c1050601`), and 5
license/notice files staged from embedded resources. `manifest.json` also
carries per-member sha256 maps for each archive (macos-arm64 60 /
macos-x64 56 / win-vulkan 52 / win-cpu 51 members) so `Unpacker`
re-verifies every extracted file.

## Verified claims (2026-09-28, this Mac)

- All 10 pinned artifact hashes re-verified independently on this Mac
  (5 fetched artifacts via a machine-local `local_map.json` + 5 license
  files shipped as resources) — sha256 and member maps match.
- `llama-server` from the macos-arm64 archive executed live on loopback with
  a per-launch `--api-key`: Choice/Score/Noul real readout passed through
  `LlamaGate` + `SomRouter` + `LlamaSystemoneChannel`, including restart,
  offline cache, idle unload and 401-on-missing-auth (`LlamaNativeSmokeTest`).

## Not claims

- Not verified on Windows or Linux; the Vulkan/CPU zip bytes are pinned but
  no Windows run has launched them (CUDA build excluded by design, see
  `excluded_or_blocked` in the manifest).
- Not a legal opinion; redistribution review remains a human task.
- No player-readiness claim: unsigned binaries, SmartScreen/Gatekeeper
  reception and >16-option knockout parity are still open (see
  `open_questions` in the manifest).

## Verify (dev mode)

```sh
python3 common/som-pin/verify_manifest.py --mode dev \
  --root common/src/main/resources/localinferenceapi/som \
  --manifest common/src/main/resources/localinferenceapi/som/manifest.json \
  --local-map local_map.json   # artifact path -> local file
```

`local_map.json` is machine-local and intentionally not committed. For the
JevK5 packet it maps the 5 fetched artifact paths (4 runtime archives + the
GGUF) to local files; the 1.0.0 integration used a throwaway map under
`/tmp/jevk5-assets/` which does not survive a reboot — regenerate it (and
re-hash the targets) before trusting a skipped test's absence. License
artifacts resolve directly under `--root`.
