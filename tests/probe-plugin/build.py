# -*- coding: utf-8 -*-
"""Builds build/DmProbe.jar (test-only Paper plugin) against the DivineMC copy in build/bmserver and BetterModel."""
import subprocess, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
GCB = ROOT.parent
JDK = GCB / 'Java/Java_25'
SERVER = ROOT / 'build/bmserver'
cp = [str(p) for p in list((SERVER / 'libraries').rglob('*.jar')) + list((SERVER / 'versions').rglob('*.jar'))]
cp.append(str(GCB / 'CobbleMon/plugins/BetterModel.jar'))
classes = ROOT / 'build/probe-classes'
classes.mkdir(parents=True, exist_ok=True)
args = ROOT / 'build/probe.args'
args.write_text('\n'.join(['-encoding', 'UTF-8', '--release', '25', '-nowarn', '-proc:none', '-cp', '"' + ';'.join(cp).replace('\\', '/') + '"',
                           '-d', '"' + str(classes).replace('\\', '/') + '"'] + ['"' + str(p).replace('\\', '/') + '"' for p in (HERE / 'src').rglob('*.java')]),
                encoding='utf-8')
r = subprocess.run([str(JDK / 'bin/javac.exe'), '@' + str(args)], capture_output=True, text=True)
print(r.stdout + r.stderr)
if r.returncode:
    sys.exit(1)
jar = ROOT / 'build/DmProbe.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(HERE / 'plugin.yml', 'plugin.yml')
    for p in classes.rglob('*.class'):
        z.write(p, p.relative_to(classes).as_posix())
print('built', jar)
