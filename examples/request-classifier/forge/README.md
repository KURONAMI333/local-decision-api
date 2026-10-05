# Local Decision Example — Forge 1.20.1

Consumer example for the Local Decision API on Minecraft 1.20.1 + Forge 47.x,
compiled with a Java 17 toolchain (class-file major 61).

## Artifacts

- API implementation (required at runtime): `localinferenceapi-forge-1.20.1-1.0.2.jar`
  — build it in the parent repository with `:forge:build`; the API classes for
  compile time are also published locally as
  `com.kuronami.localinferenceapi:local-inference-api-forge17:1.0.2` from
  `../../build/developer-repository` (Java 17 bytecode, API surface identical
  to the `local-inference-api:1.0.2` coordinate).
- This example: `inferenceexample-forge-1.20.1-1.0.2.jar` (this module's `build/libs*`).

## Install

1. Drop both jars into the server's `mods/` directory (Forge 47.x, Minecraft 1.20.1).
2. Run the game/server on Java 17 or newer. The inference worker JVM itself
   requires Java 21 or newer — on a Java 17 host, point the API at a Java 21+
   launcher with `-Dlocalinferenceapi.worker.java=<absolute path to java>`.

## What the example does

- `/inferenceexample <text>` — classifies a free-text request through
  `LocalInference.decide(...)` (server thread, async result delivered back to
  the game thread via `MinecraftServer.execute`).
- On server start with `-Dinferenceexample.smoke=true`, `DeveloperSmoke` issues
  two requests, waits on `CompletableFuture.allOf`, and reports the results —
  including the failure path — on the game thread.
- World session begin/end hooks exercise `LocalInference` session lifecycle;
  stale leases are rejected after session end.

## Honest status

Compile, metadata expansion, and jar packaging are verified locally. The
compiled jar has not been run on a live Forge 1.20.1 instance — FML load and
inference lifecycle on this artifact are unverified.
