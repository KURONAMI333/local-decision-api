"""Exercise the shipped Java worker with a clean cache and no network access."""
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import time

jar = Path(sys.argv[1]).resolve()
java = os.path.join(os.environ['JAVA_HOME'], 'bin', 'java') if os.environ.get('JAVA_HOME') else 'java'

with tempfile.TemporaryDirectory(prefix='local-inference-clean-') as home:
    clean_jar = Path(home) / 'runtime.jar'
    shutil.copyfile(jar, clean_jar)
    command = (["/usr/bin/sandbox-exec", "-p", "(version 1)(allow default)(deny network*)"]
               if sys.platform == 'darwin' else [])
    command += (["/usr/bin/time", "-l"] if sys.platform == 'darwin' else [])
    command += [java, '-Xmx2G', '-Duser.home=' + home, '-jar', str(clean_jar), '--worker']
    env = {'PATH': os.environ.get('PATH', ''), 'HOME': home, 'DJL_OFFLINE': 'true'}
    start = time.monotonic()
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, env=env, cwd=home)

    def read():
        header = process.stdout.read(4)
        if len(header) != 4:
            raise RuntimeError(process.stderr.read().decode())
        length = struct.unpack('>I', header)[0]
        assert 0 < length <= 1_048_576
        return json.loads(process.stdout.read(length))

    def call(request):
        data = json.dumps(request, ensure_ascii=False).encode()
        process.stdin.write(struct.pack('>I', len(data)) + data)
        process.stdin.flush()
        return read()

    assert read() == {'ready': True}
    print('ready_seconds', round(time.monotonic() - start, 3), flush=True)
    cases = [
        ({'context': 'Player is in a dark cave and has torches.',
          'question': 'What should the companion do?',
          'choices': ['torch: place torches along the path', 'wait: stay nearby']},
         'choice'),
        ({'kind': 'score', 'context': '検索語: 洞窟を明るくするもの。候補アイテム: たいまつ。説明: 持ち運べる光源。',
          'question': '検索語に対して候補はどれくらい役立つか？',
          'levels': [{'description': '関連しない', 'value': 0},
                     {'description': '少し関連する', 'value': 1},
                     {'description': '直接役立つ', 'value': 2}]}, 'score'),
        ({'kind': 'noul',
          'context': 'Player search query: something to light a cave. Candidate item: Torch. Description: a portable light source.',
          'proposition': 'Is the candidate item useful for the search intent?'}, 'noul'),
    ]
    results = []
    for request, name in cases:
        response = call(request)
        assert 'error' not in response, (name, response)
        results.append(response)
        print(name, response, flush=True)
    # Reference logits from the pinned Q8E8 graph with official Laya 0.3.11 sequence construction.
    expected = [1.489257574081421, -0.176335871219635]
    assert all(abs(a - b) < 1e-4 for a, b in zip(results[0]['logits'], expected))
    assert results[0]['selected'] == 0 and len(results[0]['probabilities']) == 2
    assert results[1]['selectedLevel'] == 2 and len(results[1]['probabilities']) == 3
    assert abs(results[1]['score'] - 1.381697) < 1e-3
    assert abs(results[2]['trueProbability'] - 0.687237) < 1e-3
    assert call({'context': 'Any state', 'question': 'Only option?', 'choices': ['only']}) == {
        'selected': 0, 'probabilities': [1.0], 'logits': [0.0]}
    assert 'error' in call({'context': '', 'question': 'What?', 'choices': ['A', 'B']})
    # Over-budget inputs shorten like the reference SDK instead of failing; the
    # worker flags the response so the shortening is never silent.
    crowded = call({'context': 'Any state', 'question': 'Choose',
                    'choices': ['word ' * 100, 'short']})
    assert 'error' not in crowded and crowded.get('truncated') is True
    assert len(crowded['probabilities']) == 2
    assert 'error' in call({'context': 'Any state', 'question': 'Choose',
                            'choices': [str(i) for i in range(25)]})
    assert call(cases[0][0]) == results[0]
    process.stdin.close()
    assert process.wait(timeout=15) == 0
    print('eof_shutdown_ok', flush=True)
    print(process.stderr.read().decode(), file=sys.stderr)
