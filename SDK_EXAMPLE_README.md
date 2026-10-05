# Local Decision API example

This independent mod shows how to call Local Decision API from Minecraft 1.21.1 Fabric/NeoForge or Minecraft 1.20.1 Forge. In a world, enter `/inferenceexample It is too dark. I need a torch.` The mod asks the API whether the text is about food, lighting or directions, then reports the model's answer in chat. It does not change the world.

## Build

Use JDK 21 and make a JDK 17 toolchain available for the Forge cell. From this `example/` directory, run:

```sh
./gradlew build -PlocalInferenceRepository=/absolute/path/to/developer-kit/maven
```

On Windows, use `gradlew.bat`. The Maven directory is supplied alongside this example in the developer kit. The small API JAR is a compile-only dependency; it is not a Minecraft mod. Install the matching **full Local Decision API MOD JAR** alongside the built example JAR to try the command.

The implementation in `common/` creates a `DecisionRequest`, calls `LocalInference.decide`, handles failures, switches back to the server thread, and checks that the original player and world still exist. `fabric/`, `neoforge/`, and `forge/` provide loader entry points and declare `localinferenceapi` as a required dependency. The Forge example compiles against the bundled Java 17-compatible API artifact. Its host JVM still needs a separate Java 21+ worker launcher configured as described in the kit README.

The model can answer incorrectly. Do not treat its answer or score as permission to change the game world without your own checks.
