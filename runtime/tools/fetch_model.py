"""Download the pinned build-time Laya Q8E8 files; players never run this script."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / 'models'
Q8 = 'https://huggingface.co/VishalMysore/layaForWebTrained/resolve/dd0c52a2b563bea25279e2689689061dd0a1c382/'
BASE = 'https://huggingface.co/convaiinnovations/laya/resolve/1c5edc17a7acd8701df6fc341c0d179f1c62c982/typed-decisions/'
FILES = {
    'laya_q8e8.onnx': (Q8 + 'laya_q8e8.onnx', '599756d6506db9659279f4ac7871045801f90539844fbda6cd2918b6316e2d07'),
    'laya-tokenizer.json': (BASE + 'tokenizer/tokenizer.json', '6c8aaa9a542084f2457eab775d4eeb51f92a70c0fd9de28d5edb0ddec3c08d30'),
    'laya-config.json': (BASE + 'rl_agent_config.json', 'ebf0cd524d92342a6be5e48e9fca3d7c2babfb5a56ccd79d2171ef5d8c7f7be8'),
}
DATA_HASH = 'e5ac4bfe0503361dacac825a91a82ae860e3a5d38dfb021dcf0bd37369573b44'


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def copy_url(url, output):
    request = urllib.request.Request(url, headers={'User-Agent': 'local-inference-api-build/0.2'})
    with urllib.request.urlopen(request, timeout=120) as source, output.open('ab') as target:
        while block := source.read(1024 * 1024):
            target.write(block)


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in FILES.items():
        target = ROOT / name
        if target.exists() and sha(target) == expected:
            print('verified', name)
            continue
        partial = ROOT / (name + '.part')
        partial.unlink(missing_ok=True)
        copy_url(url, partial)
        if sha(partial) != expected:
            raise RuntimeError('Hash mismatch: ' + name)
        partial.replace(target)
        print('downloaded', name)
    manifest_url = Q8 + 'manifest.json'
    with urllib.request.urlopen(urllib.request.Request(manifest_url, headers={'User-Agent': 'local-inference-api-build/0.2'}), timeout=30) as response:
        manifest = json.load(response)
    data = manifest['variants']['q8e8']['data']
    if data['sha256'] != DATA_HASH or data['name'] != 'laya_q8e8.onnx.data':
        raise RuntimeError('Pinned external data manifest changed')
    target = ROOT / data['name']
    if target.exists() and sha(target) == DATA_HASH:
        print('verified', target.name)
        return
    partial = ROOT / (data['name'] + '.download')
    partial.unlink(missing_ok=True)
    for name in data['parts']:
        print('downloading', name, flush=True)
        copy_url(Q8 + name, partial)
    if partial.stat().st_size != data['size'] or sha(partial) != DATA_HASH:
        raise RuntimeError('Reconstructed data mismatch')
    partial.replace(target)
    print('downloaded', target.name)


if __name__ == '__main__':
    main()
