# -*- coding: utf-8 -*-
"""First-appearance stall test: 24 heavy mesh bones of 6 different models (each 6912 sub-models) appear in the same
tick. Compares the worst frame in the seconds after that (and the steady frame time) between the vanilla renderer,
the baseline RemoteRenderer build (build/baseline/remoterenderer-baseline.jar) and the current build.

  python tests/latency_test.py [repeats]
"""
import os, re, shutil, statistics, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPEATS = int(sys.argv[1]) if len(sys.argv) > 1 else 2
VARIANTS = [('vanilla', ['--off'], None), ('baseline', [], ROOT / 'build/baseline/remoterenderer-baseline.jar'),
            ('new', [], sorted((ROOT / 'build').glob('remoterenderer-*.jar'))[-1])]
STEPS = ['view:0:-56.5:-4:0:30', '@6', 'mark:burst', 'scene:burst', '@8', 'mark:steady', '@6', 'mark:end']


def run(flags, jar):
    """Frames over 4 ms (RR_SLOW, wall clock) in the 3 s after the burst and in the 6 s steady window; no chat markers."""
    env = dict(os.environ, RR_STATS='1', RR_JVM='-Dremoterenderer.slowFrameMs=4')
    if jar:
        env['RR_MOD_JAR'] = str(jar)
    r = subprocess.run([sys.executable, str(ROOT / 'tests/run_client.py')] + flags + STEPS, capture_output=True, text=True, env=env)
    marks = dict((m.group(1), int(m.group(2))) for m in re.finditer(r'MARK (\w+) (\d+)', r.stdout))
    text = (ROOT / 'build/client.log').read_text(encoding='utf-8', errors='replace')
    slow = [(int(m.group(1)), float(m.group(2))) for m in re.finditer(r'RR_SLOW t=(\d+) frame=([\d.]+)ms', text)]
    if 'burst' not in marks or 'steady' not in marks:
        return None, None
    burst = [ms for t, ms in slow if marks['burst'] <= t <= marks['burst'] + 3000]
    steady = [ms for t, ms in slow if marks['steady'] <= t <= marks['end']]
    return max(burst, default=4.0), max(steady, default=4.0)


only = os.environ.get('RR_ONLY')
rows = []
for name, flags, jar in VARIANTS:
    if only and name not in only.split(','):
        continue
    worst, steadys = [], []
    for _ in range(REPEATS):
        b, st = run(flags, jar)
        if b is None:
            print(name, 'no data')
            continue
        worst.append(b)
        steadys.append(st)
    rows.append((name, worst, steadys))
    print(name, 'worst frame in the 3 s after the burst (ms):', worst, 'worst frame while steady (ms):', steadys, flush=True)

lines = ['| version | worst frame, 3 s after the burst (ms) | worst frame while steady (ms) |', '|---|---:|---:|']
for name, worst, steadys in rows:
    if worst:
        lines.append('| %s | %s | %s |' % (name, ' / '.join('%.1f' % w for w in worst), ' / '.join('%.1f' % w for w in steadys)))
(ROOT / 'build/latency.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print('\n'.join(lines))
