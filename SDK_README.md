# Local Decision API developer kit

Minecraft 1.21.1 • Fabric / NeoForge • Java 21

This kit contains a small compile-only Maven repository under `maven/` and an independent example under `example/`. It does not contain the model or the full runtime MOD. Install the matching full Local Decision API MOD JAR in your test game's mods directory.

## Build the example

With JDK 21, enter `example/` and run:

```sh
./gradlew build -PlocalInferenceRepository=/absolute/path/to/this-kit/maven
```

On Windows, use `gradlew.bat` and an absolute path to `maven`. The normal example artifacts are under `fabric/build/libs/` and `neoforge/build/libs/`. Install the matching example JAR alongside the full library MOD, then use `/inferenceexample It is too dark. I need a torch.`

The kit contains only the example MOD; internal verification fixtures are not part of this download.

## Integrate your own MOD

```groovy
repositories {
    maven { url = uri('/absolute/path/to/this-kit/maven') }
}
dependencies {
    compileOnly 'com.kuronami.localinferenceapi:local-inference-api:1.0.1'
}
```

Use `com.kuronami.localinferenceapi.api.LocalInference` for `decide`, `score` and `noul`. Declare MOD ID `localinferenceapi` as a required runtime dependency; the loader-specific metadata and callback example are in `example/README.md` and the source tree. Never bundle the compile-only API classes into your own MOD or install this API JAR instead of the full MOD.

The public API takes immutable text snapshots and returns a CompletableFuture. Schedule changes back onto the game thread and confirm that the target player/world still exists. Handle failure. The model always returns a choice for a valid request; it has no independent abstention result. The model makes mistakes and is not an authorization or safety system.

The library manages a shared runtime. Calls must not initialize or shut it down. Pin a tested release; API compatibility does not guarantee unchanged model decisions. The worker unloads five minutes after the last request and restarts on demand; disconnection or server stop closes it sooner.

## License

The public API sources and the example code in this kit are MIT licensed (LICENSE Part 1). The full MOD's internal implementation is All Rights Reserved; declaring a normal dependency on it, calling the API, and redistributing the unmodified MOD JAR are expressly allowed (LICENSE Part 2). LICENSING.md states the file-level scope. Models and runtime dependencies in the full MOD retain their own licenses; see THIRD_PARTY.md. Nothing in this developer kit publishes a remote Maven repository or changes third-party terms.
