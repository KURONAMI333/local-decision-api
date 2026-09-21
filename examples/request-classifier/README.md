# Local Inference Example

A separate Minecraft 1.21.1 mod showing how to consume Local Inference API from Fabric or NeoForge. `/inferenceexample <text>` classifies a request as **food**, **lighting**, or **directions** and replies in chat. It never changes blocks, inventories, or entity behavior.

This is an independent Gradle build. It compiles against the small Maven API artifact, not the library's source tree or its model bundle. The model's answer can be wrong or abstain. English requests are the initial evaluation target; Japanese interface messages are included, but Japanese classification quality is not established.

## Build

Use JDK 21. Until a public Maven repository exists, publish the API from the library root first:

```sh
./gradlew :common:publishApiPublicationToDeveloperRepository
```

Then, from this example directory:

```sh
./gradlew build -PlocalInferenceRepository=/absolute/path/to/local-inference-api/build/developer-repository
```

`localInferenceRepository` accepts a local path or URI. If omitted, the example uses `../../build/developer-repository` relative to this example root. The dependency coordinate is `com.kuronami.localinferenceapi:local-inference-api:0.1.0`; it is compile-only and is not bundled into the example.

The loader JARs appear under `fabric/build/libs/` and `neoforge/build/libs/`. Install the example JAR and the matching **full Local Inference API MOD JAR** together. The small Maven API artifact is not a runnable MOD. Both loader manifests declare `localinferenceapi` as required.

## Try it

With the two matching MOD JARs installed, open a world and run:

```text
/inferenceexample I am hungry. Can I have some bread?
/inferenceexample It is too dark. I need a torch.
/inferenceexample I am lost. Which way is the village?
```

The response is asynchronous. The example copies only text into the request and dispatches its callback through `server.execute`. Before replying, it checks the server session, world instance, and original player instance. Disconnects, dimension changes, and server shutdown invalidate old replies. Failure and abstention have explicit messages.

## Developer smoke

To load a built library JAR into this independent development environment, pass `-PlocalInferenceFabricRuntime=/absolute/path/to/the/fabric-library.jar` for Fabric, or `-PlocalInferenceNeoForgeRuntime=/absolute/path/to/the/neoforge-library.jar` for NeoForge. Fabric uses `modLocalRuntime`; NeoForge uses `localRuntime`. These properties are optional at build time but a runtime library must be installed to launch.

`-PexampleSmoke` enables a normally disabled probe after a real server/world starts. It submits two independent requests at once and logs:

- `INFERENCE_EXAMPLE_SMOKE_PASS`: both real inference futures completed and the delivery ran on the server thread in the original world. The selected indices are diagnostic output, not a quality guarantee.
- `INFERENCE_EXAMPLE_WORLD_STOPPED_PASS`: the real world-stop lifecycle invalidated the captured session lease, so stale results cannot pass that gate.

For a dedicated development server, add both properties to `:fabric:runServer` or `:neoforge:runServer`. Follow Minecraft's server EULA workflow for your environment. For a development client, add them to the selected loader's client launch, then create or open a world. Client launch is managed by the enclosing development workspace's normal run-client procedure.

These probes do not simulate a player's chat display, dimension travel, or disconnect/reconnect. Verify those separately when modifying the callback path. The example intentionally never calls the library's `initialize` or `close`: those belong to the library lifecycle.

## License

MIT. The template notice is preserved in `LICENSE`, alongside the example implementation copyright. You may use and adapt this example in your own MOD under those terms. The bundled model and third-party runtime components belong to the library distribution and have their own notices.

## Separate consumer and failure fixture

`fixture-fabric` and `fixture-neoforge` build a **second MOD**, ID `inferencesharedfixture`, with separate entry points and its own direct compile-only Maven API dependency. Install the matching `inference-shared-fixture-<loader>-1.21.1-0.1.0.jar` alongside the example and the library only in an isolated development server. The fixture contains no copy of the first consumer or library implementation.

Both consumers run only with `-Dinferenceexample.smoke=true`. Their request time intervals must overlap. The fixture verifies that both consumer MODs complete requests, server ticks advance, and exactly one matching child worker exists. Success logs include `INFERENCE_CONCURRENT_REQUESTS_PASS` and `INFERENCE_SHARED_WORKER_PASS`.

An additional explicit `-Dinferenceexample.failureSmoke=true` enables a destructive **test of the worker process**. After both consumers succeed, a background test thread checks the process is a direct child of this server, uses the same Java executable, and has exactly the library worker arguments pointing to this server directory's `.localinferenceapi/runtime/runtime-<SHA256>.jar`. Only that checked worker is terminated. No process is terminated if these checks are unavailable or fail. Windows may use a read-only PowerShell CIM query of that exact child PID when the Java process API does not expose its arguments.

The fixture then requires a new API request to fail and at least five more server ticks to occur. The final marker is `INFERENCE_SHARED_FAILURE_PASS`. This leaves inference intentionally unavailable until the next server session. Stop and restart the isolated test server afterward. The fixture never runs this failure injection merely because it is installed, and it is not a gameplay MOD or a player-facing distribution.

The process checks are tied to this prototype's worker launch format; changes to that format must update and re-review the test. This proves shared use and game-loop survival after an isolated worker failure, not behavior of a connected player's GUI or reconnect flow.
