# Local Decision API

**Helps compatible mods choose between predefined options or rate a situation on the game PC or server. Adds no gameplay by itself.**

A library mod that lets other mods submit a context, a question, and a list of choices to a local model. Results arrive asynchronously. This library does not add gameplay by itself.

## Status

Local Decision API 1.0.0 targets Minecraft **1.21.1**, **Fabric and NeoForge**, **Java 21+**. The primary inference path runs **JevK5-4B v0.3 (Q5_K_M)** through a pinned **llama.cpp `llama-server`** process outside the Minecraft JVM; the MOD automatically stages the hash-pinned runtime and model into the game directory on first use. If the native worker is unavailable or still starting, the bundled **Laya Typed-Decisions** ONNX worker inside the same JAR serves requests as a degraded fallback. The earlier 0.1.0 release remains a Choice-only Verdict prototype. Model probabilities are not well-calibrated truth, and gameplay or language quality is not guaranteed.

The full native path — manifest verification, staged download with SHA-256 pins, archive extraction, `llama-server` launch on loopback with a per-launch API key, Choice/Score/Noul readout, restart, offline cache and idle unload — has been exercised end to end on **macOS Apple Silicon**, and on a **Windows x64 dedicated server** answering two connected clients. The bundled ONNX fallback has passed standalone Java worker tests and dedicated-server inference on macOS and Windows x64 for both loaders (see runtime/MODEL.md for those measurements). **Still unverified:** the native path inside the integrated (singleplayer) client on any OS, macOS x64, CPU-only Windows machines, and Linux — which has no native runtime at all, so the bundled fallback always serves there. The two loader JARs remain roughly 450 MB because the ONNX fallback is bundled; the native model is not inside the JAR.

## For players

**Before you install — please read:**

- The JAR itself is large (about 450 MB) because it bundles a fallback model.
- On first use the MOD downloads about **3.1 GB** of hash-pinned files (the JevK5 model and the llama.cpp runtime) into the game directory. A one-time internet connection is required for that fetch; play works offline afterwards. To use only the bundled fallback and skip the download entirely, launch with `-Dlocalinferenceapi.som.native=false`.
- Platform coverage is uneven. The native path has been run end to end on Apple Silicon Macs and on a Windows x64 dedicated server. On **Linux** there is no native runtime at all and the bundled CPU fallback always serves requests. The native path is unverified in the **integrated (singleplayer) client**, on macOS x64, and on Windows machines without a compatible GPU; where it cannot start, the bundled fallback answers instead.
- On a dedicated or rented server, everything runs on the machine hosting Minecraft — the download, the model files, and the worker process. The host must allow roughly 3.1 GB of outbound downloads and a spawned worker process; a shared host that blocks either leaves the library unable to answer.

Install the JAR matching your Minecraft loader in `mods/`. Minecraft and the loader must already be installed. Fabric API is included in the Fabric JAR.

No Python installation, manual model setup, account, API key, or separately managed inference server is needed. If the download or launch fails, the bundled CPU worker answers instead. Disconnecting or stopping the server closes the inference process. Gameplay features are provided by mods that depend on this library.

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

The 1.0.0 developer kit can be built with `./gradlew developerKit`. The [published 0.1.0 kit](https://github.com/KURONAMI333/local-inference-api/releases/tag/v0.1.0) is for the old API and model.

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
