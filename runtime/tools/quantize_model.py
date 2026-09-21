"""Developer-only deterministic quantization of the pinned upstream model.
Requires onnx==1.23.0 and onnxruntime==1.30.0.
"""
import argparse
import hashlib
from pathlib import Path
import onnx
import onnxruntime
from onnxruntime.quantization import QuantType, quantize_dynamic
SOURCE = '4ae01f822538b000fa0e55859d4b3e6b40871d860149397e8784428b2a42ee5e'
RESULT = '4b4c4bdb608bbbcb719daf3f7301bae50bc020f046f8d1620f9a4f7471f5baf9'
def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            h.update(block)
    return h.hexdigest()
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if onnx.__version__ != '1.23.0' or onnxruntime.__version__ != '1.30.0':
        raise RuntimeError('Use onnx==1.23.0 and onnxruntime==1.30.0 for reproducibility')
    if args.source.resolve() == args.output.resolve():
        raise ValueError('Keep the upstream source separate from the output')
    if sha(args.source) != SOURCE:
        raise ValueError('Upstream model checksum mismatch')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    quantize_dynamic(str(args.source), str(args.output), weight_type=QuantType.QInt8,
                     per_channel=True, op_types_to_quantize=['MatMul', 'Gemm'],
                     extra_options={'MatMulConstBOnly': True})
    onnx.checker.check_model(str(args.output))
    if sha(args.output) != RESULT:
        raise RuntimeError('Quantized checksum differs; do not ship without investigating')
    print('Verified quantized model:', RESULT)
if __name__ == '__main__':
    main()
