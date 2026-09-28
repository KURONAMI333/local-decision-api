---
license: apache-2.0
base_model: convaiinnovations/laya-typed-decisions
library_name: onnx
tags: [onnx, onnxruntime-web, browser, decision-model, quantized]
---
# laya-typed-decisions converted for the browser

This is a modified copy of the checkpoint at [convaiinnovations/laya-typed-decisions](https://huggingface.co/convaiinnovations/laya-typed-decisions)
(Apache-2.0, Copyright ConvAI Innovations), made for ONNX Runtime Web. It is unofficial and not affiliated with
ConvAI Innovations. This checkpoint is fine-tuned specifically for typed-decision workflows (see its own model card for which ones). Laya itself is built on ModernBERT-large by Answer.AI and LightOn
(Apache-2.0).

**Changes from the original:** exported to ONNX; weights quantized (weight-only int8 or int4, int8 embeddings), so
outputs differ slightly from the original PyTorch model; weight files split into parts (`*.onnx.data.partNNN`, listed
in `manifest.json`, which the demo page reassembles).

Licensed under the Apache License, Version 2.0. See `LICENSE` and `NOTICE.md` in this folder.
Build scripts and demo: the `layaForWeb` repository that produced this folder.
