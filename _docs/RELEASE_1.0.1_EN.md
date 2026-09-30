# Local Decision API 1.0.1

This update improves reliability when several mods request decisions at once, when callers cancel a request, and when a worker starts or a player changes worlds. The public `decide`, `score`, and `noul` API contracts are unchanged from 1.0.0.

- A busy native worker now rejects only the request it cannot accept. It no longer disables a healthy worker for the other callers.
- Cancelling a returned future releases its router capacity and cancels a waiting backend request. A native HTTP request already in progress is interrupted where possible; no cancelled result is delivered to the caller.
- The bundled fallback retries once if its process exits before the first ready message. Later failures are reported to the caller without an unbounded retry loop.
- Worker idle time starts after startup. The client self-test starts after player login, and the worker can be reused after a world disconnect and a later request.

Install the Fabric or NeoForge JAR that matches Minecraft 1.21.1 and Java 21+. This is a library for other mods; it adds no gameplay by itself. Mod authors can use the attached developer kit for the compile-only API and runnable example. The primary JevK5 model and runtime download on first native use; each full JAR includes a fallback model. The [README](https://github.com/KURONAMI333/local-decision-api#for-mod-developers) has setup, licensing, storage and result-handling details.

The occasional macOS arm64 ONNX worker crash seen during testing has no confirmed upstream cause. The startup retry covers an exit before readiness, and other failures still complete the request exceptionally. Callers should keep their own fallback behavior.
