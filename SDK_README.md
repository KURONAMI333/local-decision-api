# Local Inference API developer kit

Minecraft 1.21.1 • Fabric / NeoForge • Java 21

This kit contains a small compile-only Maven repository under `maven/` and an independent example under `example/`. It does not contain the model or the full runtime MOD. Install the matching full Local Inference API MOD JAR in your test game's mods directory.

## Build the example

With JDK 21, enter `example/` and run:

```sh
./gradlew build -PlocalInferenceRepository=/absolute/path/to/this-kit/maven
```

On Windows, use `gradlew.bat` and an absolute path to `maven`. The normal example artifacts are under `fabric/build/libs/` and `neoforge/build/libs/`. Install the matching example JAR alongside the full library MOD, then use `/inferenceexample It is too dark. I need a torch.`

The `fixture-*` projects are developer verification tools, not normal player downloads. Do not add failure-test flags to a player's installation.

## Integrate your own MOD

```groovy
repositories {
    maven { url = uri('/absolute/path/to/this-kit/maven') }
}
dependencies {
    compileOnly 'com.kuronami.localinferenceapi:local-inference-api:0.1.0'
}
```

Use `com.kuronami.localinferenceapi.api.LocalInference.decide(DecisionRequest)`. Declare MOD ID `localinferenceapi` as a required runtime dependency; the loader-specific metadata and callback example are in `example/README.md` and the source tree. Never bundle the compile-only API classes into your own MOD or install this API JAR instead of the full MOD.

The public API takes immutable text snapshots and returns a CompletableFuture. Schedule changes back onto the game thread and confirm that the target player/world still exists. Handle failure and abstention. The bundled model makes mistakes and is not an authorization or safety system.

The library manages a shared runtime. Calls must not initialize or shut it down. Pin a tested 0.x release; API compatibility does not guarantee unchanged model decisions. The model remains resident after use until disconnection, server stop or application exit; no automatic idle unloading is provided in this release.

## License

Our API and example code are MIT licensed. See LICENSE and THIRD_PARTY.md. Models and runtime dependencies in the full MOD retain their own licenses. Nothing in this developer kit publishes a remote Maven repository or changes third-party terms.
