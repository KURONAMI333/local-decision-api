**Local Decision API is a library for other Minecraft mods.** A compatible mod provides a situation and a set of possible answers or a rating scale. This mod runs a model on the machine running the game and returns a choice or rating for that request. For example, another mod could provide three possible actions and receive one choice; that mod decides what those actions are and how to use the result. Local Decision API adds no items, blocks, mobs, or standalone gameplay.

This is for bounded decisions supplied by the calling mod. It is not a chatbot or a general-purpose game assistant. Results can be wrong; the calling mod decides what to do with them and provides a fallback.

### Where it runs

Requests are processed on the machine running the mod — a player's PC, or a dedicated or self-hosted server when a server-side mod calls it — with no cloud AI account. On first use the mod downloads about 3.1 GB of hash-pinned model and runtime files into the game directory; after they are cached it works offline. To skip the download entirely and use only the bundled fallback, launch with `-Dlocalinferenceapi.som.native=false`. The JAR itself is about 450 MB because it includes that fallback model. The inference worker runs outside Minecraft's Java heap, but it still uses the host machine's RAM and, when available, GPU memory.

Platform coverage is uneven. The native path has been exercised end to end on Apple Silicon Macs and on a Windows x64 dedicated server; the integrated singleplayer client, macOS x64, and CPU-only Windows machines are unverified, and failures there fall back to the bundled CPU worker. Linux has no native runtime — the bundled fallback always answers. On a server, the host must allow the roughly 3.1 GB download and a spawned worker process; a shared or free host that blocks either leaves the library unable to answer.

### Who needs it

Install this mod when another mod lists Local Decision API as a dependency. Installing it alone does not change gameplay. Mod developers can use the public API and example in the developer kit; see the source repository for integration details.

### Limits and licenses

Choices and ratings are model outputs, not verified facts or permission checks. The public API and developer example are MIT licensed; the internal implementation is All Rights Reserved with permission for normal mod dependencies and modpack use. Bundled models and other dependencies keep their own licenses. See the distributed LICENSE, LICENSING.md, and THIRD_PARTY.md for the exact terms.

Source: https://github.com/KURONAMI333/local-inference-api
Bugs and questions: comment on the CurseForge page, or @kuronami333 on X.

[![Support my mods on Patreon](https://raw.githubusercontent.com/KURONAMI333/music-disc-maker/6a0a895769575a1a58fd1fb6dfb15e259bcefccb/_docs/support/patreon.png)](https://www.patreon.com/KURONAMI333) [![Follow @kuronami333 on X](https://raw.githubusercontent.com/KURONAMI333/music-disc-maker/6a0a895769575a1a58fd1fb6dfb15e259bcefccb/_docs/support/x.png)](https://x.com/kuronami333)
