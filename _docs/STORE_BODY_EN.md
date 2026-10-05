**A library for mods that need to make a choice.** A companion mod, for example, can send “follow the player,” “return home,” and “fight” along with what is happening in the game. Local Decision API returns a choice; the companion mod decides what to do with it. It can also return a score or estimate how likely a statement is.

If a mod you use lists Local Decision API as a dependency, install it alongside that mod. Mod authors can start with the [developer kit and working example](https://github.com/KURONAMI333/local-decision-api/releases) or read the [integration guide](https://github.com/KURONAMI333/local-decision-api#for-mod-developers).

### The model

[System One Models (SOMs)](https://typesafe.ai/blog/introducing-system-one-models-and-jev) are built around structured decisions: a defined question produces a choice, score, or probability. This API uses that style of question. This release uses [JevK5-4B v0.3](https://github.com/allebee/jevk5) as its primary model. Its [model card](https://huggingface.co/alibiserikbay/JevK5) describes the weights and training; this mod supplies the Minecraft integration, request handling, and a bundled fallback model.

On a supported native route, the mod downloads about **3.1 GB** of model and runtime files on first use and keeps them in the game directory for later offline use. The mod JAR is about **450 MB** because it includes the fallback model. Inference uses the host computer's RAM and, when available, GPU memory. A rented server must permit the download and a worker process for the native route. Results are model estimates, so a calling mod should handle uncertain or unsuitable answers in its own logic.

### For mod authors

The Java API offers `decide` for choosing from supplied options, `score` for rating an ordered scale, and `noul` for estimating a statement's likelihood. Calls complete asynchronously. The [README](https://github.com/KURONAMI333/local-decision-api#for-mod-developers) covers dependency setup, result handling, limits, and migration from 0.1.0; the release includes a compile-only API artifact and runnable example.

The public API and developer example are MIT licensed. The implementation is All Rights Reserved with permission for normal mod dependencies and modpack use. JevK5 and other third-party components retain their own terms; see [LICENSE](https://github.com/KURONAMI333/local-decision-api/blob/main/LICENSE), [LICENSING.md](https://github.com/KURONAMI333/local-decision-api/blob/main/LICENSING.md), and [THIRD_PARTY.md](https://github.com/KURONAMI333/local-decision-api/blob/main/THIRD_PARTY.md).

Source: https://github.com/KURONAMI333/local-decision-api

Bugs and questions: comment on the CurseForge page, or reach me on [X](https://x.com/kuronami333).

[Support my mods on Patreon](https://www.patreon.com/KURONAMI333)
