# -*- coding: utf-8 -*-
"""Ragdolls and BetterModel models of the TopViewDemo server (a copy, build/tvserver) through RemoteRenderer.

Three client runs: "auto" (RemoteRenderer default: it should step aside because TopView's pack replaces the item shader),
"forced" (-Dremoterenderer.ignoreCustomItemShaders=true: RemoteRenderer draws the ragdoll limbs / creatures itself),
"off" (vanilla renderer). Frozen corpses are compared pixel by pixel; falling corpses and an animated player-model
doll are captured for a look.

  python tests/ragdoll_test.py [auto forced off]
"""
import os, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
P = 'tv test DmTester '
STEPS = [P + 'go match', '@8', P + 'ragdoll force on', P + 'ragdoll freeze on',
         P + 'corpse bot 1 2.5 0', P + 'corpse gabe 1 -2.5 0', P + 'corpse minion 1 0 2.5', P + 'corpse fiend 1 2.5 2.5',
         P + 'corpse husk 1 -2.5 2.5', P + 'corpse pillager 1 0 -2.5', P + 'corpse strong 1 2.5 -2.5',
         '@8', 'shot:rag_{m}_frozen', P + 'ragdoll',
         P + 'ragdoll freeze off', 'tick rate 4', P + 'corpse bot 3 4 4', P + 'corpse minion 3 -4 4',
         '@0.4', 'shot:rag_{m}_fall1', '@0.6', 'shot:rag_{m}_fall2', '@0.8', 'shot:rag_{m}_fall3', 'tick rate 20',
         P + 'doll attack01 -4 -4', '@3', 'shot:rag_{m}_doll1', '@0.5', 'shot:rag_{m}_doll2', P + 'ragdoll', '@1']

modes = sys.argv[1:] or ['auto', 'forced', 'off']
for m in modes:
    flags = ['--tv']
    env = dict(os.environ)
    if m == 'off':
        flags.append('--off')
    if m == 'forced':
        env['RR_JVM'] = '-Dremoterenderer.ignoreCustomItemShaders=true'
    r = subprocess.run([sys.executable, str(ROOT / 'tests/run_client.py')] + flags + [s.replace('{m}', m) for s in STEPS],
                       capture_output=True, text=True, encoding='utf-8', errors='replace', env=env)
    shutil.copy(ROOT / 'build/client.log', ROOT / f'build/client-rag-{m}.log')
    shutil.copy(ROOT / 'build/server.log', ROOT / f'build/server-rag-{m}.log')
    print('==', m, 'joined True' in r.stdout)
    for line in r.stdout.splitlines():
        if any(k in line for k in ('shot ', 'pack status', 'steps aside', 'takes over', 'Exception', 'ERROR')) and 'No key layers' not in line:
            print('  ', line[:200])
    stats = [l for l in (ROOT / 'build/client.log').read_text(encoding='utf-8', errors='replace').splitlines() if 'RR_STATS' in l]
    for l in stats[-3:]:
        print('  ', l[l.index('RR_STATS'):][:240])
