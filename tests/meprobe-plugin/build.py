# -*- coding: utf-8 -*-
"""Builds build/MeProbe.jar (test-only Paper plugin) against the DivineMC copy in build/bmserver and ModelEngine (RR_ME_JAR)."""
import os, subprocess, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
GCB = ROOT.parent
JDK = GCB / 'Java/Java_25'
SERVER = ROOT / 'build/bmserver'
ME_JAR = Path(os.environ.get('RR_ME_JAR', str(GCB / 'SelfCharacterBattle/plugins/ModelEngine-R4.1.1.jar')))
cp = [str(p) for p in list((SERVER / 'libraries').rglob('*.jar')) + list((SERVER / 'versions').rglob('*.jar'))]
cp.append(str(ME_JAR))
classes = ROOT / 'build/meprobe-classes'
classes.mkdir(parents=True, exist_ok=True)
args = ROOT / 'build/meprobe.args'
args.write_text('\n'.join(['-encoding', 'UTF-8', '--release', '25', '-nowarn', '-proc:none', '-cp', '"' + ';'.join(cp).replace('\\', '/') + '"',
                           '-d', '"' + str(classes).replace('\\', '/') + '"'] + ['"' + str(p).replace('\\', '/') + '"' for p in (HERE / 'src').rglob('*.java')]),
                encoding='utf-8')
r = subprocess.run([str(JDK / 'bin/javac.exe'), '@' + str(args)], capture_output=True, text=True)
print(r.stdout + r.stderr)
if r.returncode:
    sys.exit(1)
jar = ROOT / 'build/MeProbe.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(HERE / 'plugin.yml', 'plugin.yml')
    for p in classes.rglob('*.class'):
        z.write(p, p.relative_to(classes).as_posix())
print('built', jar)
