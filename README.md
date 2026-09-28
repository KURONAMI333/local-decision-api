# Local Decision API

Local Decision API helps Minecraft mods choose between options, rate a situation, or estimate the likelihood of a statement. A calling mod supplies the game context and defines the possible answers; it decides how to use the result.

## Model and operation

[System One Models (SOMs)](https://typesafe.ai/blog/introducing-system-one-models-and-jev) focus on structured decisions: a defined question produces a choice, score, or probability. This API uses that style of question. The primary model in 1.0.0 is [JevK5-4B v0.3](https://github.com/allebee/jevk5), an open-weight model built on Qwen3.5. Its [model card](https://huggingface.co/alibiserikbay/JevK5) describes the weights and training. The mod also bundles a fallback model; details and upstream credits are in [THIRD_PARTY.md](THIRD_PARTY.md).

The mod downloads about **3.1 GB** of model and runtime files on first use and keeps them in the game directory. The Fabric and NeoForge JARs are each about **450 MB** because they include the fallback. A rented server needs enough storage, permission for the download, and permission to start a worker process. Model results are estimates; consumer mods should decide how to handle unsuitable or uncertain answers.

Install this mod when another mod requires it. Mod authors can use the [1.0.0 developer kit](https://github.com/KURONAMI333/local-decision-api/releases/tag/v1.0.0), which includes a compile-only API artifact and a runnable example. The sections below cover integration, result handling, limits, and migration from 0.1.0.

## For mod developers

The MOD ID is **`localinferenceapi`**. The Java entry point is **`com.kuronami.localinferenceapi.api.LocalInference`**.

The lightweight compile-only API artifact avoids downloading the bundled model during compilation. Build a local Maven repository with `./gradlew :common:publishApiPublicationToDeveloperRepository`, then point your consumer build at its absolute path:

```groovy
repositories {
    maven { url = uri(providers.gradleProperty('localInferenceRepository').get()) }
}
dependencies {
    compileOnly 'com.kuronami.localinferenceapi:local-inference-api:1.0.0'
}
```

Pass `-PlocalInferenceRepository=/absolute/path/to/local-inference-api/build/developer-repository`. The API artifact is not published to a public Maven repository — it ships inside the developer kit. The API JAR is only for compilation and must not be installed or bundled as a replacement for the full MOD.

The 1.0.0 developer kit can be built with `./gradlew developerKit`. The [published 0.1.0 kit](https://github.com/KURONAMI333/local-decision-api/releases/tag/v0.1.0) is for the old API and model.

**Migration from 0.1.0:** Choice still uses `DecisionRequest` and `DecisionResult`, but `selected()` never returns `null` for a valid request and `probabilities()` / `logits()` have exactly one entry per supplied choice. The old final abstention entry is gone. Consumers that index that entry or rely on abstention must update before using 1.0.0. Keep 0.1.0 pinned until your addon has been adapted and tested.

See [the independent example mod](examples/request-classifier/README.md) for Fabric and NeoForge integration.

Install the matching full Fabric or NeoForge MOD JAR in your test game's `mods/` directory at runtime. Declare it as a required dependency in your consumer mod:

Inside `fabric.mod.json`’s existing `depends` object:

```json
"localinferenceapi": "=1.0.0"
```

```toml
# Add to META-INF/neoforge.mods.toml; replace yourmod with your MOD ID.
[[dependencies.yourmod]]
modId = "localinferenceapi"
type = "required"
versionRange = "[1.0.0]"
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
- Startup timeout is 180 seconds; each inference timeout is 60 seconds. A failed worker is not automatically restarted in a loop. A new server session or client connection can initialize a fresh worker.
- Result delivery is separated from the inference worker so a consumer's synchronous callback cannot block inference for other mods. Completion callbacks are not guaranteed to run on the game thread. Keep synchronous callbacks short; use `thenAcceptAsync` for other work. Schedule world changes through the appropriate server/client executor. This API does not synchronize game actions between clients and servers.
- Cancelling a returned future cancels delivery, not an in-flight native inference. A cancelled queued request is skipped when its turn arrives and occupies its queue slot until then.
- After the last request finishes, the worker unloads after five minutes without another request. A later request starts it again. World disconnection and server stop close it sooner; an active request is never unloaded for idleness.
- Cache and worker logs live in `.localinferenceapi/` under the game directory: `runtime/` for the bundled ONNX worker JVM and `som/` for the staged llama.cpp runtime and model. Both the dedicated worker JVM and the external `llama-server` isolate native inference libraries from other mods and from the Minecraft JVM.

## Building

1. Use JDK 21 and run `python3 runtime/tools/fetch_model.py` to fetch the pinned build-time model files. Players do not download them separately.
2. Run `./gradlew build --no-build-cache`.
3. Find loader JARs under `fabric/build/libs/` and `neoforge/build/libs/`. Do not install `-sources` or `-javadoc` JARs.

The common unit tests cover queue limits, worker reuse, shutdown, timeouts, protocol validation, and cache repair. `python3 runtime/tools/smoke.py runtime/build/libs/runtime.jar` exercises the bundled fallback model; on macOS it also denies network access with the OS sandbox. The native-path smoke test (`LlamaNativeSmokeTest`) launches the pinned `llama-server` against the real pinned model and is gated on machine-local assets supplied through `SOM_TEST_LOCAL_MAP`; without them it is skipped.

Broader gameplay evaluation and native-path runs on the integrated client, macOS x64, and CPU-only Windows remain open; Linux ships no native runtime and always uses the bundled fallback. See runtime/MODEL.md for the bundled fallback's measurements and limits.

## License and upstream credits

This project is **not uniformly MIT**. The public API sources (`com.kuronami.localinferenceapi.api`) and the developer-kit example are **MIT licensed**; the internal implementation is **All Rights Reserved**, with explicit permission to use the mod in modpacks, redistribute the unmodified JAR, and build and distribute mods that depend on and call the API. The file-level scope and the full terms are in [LICENSE](LICENSE) and [LICENSING.md](LICENSING.md). Bundled models and dependencies retain their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md). The published 0.1.0 release stays under its original MIT grant — this change is not retroactive. The model was created by its upstream authors, not by KURONAMI333.

During the 0.x prototype series, pin the tested version. API or bundled-model changes can alter behavior, including scores and selected choices. A compatible Java signature does not guarantee identical inference results. Re-run your own task evaluations when upgrading.
