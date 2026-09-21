# Third-party components and attribution

Local Inference API's original Java/Python code, public API, examples and documentation are licensed under MIT. The root LICENSE applies to that work, not to separately licensed models or dependencies. Bundling a component does not transfer its authorship to KURONAMI333.

## Model

The prototype bundles **heman10x's first Verdict / OpenJev GLiClass ModernBERT 151M checkpoint**, from [heman10x/rlcd-modernbert-151m](https://huggingface.co/heman10x/rlcd-modernbert-151m), revision `8af2496eb63c7fa66d7d234e1f62629380030eb4`. It is not Verdict2 or a connection to the Jev service.

The publisher declares Apache-2.0. Its model card credits the GLiClass ModernBERT base checkpoint (`knowledgator/gliclass-modern-base-v2.0`) and inspiration from TypeSafe AI's Jev and RLCD. These credits describe upstream work, not an affiliation or endorsement of this Minecraft mod.

See [runtime/MODEL.md](runtime/MODEL.md) for pinned files, modifications and reproducible build instructions. The runtime JAR preserves the upstream model license and includes the full Apache-2.0 text. Model weights are bundled in the distribution JAR; they are not covered by this project's MIT license.

## Execution libraries

The runtime uses GraalPy Community 25.0.1, ONNX Runtime 1.30.0, DJL Hugging Face tokenizers 0.38.0 and their dependencies. The Fabric distribution also embeds Fabric API modules. Each retains its own license. The runtime build preserves dependency LICENSE, NOTICE and COPYING files under `third-party/<dependency-jar>/`; nested Fabric API JARs retain their own notices.

PyFish is an architectural reference for Python embedding only, not a prerequisite or bundled component. Its source code has not been copied into this project.
