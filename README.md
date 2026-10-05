# Local Decision API

Local Decision API helps Minecraft mods choose between supplied options, rate a situation, or estimate a statement's likelihood. The calling mod provides the game context and decides how to use the answer. This library adds no gameplay by itself.

## Status

Version 1.0.2 targets Minecraft **1.21.1 Fabric/NeoForge on Java 21+** and a **Minecraft 1.20.1 Forge candidate on a Java 17 host with a Java 21+ worker**. Check the [GitHub releases](https://github.com/KURONAMI333/local-decision-api/releases) for published downloads. The primary inference path runs **[JevK5-4B v0.3 (Q5_K_M)](https://huggingface.co/alibiserikbay/JevK5-GGUF)** through a pinned **llama.cpp `llama-server`** process outside the Minecraft JVM; the MOD automatically stages the hash-pinned runtime and model into the game directory on first use. If the native worker is unavailable or still starting, the bundled **Laya Typed-Decisions** ONNX worker inside the same JAR serves requests as a degraded fallback. Model probabilities are not well-calibrated truth, and gameplay or language quality is not guaranteed.

The API follows the structured question style of [System One Models (SOMs)](https://typesafe.ai/blog/introducing-system-one-models-and-jev). JevK5's [model card](https://huggingface.co/alibiserikbay/JevK5) explains its weights and training; this mod supplies the Minecraft integration and fallback.

This source tree contains the 1.0.2 packaging update. The release page identifies which versions have been published.

Current 1.0.2 verification is narrower than the platform targets above. The exact Forge 1.20.1 candidate passed a Windows dedicated-server ONNX check with a Java 17 host and Java 21 worker: SDK requests, game-thread delivery, stale-session rejection, world save and normal cleanup. The exact Fabric 1.21.1 candidate passed Windows dedicated-server loader initialization and ONNX fallback, then returned an actual native SOM decision (`route=NATIVE`, `status=NATIVE`, `availability=SERVING`) after native startup; the world saved and the host stopped normally. These are scoped server results, not client or model-quality acceptance. In a separate Windows NeoForge 21.1.227 client play, the exact staged candidate loaded and a calling mod observed 12 typed choice responses with two canceled requests. The backend route and gameplay quality were not established by that play.

Earlier macOS native and ONNX development tests are historical measurements, not proof for these exact 1.0.2 candidates. A macOS arm64 ONNX crash is recorded; the current candidate has not been run successfully on macOS arm64. Linux inference and native SOM on platforms other than the scoped Windows Fabric server remain unverified. This does not establish that those platforms are unsupported. See `runtime/MODEL.md` for historical model measurements.

## For players

**Before you install — please read:**

- The JAR itself is large (about 450 MB) because it bundles a fallback model.
- On first use the MOD downloads about **3.1 GB** of hash-pinned files (the JevK5 model and the llama.cpp runtime) into the game directory. A one-time internet connection is required for that fetch; play works offline afterwards. To use only the bundled fallback and skip the download entirely, launch with `-Dlocalinferenceapi.som.native=false`.
- Current 1.0.2 runtime acceptance covers the scoped Windows Forge ONNX and Windows Fabric ONNX/native server checks above. Linux and current macOS arm64 operation remain unverified for these exact candidates. A fallback is implemented, but it does not guarantee successful inference on an unverified platform.
- On a dedicated or rented server, everything runs on the machine hosting Minecraft. The native JevK5 path needs roughly 3.1 GB of outbound downloads and permission to start a worker process. If downloads are blocked, the bundled CPU fallback can answer without them. Both paths need permission to start a local worker process; a host that blocks that cannot run inference.

Install the JAR matching your Minecraft loader in `mods/`. Minecraft and the loader must already be installed. Fabric API is included in the Fabric JAR.

No Python installation, manual model setup, account, API key, or separately managed inference server is needed. The Forge 1.20.1 candidate's Java 17 host additionally needs a Java 21+ executable for the bundled worker, selected with `-Dlocalinferenceapi.worker.java=<absolute path to java>`. If the native download or launch fails, the bundled CPU worker answers instead when its Java runtime is available. Disconnecting or stopping the server closes the inference process. Gameplay features are provided by mods that depend on this library.

## For mod developers

The MOD ID is **`localinferenceapi`**. The Java entry point is **`com.kuronami.localinferenceapi.api.LocalInference`**.

The lightweight compile-only API artifact avoids downloading the bundled model during compilation. The developer kit contains both the Java 21 API and the Java 17-compatible Forge API. Point your consumer build at the kit's local `maven/` directory:

```groovy
repositories {
    maven { url = uri(providers.gradleProperty('localInferenceRepository').get()) }
}
dependencies {
    compileOnly 'com.kuronami.localinferenceapi:local-inference-api:1.0.2'
}
```

Pass `-PlocalInferenceRepository=/absolute/path/to/extracted-developer-kit/maven` when building the example. When building this source tree, `build/developer-repository` is an alternative after publishing the API artifacts locally. These repositories have not been published online. The API JAR is only for compilation and must not be installed or bundled as a replacement for the full MOD.

For Forge 1.20.1, use `com.kuronami.localinferenceapi:local-inference-api-forge17:1.0.2` in `compileOnly` instead. The public nine-class API is the same; the Forge artifact uses Java 17 class files.

DeveloperKit 1.0.2-r6 is the documentation-corrected developer delivery for this version; the release page records its publication state. Its ZIP is 137105 bytes, SHA-256 `1686be85a4f0c8d8a13760cbad552e6306e5d47c7e6af93b155109081c5bace0`. The original r5 ZIP is preserved (136,569 bytes; SHA-256 `c6f15f01907ad07db72e82e1ed4249120551c8ec21d5b9a2ee180516410d84ab`). It contains both compile-only API artifacts and the example. Match the kit to the exact full MOD candidate; the release notes list the exact distribution checksums and verification scope. r6 applies the current acceptance clarification while preserving every non-README r5 entry byte; the r5 archive retains its historical documentation. A future kit can be built with `./gradlew developerKit`.

**Migration from 0.1.0:** Choice still uses `DecisionRequest` and `DecisionResult`, but `selected()` never returns `null` for a valid request and `probabilities()` / `logits()` have exactly one entry per supplied choice. The old final abstention entry is gone. Consumers that index that entry or rely on abstention must update before using 1.0.0. Keep 0.1.0 pinned until your addon has been adapted and tested.

See [the independent example mod](examples/request-classifier/README.md) for Fabric, NeoForge, and Forge integration.

Install the full MOD JAR matching your loader and Minecraft version in your test game's `mods/` directory at runtime. Declare it as a required dependency in your consumer mod:

Inside `fabric.mod.json`’s existing `depends` object:

```json
"localinferenceapi": ">=1.0.0"
```

```toml
# Add to META-INF/neoforge.mods.toml; replace yourmod with your MOD ID.
[[dependencies.yourmod]]
modId = "localinferenceapi"
type = "required"
versionRange = "[1.0.0,)"
ordering = "AFTER"
side = "BOTH"
```

The loader owns initialization, worker sharing and shutdown. The public entry points are `decide`, `score` and `noul`; consumer mods do not start or stop the shared runtime. Normal world disconnection releases it, and a later explicit request from a menu or a new world starts it lazily again.

```java
import com.kuronami.localinferenceapi.api.DecisionRequest;
import com.kuronami.localinferenceapi.api.LocalInference;
import java.util.List;

// A small classification example used in the runtime smoke test.
var request = new DecisionRequest(
    "I cannot remember my login password. Please reset it.",
    "What does this person need?",
    List.of("Reporting a lost bank card", "Resetting a password", "Requesting a loan")
);

LocalInference.decide(request).whenComplete((result, failure) -> {
    if (failure != null) {
        // Keep your deterministic fallback behavior.
        return;
    }
    String selectedChoice = request.choices().get(result.selected());
    // Pass the result to your game's thread before accessing world state.
    // Recheck that the request is still relevant to the current world/entity.
});
```

### Contract

- Input is an immutable snapshot of strings: 1–24 distinct choices, 2–10 ordered Score levels, or a Noul proposition. Empty, oversized, reserved-token, and malformed Unicode inputs are rejected. When the prompt exceeds the active backend's context budget it is deterministically shortened to fit rather than rejected; such responses carry `"truncated": true` so the shortening is observable.
- `selected()` is a zero-based choice index. Score returns the weighted mean of your ordered level values; Noul returns a model-assigned probability for the statement. The model has no independent abstention option. These probabilities are not verified real-world frequencies and should not authorize gameplay actions by themselves.
- One request runs at a time; at most 16 more can wait. Full queues, startup failures, timeouts, and shutdown complete futures exceptionally. Invalid constructor arguments throw immediately.
- Startup timeout is 180 seconds per attempt; each inference timeout is 60 seconds. If the bundled worker process exits before its first ready frame, startup is retried once. Malformed responses and failures after readiness are not retried in a loop. A new server session or client connection can initialize a fresh worker.
- Result delivery is separated from the inference worker so a consumer's synchronous callback cannot block inference for other mods. Completion callbacks are not guaranteed to run on the game thread. Keep synchronous callbacks short; use `thenAcceptAsync` for other work. Schedule world changes through the appropriate server/client executor. This API does not synchronize game actions between clients and servers.
- Cancelling a returned future releases its router capacity immediately and propagates to any queued backend future. A request already running in a native process may finish internally, but its answer is not delivered to the caller.
- After the last request finishes, the worker unloads after five minutes without another request. A later request starts it again. World disconnection and server stop close it sooner; an active request is never unloaded for idleness.
- Cache and worker logs live in `.localinferenceapi/` under the game directory: `runtime/` for the bundled ONNX worker JVM and `som/` for the staged llama.cpp runtime and model. Both the dedicated worker JVM and the external `llama-server` isolate native inference libraries from other mods and from the Minecraft JVM.

## Building

1. Use JDK 21 and run `python3 runtime/tools/fetch_model.py` to fetch the pinned build-time model files. Players do not download them separately.
2. Run `./gradlew build --no-build-cache`.
3. A normal build writes loader JARs under `fabric/build/libs/`, `neoforge/build/libs/` and `forge/build/libs/`. The current preserved 1.0.2 candidates use `fabric/build/libs-gpt-r1/`, `neoforge/build/libs-c13/` and `forge/build/libs-c15/`; do not substitute an older standard-path JAR. Do not install `-sources` or `-javadoc` JARs.

The three preserved 1.0.2 candidates passed the recursive distribution archive check for `.exe` names and PE executables, including nested JARs. This addresses the executable-file issue stated in the earlier CurseForge rejection notice; it does not constitute a moderation decision or publication approval. Windows DLLs remain bundled as runtime libraries and are not classified as `.exe` files by this check.

The common unit tests cover queue limits, worker reuse, shutdown, timeouts, protocol validation, and cache repair. `python3 runtime/tools/smoke.py runtime/build/libs/runtime.jar` exercises the bundled fallback model; on macOS it also denies network access with the OS sandbox. The native-path smoke test (`LlamaNativeSmokeTest`) launches the pinned `llama-server` against the real pinned model and is gated on machine-local assets supplied through `SOM_TEST_LOCAL_MAP`; without them it is skipped.

Broader gameplay evaluation, native-path runs for the other loader/platform combinations, and Linux inference remain open. See runtime/MODEL.md for the bundled fallback's measurements and limits.

## License and upstream credits

This project is **not uniformly MIT**. The public API sources (`com.kuronami.localinferenceapi.api`) and the developer-kit example are **MIT licensed**; the internal implementation is **All Rights Reserved**, with explicit permission to use the mod in modpacks, redistribute the unmodified JAR, and build and distribute mods that depend on and call the API. The file-level scope and the full terms are in [LICENSE](LICENSE) and [LICENSING.md](LICENSING.md). Bundled models and dependencies retain their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md). The published 0.1.0 release stays under its original MIT grant — this change is not retroactive. The model was created by its upstream authors, not by KURONAMI333.

Pin the tested version. API or bundled-model changes can alter behavior, including scores and selected choices. A compatible Java signature does not guarantee identical inference results. Re-run your own task evaluations when upgrading.
