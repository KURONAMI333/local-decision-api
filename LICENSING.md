# Licensing — which terms cover which files

This repository is not uniformly MIT. The `LICENSE` file contains both
sets of terms; this file states the file-level scope. Where this file and
`LICENSE` seem to disagree, `LICENSE` governs.

## Scope

| Path | Terms |
|---|---|
| `common/src/main/java/com/kuronami/localinferenceapi/api/**` — the public API types (`LocalInference`, request/result records). In the published JARs these are the `com/kuronami/localinferenceapi/api/` classes; the compile-only Maven artifact ships the same classes and nothing else | MIT (LICENSE Part 1) |
| `examples/**` — the developer-kit example mod and its test fixtures | MIT (LICENSE Part 1) |
| Everything else authored by KURONAMI333: the rest of `common/src/` (`Constants`, `internal/**`, tests, resources including the SOM manifest), `fabric/`, `neoforge/`, `runtime/src/`, `common/som-pin/`, build files (`*.gradle`, `buildSrc/`, `gradle.properties`, wrapper scripts), documentation (`README.md`, `THIRD_PARTY.md`, `runtime/MODEL.md`, this file) and `branding/` | All Rights Reserved (LICENSE Part 2) |
| `runtime/models/**`, `runtime/licenses/**`, `common/src/main/resources/localinferenceapi/som/licenses/**`, and libraries fetched or bundled at build or runtime | Upstream terms — see `THIRD_PARTY.md` |

## What the split means in practice

- **Mod authors** may compile against the public API, declare a normal
  dependency on this mod, call it at runtime, and distribute their own
  mods that do so. That is expressly allowed and needs no permission.
  Compile against the compile-only Maven artifact rather than bundling
  the API classes into your own JAR — the full mod JAR already provides
  them at runtime, and a bundled copy would conflict. (MIT would permit
  the copy; this note is about it failing, not about it being forbidden.)
- **Modpacks and redistribution**: the unmodified mod JAR may be included
  in modpacks and redistributed on any platform or launcher, including
  monetised packs. No permission or credit is required.
- **Internal implementation**: its source is published for reading, review
  and bug reports. It may not be copied into other projects or republished,
  and modified versions of the implementation or of the mod JAR may not be
  distributed.

## Upstream components

Upstream licenses are unchanged and are not reasserted as ours:

- **JevK5-4B v0.3 weights** (fetched at first use): Apache-2.0 as declared
  by the upstream model card's license field. The upstream HF repository
  ships no LICENSE file — what exists upstream is the card declaration and
  the NOTICE file. `JEVK5-NOTICE.txt` inside the JAR is that upstream
  NOTICE verbatim (sha256-pinned); `MODEL-NOTICE.txt` is a packager-written
  attribution stub and states so itself. No counsel has reviewed this.
- **llama.cpp `llama-server` runtime** (fetched at first use, unmodified
  upstream binaries): MIT. Windows builds also carry the LLVM OpenMP
  runtime under Apache-2.0 with LLVM Exceptions.
- **Laya Typed-Decisions ONNX fallback** (bundled): Apache-2.0, per the
  model and its conversion; preserved under `runtime/licenses/` and inside
  the runtime JAR.
- **Other dependencies** (ONNX Runtime, DJL tokenizers, Gson, SLF4J, the
  embedded Fabric API modules in the Fabric JAR): each retains its own
  license; notices ship under `third-party/` in the distribution.

The terms above were written by the packager and have not been reviewed
by counsel. The published 0.1.0 release stays under its original MIT
grant; nothing here changes it.
