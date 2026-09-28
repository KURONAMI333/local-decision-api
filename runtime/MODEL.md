# Bundled fallback model: Laya Typed-Decisions Q8E8

In 1.0.0 this ONNX worker is the **degraded-path fallback**; the primary path is the pinned JevK5-4B v0.3 GGUF served by an external `llama-server` (see `common/src/main/resources/localinferenceapi/som/manifest.json`). This document describes the bundled fallback. The JAR bundles an unofficial ONNX Q8E8 conversion of [Convai Innovations' Laya Typed-Decisions](https://huggingface.co/convaiinnovations/laya-typed-decisions). The converter is [VishalMysore/layaForWebTrained](https://huggingface.co/VishalMysore/layaForWebTrained), pinned at `dd0c52a2b563bea25279e2689689061dd0a1c382`. The source tokenizer and config are pinned to the [Laya family repository](https://huggingface.co/convaiinnovations/laya/tree/1c5edc17a7acd8701df6fc341c0d179f1c62c982/typed-decisions) at `1c5edc17a7acd8701df6fc341c0d179f1c62c982`.

The model is not created or trained by KURONAMI333. The original and conversion declare Apache-2.0. The conversion's `LICENSE` and `NOTICE.md` are preserved in `runtime/licenses/` and packaged under `third-party/model/`. The bundled ONNX graph uses weight-only int8 and int8 embeddings; it is not bitwise equivalent to upstream FP16. We reassemble the converter's 18 external-data parts at build time, then ship the graph and data in the MOD JAR. Players perform no download or setup.

## Reproducing the bundle

1. Run `python3 runtime/tools/fetch_model.py` from the repository root. This build-time script downloads pinned files and verifies SHA-256. `runtime/models/` is ignored by Git.
2. Use JDK 21 and run `./gradlew :runtime:shadowJar --no-build-cache`.
3. Run `python3 runtime/tools/smoke.py runtime/build/libs/runtime.jar`. The clean-cache test checks Choice, Score and Noul against fixed upstream ONNX outputs, request rejection, offline execution and shutdown.

SHA-256:

| File | SHA-256 |
|---|---|
| `laya_q8e8.onnx` | `599756d6506db9659279f4ac7871045801f90539844fbda6cd2918b6316e2d07` |
| `laya_q8e8.onnx.data` | `e5ac4bfe0503361dacac825a91a82ae860e3a5d38dfb021dcf0bd37369573b44` |
| `laya-tokenizer.json` | `6c8aaa9a542084f2457eab775d4eeb51f92a70c0fd9de28d5edb0ddec3c08d30` |
| `laya-config.json` | `ebf0cd524d92342a6be5e48e9fca3d7c2babfb5a56ccd79d2171ef5d8c7f7be8` |

## Measurements and limits

On a 24 GB Apple M5 Mac, the standalone Java worker running ONNX Runtime 1.30.0 with two CPU threads reached 924,172,288 bytes maximum RSS in a clean offline smoke test. The first-ready time was about 2.6 seconds. This is a **worker-only** peak. Fabric and NeoForge development JARs are about 450 MB each. Distribution size is secondary to runtime RAM use.

On macOS, the independent example MOD also completed Choice, Score and Noul through the real Minecraft 1.21.1 dedicated server on both Fabric and NeoForge. During one Fabric development-server run after inference, `ps` reported 815,648 KiB RSS for the server JVM and 898,544 KiB for the worker JVM. Their sum is a single server-side snapshot, not a peak or a client-plus-worker measurement. The Gradle daemon is excluded.

The isolated 1.21.1 client lifecycle fixture passed on both Fabric and NeoForge with the ONNX fallback JAR: world inference, disconnection, title-screen inference and a second world in the same JVM. During the Fabric run, 22 paired `ps` samples taken about 0.25 seconds apart showed a highest observed sum of 2,581,392 KiB RSS (client 1,689,536 KiB + worker 891,856 KiB). This is a sampled development client with JEI, not a peak, a typical modpack, or a Windows measurement. No usable NeoForge client memory sample was captured.

On Windows x64/JDK 21, the ONNX-era Fabric and NeoForge distribution JARs each passed in an isolated Minecraft 1.21.1 production dedicated server with an independently built consumer MOD. Choice, Score and Noul completed, world-stop invalidation passed, and both servers exited normally. The tests also passed from empty extraction caches with the test JDK's inbound and outbound network access blocked and Python absent from `PATH`. A standalone Windows worker run matched fixed ONNX outputs for all three primitives. In one warm-cache Fabric server snapshot after inference, server and worker working sets were 780,152,832 and 812,072,960 bytes; the worker process reported 839,127,040 bytes peak working set. These values are not a client measurement, representative modpack usage, or a combined peak. Logs: `_work/som-core-20260922/evidence/onnx-windows-{fabric,neoforge}-offline.log` in the development workspace. Windows integrated clients and Linux remain untested.

A fixed, small Minecraft diagnostic measured Choice 19/24, Score pairwise ranking 22/27 in English and 19/27 in Japanese, Noul ranking 25/27 in English and 20/27 in Japanese. These hand-written cases do not represent overall gameplay accuracy. Upstream explicitly describes Typed-Decisions as English-only and specialized for synthetic business workflows, with uncalibrated confidence. This API exposes model probabilities for ranking, but callers must not treat them as calibrated truth or a safety/permission mechanism. Laya does not provide an independent abstention slot. Score is a probability-weighted mean of caller-supplied ordered values; Noul is P(true) within a forced binary question. As in the reference SDK's `build_sequence`, options are shortened to 48 model tokens and a question that exceeds the 256-token head budget is deterministically shortened rather than rejected; affected responses carry `"truncated": true`, which also surfaces on the public result records.

The 0.1.0 public release uses a different Verdict model and API contract. The same ONNX backend is used on Windows and macOS. No separate macOS MLX or Core ML execution path is planned for the fallback worker.
