# -*- coding: utf-8 -*-
"""modelengine:bulk_data against the vanilla packets, on models whose heads follow a turning mob.

  python tests/bulk_test.py bm|me [case ...]

bm: DivineMC + BetterModel 3.6.0, build/bm/headtest.bbmodel (a Charizard whose head group is "hi_head").
me: DivineMC + the GCB-patched ModelEngine R4.1.1, the player.bbmodel of SelfCharacterBattle (hi_phead_head ...).
Cases (default all): off, on, defer10 (applied later as the MEG client mod does, 10 fps), off10, on10, gcb (with the GCB mod).
Per case: drawn rotation change per frame and display (-Dremoterenderer.traceRotation), received bytes and frames,
bulk entries, and a screenshot of the still pose afterwards (build/shots/bulk-<plugin>-<case>.png).
"""
import os, re, shutil, subprocess, sys, time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GCB = ROOT.parent
GCB_MOD = Path(os.environ.get('RR_GCB_MOD', str(GCB.parent / 'ATLauncher/instances/Danmo263/mods/gcbmod-4.2.0.jar')))

CASES = {
    'off': dict(bulk=False),
    'on': dict(bulk=True),
    'defer10': dict(bulk=True, defer=True, fps=10),
    'off10': dict(bulk=False, fps=10),
    'on10': dict(bulk=True, fps=10),
    'gcb': dict(bulk=True, gcb=True),
}


def steps_for(plugin):
    if plugin == 'bm':
        spawn = ['dmprobe spawn headtest 0 -60 12 8 3.5 180', '@2', 'dmprobe anim ' + os.environ.get('RR_BULK_ANIM', 'ground_run')]
    else:
        spawn = ['meprobe spawn player 0 -60 8 8 1.6 180']
    s = spawn + ['view:6:-57.5:0:0:15', '@12', 'mark:rot_start']
    # 12 s of turning: 9 degrees a tick, the pitch swinging between -30 and 30 (the body follows the head with vanilla's lag)
    for i in range(240 if os.environ.get('RR_BULK_TURN', '1') == '1' else 0):
        pitch = [-30, -15, 0, 15, 30, 15, 0, -15][(i // 10) % 8]
        s += [f'execute as @e[tag=probe] at @s run tp @s ~ ~ ~ ~9 {pitch}', '@0.05']
    if os.environ.get('RR_BULK_TURN', '1') != '1':
        s += ['@12']
    s += ['mark:rot_end', 'execute as @e[tag=probe] at @s run tp @s ~ ~ ~ 200 10', '@4']
    return s


def run(plugin, case):
    c = CASES[case]
    label = f'{plugin}-{case}'
    jvm = ['-Dremoterenderer.traceRotation=true', f'-Dremoterenderer.bulkData={str(c["bulk"]).lower()}']
    if c.get('defer'):
        jvm.append('-Dremoterenderer.bulkDefer=true')
    env = dict(os.environ, RR_STATS='1', RR_JVM=' '.join(jvm), RR_STATS_TAIL='0')
    env['RR_MAXFPS'] = str(c.get('fps', 260))
    if c.get('gcb'):
        env['RR_EXTRA_MODS'] = str(GCB_MOD)
    steps = steps_for(plugin) + [f'shot:bulk-{label}']
    t0 = time.time()
    r = subprocess.run([sys.executable, str(ROOT / 'tests/run_client.py'), '--bm' if plugin == 'bm' else '--me'] + steps,
                       capture_output=True, text=True, encoding='utf-8', errors='replace', env=env)
    (ROOT / f'build/bulk-{label}.out').write_text(r.stdout, encoding='utf-8')
    log = ROOT / f'build/bulk-{label}.log'
    shutil.copy(ROOT / 'build/client.log', log)
    marks = {m.group(1): int(m.group(2)) / 1000.0 for m in re.finditer(r'MARK (\S+) (\d+)', r.stdout)}
    if 'joined True' not in r.stdout or 'rot_start' not in marks:
        print(r.stdout[-2500:])
        raise SystemExit(f'{label}: client did not join or the run broke')
    return parse(log.read_text(encoding='utf-8', errors='replace'), marks), time.time() - t0


def tod(epoch):
    d = datetime.fromtimestamp(epoch)
    return d.hour * 3600 + d.minute * 60 + d.second + d.microsecond / 1e6


def parse(text, marks):
    start, end = tod(marks['rot_start']), tod(marks['rot_end'])
    rows = []
    for m in re.finditer(r'^\[(\d\d):(\d\d):(\d\d)\].*RR_STATS (.*)$', text, re.M):
        t = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
        kv = dict(x.split('=', 1) for x in m.group(4).split() if '=' in x)
        rows.append((t, kv))
    inside = [kv for t, kv in rows if start + 1.0 <= t <= end + 0.5]
    before = [kv for t, kv in rows if t <= start + 1.0]
    last_in = inside[-1] if inside else {}
    first = before[-1] if before else (inside[0] if inside else {})
    secs = max(1.0, end - start)
    f = lambda kv, k: float(kv.get(k, 0) or 0)
    steps = sum(f(kv, 'rotSteps') for kv in inside)
    out = {
        'fps': sum(f(kv, 'fps') for kv in inside) / max(1, len(inside)),
        'rotMean': sum(f(kv, 'rotMeanDeg') * f(kv, 'rotSteps') for kv in inside) / max(1.0, steps),
        'rotMax': max([f(kv, 'rotMaxDeg') for kv in inside] or [0]),
        'over10': sum(f(kv, 'rotOver10') for kv in inside) / max(1.0, steps) * 100,
        'over30': sum(f(kv, 'rotOver30') for kv in inside) / max(1.0, steps) * 100,
        'over90': sum(f(kv, 'rotOver90') for kv in inside),
        'degps': sum(f(kv, 'rotMeanDeg') * f(kv, 'fps') for kv in inside) / max(1, len(inside)),
        # the stats windows are one second long (RR_STATS=1)
        'revps': sum(f(kv, 'rotReversals') for kv in inside) / max(1, len(inside)),
        'kbps': (f(last_in, 'netKB') - f(first, 'netKB')) / secs,
        'framesps': (f(last_in, 'netFrames') - f(first, 'netFrames')) / secs,
        'bulkps': (f(last_in, 'bulkEntries') - f(first, 'bulkEntries')) / secs,
        'missing': f(last_in, 'bulkMissing'),
        'instances': f(last_in, 'instances'),
    }
    return out


def main():
    plugin = sys.argv[1]
    cases = sys.argv[2:] or list(CASES)
    results = {}
    for case in cases:
        res, took = run(plugin, case)
        results[case] = res
        print(f'{plugin}-{case} ({took:.0f}s): ' + ' '.join(f'{k}={v:.2f}' for k, v in res.items()), flush=True)
    print()
    print('| case | fps | turn deg/s per display (all displays) | reversals/s | max step deg | >30 deg % | received KB/s | frames/s | bulk entries/s |')
    print('|---|---:|---:|---:|---:|---:|---:|---:|---:|')
    for case, r in results.items():
        print(f"| {case} | {r['fps']:.0f} | {r['degps']:.0f} | {r['revps']:.0f} | {r['rotMax']:.1f} | {r['over30']:.3f} | {r['kbps']:.1f} | {r['framesps']:.0f} | {r['bulkps']:.0f} |")


if __name__ == '__main__':
    main()
