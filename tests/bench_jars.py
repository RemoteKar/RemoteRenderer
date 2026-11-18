# -*- coding: utf-8 -*-
"""Two RemoteRenderer jars on the same scenes (both on), one client run each: did a change make it faster?

  python tests/bench_jars.py <old.jar> <new.jar> [--danmo] [--vulkan] [seconds per scene]

RR_SHADERPACK works as in run_client.py (shader-pack mode). Prints a table.
"""
import os, re, shutil, subprocess, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
_argv, sys.argv = sys.argv, sys.argv[:1]  # bench reads its own arguments on import
import bench  # noqa: E402
sys.argv = _argv

ROOT = Path(__file__).resolve().parents[1]


def run(jar, label, extra, seconds):
    steps = ['time set 6000']
    for name, spec, view, _ in bench.SCENES:
        steps += ['scene:clear', 'scene:' + spec, 'view:' + view, '@6', f'say BENCH {name} start', f'@{seconds}', f'say BENCH {name} end']
    args = [sys.executable, str(ROOT / 'tests/run_client.py')] + extra + steps
    env = dict(os.environ, RR_STATS='1', RR_MOD_JAR=str(jar))
    r = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', env=env)
    if 'joined True' not in r.stdout:
        print(r.stdout[-3000:])
        raise SystemExit('client did not join')
    log = ROOT / f'build/client-benchjar-{label}.log'
    shutil.copy(ROOT / 'build/client.log', log)
    return bench.parse(log.read_text(encoding='utf-8', errors='replace'))


def main():
    jars = [Path(a) for a in sys.argv[1:] if a.endswith('.jar')]
    extra = [a for a in sys.argv[1:] if a.startswith('--')]
    nums = [a for a in sys.argv[1:] if not a.startswith('--') and not a.endswith('.jar')]
    seconds = float(nums[0]) if nums else 15.0
    old, new = run(jars[0], 'old', extra, seconds), run(jars[1], 'new', extra, seconds)
    print('| scene | old FPS | old ms | new FPS | new ms | old/new ms |')
    print('|---|---:|---:|---:|---:|---:|')
    for name, _, _, desc in bench.SCENES:
        a, b = bench.summary(old.get(name, [])), bench.summary(new.get(name, []))
        if a and b:
            print(f'| {name} | {a[0]:.0f} | {a[1]:.2f} | {b[0]:.0f} | {b[1]:.2f} | x{a[1] / b[1]:.2f} |')


if __name__ == '__main__':
    main()
