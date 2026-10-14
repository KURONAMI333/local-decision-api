# Local Decision API 1.0.3

This update changes how the fallback model's native libraries are delivered. The public `decide`, `score`, and `noul` API is unchanged from 1.0.2; mods built against 1.0.2 work without changes.

- The JARs no longer contain native libraries. In 1.0.2 each JAR carried the ONNX Runtime, tokenizer and JNA libraries for every platform (including Windows DLLs). Now the mod downloads only the libraries for your platform the first time a mod asks it for a decision: the official ONNX Runtime 1.30.0 and DJL tokenizers 0.38.0 JARs from Maven Central (about 73 MB), checked against fixed SHA-256 values, kept under `.localinferenceapi/natives/` in the game directory.
- **That first request needs an internet connection.** Later sessions use the saved copy and work offline. If the download or the check fails, the calling mod receives an "inference is unavailable" error and the log says why; the game does not crash. If the download stops receiving data for 60 seconds it gives up the same way. The mod tries again in the next session, and a saved file that no longer matches is downloaded and checked again.
- The JAR is about 60 MB smaller.

Native libraries are available for Windows x64, macOS on Apple silicon, and Linux x64/arm64, the same platforms 1.0.2 shipped libraries for. The native JevK5 route and its 3.1 GB first-use download are unchanged. The [README](https://github.com/KURONAMI333/local-decision-api#for-players) has setup, storage and platform details.
