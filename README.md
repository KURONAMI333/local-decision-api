# Local Inference API

**Embedded AI inference for Minecraft mods. No external setup required.**

A library mod that lets other mods submit a context, a question, and a list of choices to a bundled local model. Results arrive asynchronously. This library does not add gameplay by itself.

## Status

Unpublished initial library build for Minecraft **1.21.1**, **Fabric and NeoForge**, **Java 21+**. The current model is an INT8 per-channel derivative of the first Verdict 151M checkpoint; it is a technical test model, not an approved general-purpose game decision maker. It has produced incorrect action choices. Evaluate it on your own task before relying on its output.

macOS Apple Silicon and Windows x64 have been exercised. Windows checks used production Fabric and NeoForge dedicated servers, an empty inference cache, no Python on PATH, Japanese/space-containing paths and a network-blocked test JVM. These checks do not cover every graphical launcher. Linux x64/arm64 native libraries are bundled but untested. Intel Macs are not supported by the current bundle. The INT8 distribution JAR is approximately 392–394 MB. On macOS arm64, the standalone worker used approximately 850 MB peak RSS; this is not total Minecraft memory usage.

## For players

Install the JAR matching your Minecraft loader in `mods/`. Minecraft and the loader must already be installed. Fabric API is included in the Fabric JAR.

No Python installation, model download, account, API key, or separately managed inference server is needed. The model and runtime are inside the JAR. The first request starts a local worker automatically; disconnecting or stopping the server closes it. Gameplay features are provided by mods that depend on this library.

## For mod developers

The MOD ID is **`localinferenceapi`**. The Java entry point is **`com.kuronami.localinferenceapi.api.LocalInference`**.

The lightweight compile-only API artifact avoids downloading the bundled model during compilation. Build a local Maven repository with `./gradlew :common:publishApiPublicationToDeveloperRepository`, then point your consumer build at its absolute path:

```groovy
repositories {
    maven { url = uri(providers.gradleProperty('localInferenceRepository').get()) }
}
dependencies {
    compileOnly 'com.kuronami.localinferenceapi:local-inference-api:0.1.0'
}
```

Pass `-PlocalInferenceRepository=/absolute/path/to/local-inference-api/build/developer-repository`. This repository has not been published online. The API JAR is only for compilation and must not be installed or bundled as a replacement for the full MOD.

A self-contained developer ZIP can be created with `./gradlew developerKit`; it includes this Maven artifact and independent example sources.

See [the independent example mod](examples/request-classifier/README.md) for Fabric and NeoForge integration.

Install the matching full Fabric or NeoForge MOD JAR in your test game's `mods/` directory at runtime. Declare it as a required dependency in your consumer mod:

Inside `fabric.mod.json`’s existing `depends` object:

```json
"localinferenceapi": ">=0.1.0"
```

```toml
# Add to META-INF/neoforge.mods.toml; replace yourmod with your MOD ID.
[[dependencies.yourmod]]
modId = "localinferenceapi"
type = "required"
versionRange = "[0.1.0,)"
ordering = "AFTER"
side = "BOTH"
```

The loader owns initialization, worker sharing and shutdown. The public entry point is `decide`; consumer mods do not start or stop the shared runtime. Normal world disconnection releases it, and a later explicit request from a menu or a new world starts it lazily again.

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
    if (result.selected() == null) {
        // The model abstained.
        return;
    }
    String selectedChoice = request.choices().get(result.selected());
    // Pass the result to your game's thread before accessing world state.
    // Recheck that the request is still relevant to the current world/entity.
});
```

### Contract

- Input is an immutable snapshot of strings: 1–24 distinct choices. Empty, oversized, reserved-token, and malformed Unicode inputs are rejected. The runtime also rejects inputs above 512 tokens instead of truncating them.
- `selected()` is a zero-based choice index, or `null` for abstention. Both score lists contain one score per choice **plus an abstention score at the end**. Probabilities are model scores, not guarantees of correctness.
- One request runs at a time; at most 16 more can wait. Full queues, startup failures, timeouts, and shutdown complete futures exceptionally. Invalid constructor arguments throw immediately.
- Startup timeout is 180 seconds; each inference timeout is 60 seconds. A failed worker is not automatically restarted in a loop. A new server session or client connection can initialize a fresh worker.
- Result delivery is separated from the inference worker so a consumer's synchronous callback cannot block inference for other mods. Completion callbacks are not guaranteed to run on the game thread. Keep synchronous callbacks short; use `thenAcceptAsync` for other work. Schedule world changes through the appropriate server/client executor. This API does not synchronize game actions between clients and servers.
- Cancelling a returned future cancels delivery, not an in-flight native inference. A cancelled queued request is skipped when its turn arrives and occupies its queue slot until then.
- After a request, the model remains resident until normal world disconnection, server stop or application exit. A one-shot request does not currently trigger automatic idle unloading.
- Cache and worker logs live in `.localinferenceapi/runtime/` under the game directory. The dedicated worker JVM isolates its Python and native inference libraries from other mods.

## Building

1. Use JDK 21 and prepare the pinned model files as described in [runtime/MODEL.md](runtime/MODEL.md). This is a developer-only step; players do not download them separately.
2. Run `./gradlew build --no-build-cache`.
3. Find loader JARs under `fabric/build/libs/` and `neoforge/build/libs/`. Do not install `-sources` or `-javadoc` JARs.

The common unit tests cover queue limits, worker reuse, shutdown, timeouts, protocol validation, and cache repair. `python3 runtime/tools/smoke.py runtime/build/libs/runtime.jar` exercises the real model; on macOS it also denies network access with the OS sandbox.

This build has not been published. The pinned model has passed a small quantization regression suite, but broader gameplay evaluation remains open. See runtime/MODEL.md for known errors and verification limits.

## License and upstream credits

Our implementation, API and examples are **MIT licensed**: other developers may use, modify and redistribute them while preserving the license notice. Bundled models and dependencies retain their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md). The model was created by its upstream authors, not by KURONAMI333.

During the 0.x prototype series, pin the tested version. API or bundled-model changes can alter behavior, including scores and selected choices. A compatible Java signature does not guarantee identical inference results. Re-run your own task evaluations when upgrading.
