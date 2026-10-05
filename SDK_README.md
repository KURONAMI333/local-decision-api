# Local Decision API developer kit

Minecraft 1.21.1 • Fabric / NeoForge • Java 21; Minecraft 1.20.1 • Forge • Java 17 host with a Java 21+ worker

This kit contains a small compile-only Maven repository under `maven/` and an independent example under `example/`. It does not contain the model or the full runtime MOD. Install the matching full Local Decision API MOD JAR in your test game's mods directory.

## Current delivery and verification status

DeveloperKit 1.0.2-r6 is a private documentation-corrected candidate, not a verified public release or whole-API acceptance. It preserves every API JAR, source JAR, example, script and Maven metadata/checksum byte from r5; only this README changes. The original r5 ZIP (136,569 bytes; SHA-256 `c6f15f01907ad07db72e82e1ed4249120551c8ec21d5b9a2ee180516410d84ab`) remains preserved. Both API-only JARs have nine public API classes, matching all current target JAR public signatures; Forge uses Java 17 bytecode, Fabric/NeoForge Java 21. ABI equality is not loader or inference acceptance.

The exact Forge candidate passed a scoped Windows dedicated-server ONNX/SDK lifecycle check with a Java 17 host and Java 21 worker. The exact Fabric 1.21.1 candidate passed Windows dedicated-server loader initialization, ONNX fallback and an actual native SOM decision after startup, followed by normal world save and host stop. This does not establish client, NeoForge, Linux or current macOS arm64 runtime acceptance. Earlier macOS tests belong to historical artifacts; a macOS arm64 ONNX crash is recorded. Those unverified platforms are not thereby proven unsupported. Companion's integration diagnosis is separate. See the repository README and private delivery handoff for exact candidate paths and acceptance limits. The already packaged r6 ZIP retains its earlier README; this source-document update does not change its bytes.

## Build the example

With JDK 21 and a JDK 17 toolchain available, enter `example/` and run:

```sh
./gradlew build -PlocalInferenceRepository=/absolute/path/to/this-kit/maven
```

On Windows, use `gradlew.bat` and an absolute path to `maven`. The example artifacts are under `fabric/build/libs/`, `neoforge/build/libs/`, and `forge/build/libs/`. Install the example JAR matching your loader and Minecraft version alongside the corresponding full library MOD, then use `/inferenceexample It is too dark. I need a torch.`

The kit contains the independent example MOD and its diagnostic example classes (`DeveloperSmoke` and `MpProbe`); it contains no full API runtime MOD or model.

## Integrate your own MOD

```groovy
repositories {
    maven { url = uri('/absolute/path/to/this-kit/maven') }
}
dependencies {
    compileOnly 'com.kuronami.localinferenceapi:local-inference-api:1.0.2'
}
```

For Forge 1.20.1 on Java 17, use `local-inference-api-forge17:1.0.2` instead. Both coordinates provide the same public API; the Forge artifact has Java 17-compatible class files. The kit bundles both artifacts under `maven/`.

Use `com.kuronami.localinferenceapi.api.LocalInference` for `decide`, `score` and `noul`. Declare MOD ID `localinferenceapi` as a required runtime dependency; the loader-specific metadata and callback example are in `example/README.md` and the source tree. Never bundle the compile-only API classes into your own MOD or install this API JAR instead of the full MOD.

The public API takes immutable text snapshots and returns a CompletableFuture. Schedule changes back onto the game thread and confirm that the target player/world still exists. Handle failure. The model always returns a choice for a valid request; it has no independent abstention result. The model makes mistakes and is not an authorization or safety system.

The library manages a shared runtime. Calls must not initialize or shut it down. Pin a tested release; API compatibility does not guarantee unchanged model decisions. The worker unloads five minutes after the last request and restarts on demand; disconnection or server stop closes it sooner.

### Runtime notes

- Inference runs in a separate worker JVM started as `java -Xmx2G -jar <extracted runtime>.jar --worker` under `<game dir>/.localinferenceapi/runtime/`; worker stderr is appended to `worker-<server pid>.log` in the same directory.
- `-Dlocalinferenceapi.som.native=false` disables the optional native route and pins the bundled model path; the default (`true`) tries the native route first and falls back to the bundled path on failure.
- A server stop or client disconnect ends the current inference session and closes its worker. Requests already in flight may fail or be cancelled. A later explicit request can start a new worker lazily while the MOD remains initialized; `shutdown()` is different and rejects requests until the next `initialize()`.
- Cancelling a returned `CompletableFuture` prevents a queued CPU request from being dispatched or prevents its result from being accepted. If CPU inference is already running, cancellation does not guarantee that the worker's current model computation or frame read stops. The native path attempts to interrupt its task, but prompt physical termination is not guaranteed. Consumers must still reject results for an expired world, player, or request generation. A consumer STOP command must cancel its own request, not shut down the shared API runtime.
- On a host JVM older than 21 (for example a Forge 1.20.1 server on Java 17), point the API at a Java 21+ launcher with `-Dlocalinferenceapi.worker.java=<absolute path to java>`; the worker itself always requires Java 21 or newer.

### Building this repository

- Toolchains: `neoforge`/`fabric`/`common`/`runtime` compile with a Java 21 toolchain; the `forge` cell targets Forge 1.20.1 and compiles with a Java 17 toolchain (class-file major 61). The bundled worker runtime stays Java 21+ regardless of the host game JVM.
- The `forge` module uses ForgeGradle's canonical `official` mapping channel and standard plugin repositories. An offline build still requires the exact dependencies already cached; mapping selection alone does not prove offline closure.
- To keep previously accepted jars untouched while producing new candidates, redirect archive output to a per-run directory (for example an init script that sets `Jar.destinationDirectory` to `build/libs-cN` for `neoforge` and `forge`); the fail-closed executable-content gate follows the actual jar output path.

## License

The public API sources and the example code in this kit are MIT licensed (LICENSE Part 1). The full MOD's internal implementation is All Rights Reserved; declaring a normal dependency on it, calling the API, and redistributing the unmodified MOD JAR are expressly allowed (LICENSE Part 2). LICENSING.md states the file-level scope. Models and runtime dependencies in the full MOD retain their own licenses; see THIRD_PARTY.md. Nothing in this developer kit publishes a remote Maven repository or changes third-party terms.

### Private delivery receipt

Current archive: `build/distributions/local-inference-api-developer-kit-1.0.2-r6.zip`, 137105 bytes, SHA-256 `1686be85a4f0c8d8a13760cbad552e6306e5d47c7e6af93b155109081c5bace0`. The archive README equals this source up to this receipt-only footer; the archive does not contain its own SHA. r5 remains byte-preserved. Runtime/publication gaps above remain open.
