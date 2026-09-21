# Bundled model (experimental)

The initial technical prototype uses the **first Verdict GLiClass ModernBERT 151M** checkpoint. It is not Verdict2 and is not the Jev service. Gameplay decision quality is not established; the prototype incorrectly selected walking into lava in one explicit hazard case. Consumers must not treat scores as safety guarantees.

Source: https://huggingface.co/heman10x/rlcd-modernbert-151m/tree/8af2496eb63c7fa66d7d234e1f62629380030eb4

Download `model.onnx`, `tokenizer.json`, and `calibrator.json` at that pinned revision into `runtime/models/` **when developing**. Players receive these files inside their MOD JAR. No runtime download is performed.

Model SHA-256: `4ae01f822538b000fa0e55859d4b3e6b40871d860149397e8784428b2a42ee5e`.

Upstream declares Apache-2.0. The graph runs on CPU using ONNX Runtime 1.30.0, with two intra-op threads and one inter-op thread. Python orchestration uses GraalPy 25.0.1. JNI artifacts in this build contain Windows x64, Linux x64/arm64 and macOS arm64; only macOS arm64 has been exercised so far. Intel Mac support is not claimed.

Inputs allow 1–24 substantive choices, with an additional abstention slot. Inputs above 512 tokens are rejected rather than silently truncated. Calibrated probabilities use the bundled calibration file and do not establish accuracy on Minecraft tasks.
