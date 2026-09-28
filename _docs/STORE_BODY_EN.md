**Local Decision API is a library for other Minecraft mods.** A compatible mod describes a situation and provides a set of options or a rating scale; this mod returns one choice or rating from a small model.

For example, a mod can pass three candidate actions and get one of them back; what the candidates mean and what happens with the answer stays with the calling mod. Each request is bounded: the model weighs only the candidates or scale it is given. Answers can be wrong, so the calling mod is expected to judge results and keep its own fallback.

### Where it runs

Inference runs on the computer or server hosting the game, without a cloud account or external AI service. On first use the mod downloads about 3.1 GB of hash-pinned model and runtime files into the game directory; once cached it also works offline. To skip that download and use only the bundled fallback model, launch with `-Dlocalinferenceapi.som.native=false`. The JAR itself is about 450 MB because it contains that fallback. The inference worker runs outside Minecraft's Java heap, but still uses the machine's RAM and, when available, GPU memory.

The primary model path has been exercised end to end on Apple Silicon Macs and on a Windows x64 dedicated server. It is not yet verified inside the singleplayer client, and on Linux there is no such runtime at all; wherever the primary path cannot start, the bundled fallback answers instead with reduced quality. On a server, the host must allow the roughly 3.1 GB download and a spawned worker process; a shared or free host that blocks either leaves the library unable to answer.

### Installing and developing

Install this mod when another mod lists it as a dependency. Mod developers integrate through the public API, a compile-only dependency exposing `decide` (pick one of the given choices), `score` (rate on an ordered scale), and `noul` (estimate the probability that a statement holds). The developer kit in the source repository includes a runnable example mod.

### Limits and licenses

Choices and ratings are model outputs, not verified facts or permission checks. The public API and developer example are MIT licensed; the internal implementation is All Rights Reserved with permission for normal mod dependencies and modpack use. Bundled models and other dependencies keep their own licenses. See the distributed LICENSE, LICENSING.md, and THIRD_PARTY.md for the exact terms.

Source: https://github.com/KURONAMI333/local-inference-api
Bugs and questions: comment on the CurseForge page, or @kuronami333 on X.

[![Support my mods on Patreon](https://raw.githubusercontent.com/KURONAMI333/music-disc-maker/6a0a895769575a1a58fd1fb6dfb15e259bcefccb/_docs/support/patreon.png)](https://www.patreon.com/KURONAMI333) [![Follow @kuronami333 on X](https://raw.githubusercontent.com/KURONAMI333/music-disc-maker/6a0a895769575a1a58fd1fb6dfb15e259bcefccb/_docs/support/x.png)](https://x.com/kuronami333)
