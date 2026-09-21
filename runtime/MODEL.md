# Bundled model (experimental)

The runtime bundles a **dynamic INT8 per-channel derivative of the first Verdict GLiClass ModernBERT 151M** checkpoint, published by heman10x. It is not Verdict2 or the Jev service. General gameplay decision quality is not established. The model still selected walking into lava and opening a chest against the owner's instruction in diagnostic cases. Consumers must evaluate their own use case and must not treat scores as safety guarantees.

## Source and license

Upstream: [heman10x/rlcd-modernbert-151m](https://huggingface.co/heman10x/rlcd-modernbert-151m/tree/8af2496eb63c7fa66d7d234e1f62629380030eb4), pinned revision `8af2496eb63c7fa66d7d234e1f62629380030eb4`.

The publisher declares Apache-2.0 and credits `knowledgator/gliclass-modern-base-v2.0` as the base model. The associated [Verdict source repository](https://github.com/Heman10x-NGU/Verdict-open-jev) provides a shortened license file preserved verbatim as `licenses/VERDICT-LICENSE`. The full standard Apache-2.0 text from apache.org is included separately as `licenses/APACHE-2.0.txt`. Both are preserved in the runtime JAR under `third-party/model/`. The model retains its upstream license; Local Inference API's MIT license does not replace it.

**Modification notice:** Local Inference API quantizes constant MatMul/Gemm weights using ONNX Runtime 1.30.0 dynamic QInt8, per-channel, `MatMulConstBOnly=True`. Tokenizer, calibration and input/output contract are unchanged. No further training was performed. Not every graph operation or weight becomes INT8.

## Reproducing the bundled graph

These are developer-only steps. Players receive all files in their MOD JAR; no runtime download is performed.

1. Download upstream `model.onnx` at the pinned revision to a separate source directory. Download `tokenizer.json` and `calibrator.json` into `runtime/models/`.
2. In a Python development environment install `onnx==1.23.0` and `onnxruntime==1.30.0`.
3. From the project root run `python runtime/tools/quantize_model.py /path/to/upstream/model.onnx runtime/models/model.onnx`.
4. Build with JDK 21 using `./gradlew :runtime:shadowJar --no-build-cache`. The build checks all three bundled file hashes.
5. Run `python runtime/tools/smoke.py runtime/build/libs/runtime.jar` with `JAVA_HOME` pointing to JDK 21. It uses a new home and JAR extraction directory, checks 40 regression requests against the pinned Python reference, checks input rejection and shutdown, and denies network access on macOS.

The quantization script validates the source and result hashes and runs the ONNX checker. It deliberately uses the original graph directly, without optional preprocessing, to reproduce the tested graph.

- Upstream float32 SHA-256: `4ae01f822538b000fa0e55859d4b3e6b40871d860149397e8784428b2a42ee5e`
- Bundled INT8 SHA-256: `4b4c4bdb608bbbcb719daf3f7301bae50bc020f046f8d1620f9a4f7471f5baf9`

## Scope of verification

On macOS arm64, the 606,323,181-byte graph became 269,468,405 bytes. A Python CPU comparison with two intra-op threads found identical selections on 40 handcrafted requests: existing gameplay diagnostics 20/24, existing classification 4/4, and new request classification 9/12. Each scenario appears with normal and reversed choice order, so these are not 40 independent scenarios. Japanese requests include abstentions and a pre-existing choice-order inconsistency. This is regression evidence, not a general accuracy benchmark. Per-tensor quantization was rejected after one new error.

The graph runs on CPU using ONNX Runtime 1.30.0, two intra-op threads and one inter-op thread. Python orchestration uses GraalPy 25.0.1. JNI artifacts contain Windows x64, Linux x64/arm64 and macOS arm64; platform support must be established by distribution testing, not by the presence of native files. Intel Mac support is not claimed.

Inputs allow 1–24 substantive choices, with an additional abstention slot. Inputs above 512 tokens are rejected rather than silently truncated. Calibrated probabilities use the upstream calibration file; calibration on Minecraft tasks, particularly after quantization, has not been established.
