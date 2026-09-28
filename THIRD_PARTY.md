# Third-party components and attribution

Local Decision API's public API sources (`com.kuronami.localinferenceapi.api`) and developer-kit example are MIT licensed; the internal implementation is All Rights Reserved. The file-level scope is in LICENSING.md and the terms in LICENSE. Neither relicenses models or dependencies.

## Models

Local Decision API 1.0.0 uses two local models:

- **JevK5-4B v0.3 (Q5_K_M)** — the primary model, fetched once from its pinned upstream revision and staged under `.localinferenceapi/som/` in the game directory. Apache-2.0 per the upstream model card's license field; it builds on the Qwen3.5 base (Apache-2.0) and the SemIf readout approach (MIT). The upstream HF repository carries no standalone LICENSE file — what exists upstream is the card declaration and the NOTICE file. `JEVK5-NOTICE.txt`, shipped in the JAR under `localinferenceapi/som/licenses/` and staged alongside the model, is that upstream NOTICE verbatim from `allebee/jevk5` tag `v0.3.3` (commit `f944fe37ff1d5ed3830aa4c8d88b7189c8c1268a`), sha256-pinned in the manifest. `MODEL-NOTICE.txt` in the same directory is a packager-written attribution stub. None of this is legal advice.
- **Laya Typed-Decisions (Q8E8 ONNX)** — the bundled fallback model inside the JAR, created by Convai Innovations and contributors under Apache-2.0. The [conversion](https://huggingface.co/VishalMysore/layaForWebTrained) is by VishalMysore and independently declares Apache-2.0. It exports the model to ONNX and quantizes weights and embeddings; results can differ from the original. Laya uses the ModernBERT-large backbone by Answer.AI and LightOn. These projects do not endorse this MOD.

The bundled fallback's pinned revisions, file hashes and build instructions are in [runtime/MODEL.md](runtime/MODEL.md). Its converter LICENSE and NOTICE are preserved in `runtime/licenses/` and the distributed runtime JAR under `third-party/model/`; the full Apache-2.0 text is also included. The native path's pinned manifest, per-member hashes, and notices live under `common/src/main/resources/localinferenceapi/som/` (`manifest.json`, `LLAMA-CPP-LICENSE.txt`, `JEVK5-NOTICE.txt`, `MODEL-NOTICE.txt`, `LICENSE-LLVM-OpenMP.txt`, `Apache-2.0.txt`). Model weights remain under their upstream terms.

## Execution libraries

The fallback worker uses ONNX Runtime 1.30.0, DJL Hugging Face tokenizers 0.38.0, Gson 2.10.1 and their dependencies. The native path launches an unmodified upstream [llama.cpp](https://github.com/ggml-org/llama.cpp) `llama-server` (MIT) at pinned build `b10964`; its Windows binaries also carry the LLVM OpenMP runtime (Apache-2.0 with LLVM exceptions) — the corresponding license texts ship in the JAR. The Fabric distribution embeds Fabric API modules. Each retains its own license. The runtime build preserves dependency LICENSE, NOTICE and COPYING files under `third-party/<dependency-jar>/`; nested Fabric API JARs retain their own notices.

PyFish was an architectural reference during the 0.1.0 Python-embedding prototype. It is not bundled or required in 1.0.0, and no PyFish source has been copied into this project.
