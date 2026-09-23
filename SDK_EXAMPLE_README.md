# Local Inference API example

This independent Minecraft 1.21.1 mod shows how to call Local Inference API from Fabric or NeoForge. In a world, enter `/inferenceexample It is too dark. I need a torch.` The mod asks the API whether the text is about food, lighting or directions, then reports the model's answer in chat. It does not change the world.

## Build

Use JDK 21. From this `example/` directory, run:

```sh
./gradlew build -PlocalInferenceRepository=/absolute/path/to/developer-kit/maven
```

On Windows, use `gradlew.bat`. The Maven directory is supplied alongside this example in the developer kit. The small API JAR is a compile-only dependency; it is not a Minecraft mod. Install the matching **full Local Inference API MOD JAR** alongside the built example JAR to try the command.

The implementation in `common/` creates a `DecisionRequest`, calls `LocalInference.decide`, handles failure and abstention, switches back to the server thread, and checks that the original player and world still exist. `fabric/` and `neoforge/` provide the loader entry points and declare `localinferenceapi` as a required dependency.

The model can answer incorrectly. Do not treat its answer or score as permission to change the game world without your own checks.
