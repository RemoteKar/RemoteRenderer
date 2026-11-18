# -*- coding: utf-8 -*-
"""A/B benchmark: the same scenes with RemoteRenderer off (vanilla renderer) and on, in one client run each.
Writes build/bench.md and prints it.

  python tests/bench.py [--danmo] [--vulkan] [seconds per scene]
"""
import os, re, shutil, statistics, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
extra = [a for a in sys.argv[1:] if a.startswith('--')]
nums = [a for a in sys.argv[1:] if not a.startswith('--')]
SECONDS = float(nums[0]) if nums else 20.0

SCENES = [
    # name, scene spec, view, description
    ('mesh36', 'grid:6:torus:spin', '0:-54:-5:0:32', 'BetterModel 메쉬 본 36개 (토러스, 각 6912 서브모델 = 삼각형 2304개), 회전'),
    ('bones25', 'bones:5:spin', '0:-53:-6:0:30', 'BetterModel 큐브 본 모델 25세트 (눈설왕 본 전체 x25 = 디스플레이 약 2500개), 회전'),
    ('objmc100', 'grid:10:objmc_twist:spin', '0:-52:-8:0:30', 'objmc 애니메이션 모델 100개 (128면, 8프레임), 회전'),
]


def run(mode):
    steps = []
    for name, spec, view, _ in SCENES:
        steps += ['scene:clear', 'scene:' + spec, 'view:' + view, '@6', f'say BENCH {name} start', f'@{SECONDS}', f'say BENCH {name} end', f'shot:bench_{mode}_{name}']
    args = [sys.executable, str(ROOT / 'tests/run_client.py')] + extra + (['--off'] if mode == 'off' else []) + steps
    env = dict(os.environ, RR_STATS='1')
    r = subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace', env=env)
    log = ROOT / f'build/client-bench-{mode}.log'
    shutil.copy(ROOT / 'build/client.log', log)
    if 'joined True' not in r.stdout:
        print(r.stdout[-3000:])
        raise SystemExit('client did not join')
    return log.read_text(encoding='utf-8', errors='replace')


def parse(text):
    """mean fps / frame ms / max ms of the RR_STATS lines between "BENCH <name> start" and "end" (first 2 lines skipped)."""
    out, current, rows = {}, None, []
    for line in text.splitlines():
        m = re.search(r'BENCH (\w+) (start|end)', line)
        if m:
            if m.group(2) == 'start':
                current, rows = m.group(1), []
            elif current:
                out[current] = rows[2:] if len(rows) > 4 else rows
                current = None
            continue
        if current and 'RR_STATS' in line:
            d = dict(re.findall(r'(\w+)=([\d.a-z]+)', line[line.index('RR_STATS'):]))
            rows.append(d)
    return out


def summary(rows):
    if not rows:
        return None
    fps = statistics.mean(float(r['fps']) for r in rows)
    ms = statistics.mean(float(r['frameMs']) for r in rows)
    mx = max(float(r['maxMs']) for r in rows)
    inst = rows[-1].get('instances', '0')
    return fps, ms, mx, inst


def main():
    results = {mode: parse(run(mode)) for mode in ('off', 'on')}
    lines = ['# RemoteRenderer A/B 벤치마크', '',
             f'- 조건: 1280x720, 렌더거리 8, vsync 끔, {"Vulkan" if "--vulkan" in extra else "OpenGL"}'
             + (', Danmo263 렌더 모드(Sodium/Iris(셰이더팩 없음)/EntityCulling/MoreCulling) 포함' if '--danmo' in extra else ', Fabric API만'),
             f'- 장면마다 {SECONDS:.0f}초, 1초 단위 측정의 평균 (처음 2초 제외)', '',
             '| 장면 | 바닐라 FPS | 바닐라 프레임(ms) | RemoteRenderer FPS | RemoteRenderer 프레임(ms) | 배율 | 최악 프레임 바닐라 / DM (ms) |',
             '|---|---:|---:|---:|---:|---:|---:|']
    for name, _, _, desc in SCENES:
        a, b = summary(results['off'].get(name, [])), summary(results['on'].get(name, []))
        if not a or not b:
            lines.append(f'| {desc} | ? | ? | ? | ? | ? | ? |')
            continue
        lines.append(f'| {desc} | {a[0]:.0f} | {a[1]:.2f} | {b[0]:.0f} | {b[1]:.2f} | x{a[1] / b[1]:.1f} | {a[2]:.1f} / {b[2]:.1f} |')
    text = '\n'.join(lines) + '\n'
    (ROOT / 'build/bench.md').write_text(text, encoding='utf-8')
    print(text)


if __name__ == '__main__':
    main()
