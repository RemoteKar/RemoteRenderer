# -*- coding: utf-8 -*-
"""Builds the RemoteRenderer client mod without Gradle.

Minecraft 26.x ships unobfuscated, so the mod is compiled straight against the 26.3 client jar
(Mojang names = runtime names) and needs no refmap.

  python mod/build.py            -> build/remoterenderer-<version>.jar
  python mod/build.py --install <mods folder>   also copies the jar there (replacing older remoterenderer jars)
"""
import json, os, shutil, subprocess, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
GCB = ROOT.parent
JDK = GCB / 'Java/Java_25'
CLIENT_JAR = GCB / 'BrawlStars/build/minecraft-26.3-client.jar'
CLIENT_LIBS = GCB / 'BrawlStars/build/client/libraries'
OUT = ROOT / 'build'


def main():
    meta = json.loads((HERE / 'resources/fabric.mod.json').read_text(encoding='utf-8'))
    version = meta['version']
    cp = [str(CLIENT_JAR)] + [str(p) for p in sorted(CLIENT_LIBS.rglob('*.jar'))] + [str(p) for p in sorted((HERE / 'lib').glob('*.jar'))]
    classes = OUT / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    sources = sorted(str(p) for p in (HERE / 'src').rglob('*.java'))
    argfile = OUT / 'javac.args'
    argfile.write_text('\n'.join(['-encoding', 'UTF-8', '--release', '25', '-nowarn', '-Xlint:-options', '-proc:none',
                                  '-cp', '"' + ';'.join(cp).replace('\\', '/') + '"', '-d', '"' + str(classes).replace('\\', '/') + '"']
                                 + ['"' + s.replace('\\', '/') + '"' for s in sources]), encoding='utf-8')
    r = subprocess.run([str(JDK / 'bin/javac.exe'), '@' + str(argfile)], capture_output=True, text=True, encoding='utf-8', errors='replace')
    sys.stdout.write(r.stdout)
    sys.stdout.write(r.stderr)
    if r.returncode != 0:
        print('BUILD FAILED')
        sys.exit(1)
    jar = OUT / f'remoterenderer-{version}.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
        for p in sorted(classes.rglob('*.class')):
            z.write(p, p.relative_to(classes).as_posix())
        for p in sorted((HERE / 'resources').rglob('*')):
            if p.is_file():
                z.write(p, p.relative_to(HERE / 'resources').as_posix())
        lic = ROOT / 'LICENSE'
        if lic.exists():
            z.write(lic, 'LICENSE_remoterenderer')
    print('built', jar, jar.stat().st_size, 'bytes')
    if '--install' in sys.argv:
        mods = Path(sys.argv[sys.argv.index('--install') + 1])
        for old in mods.glob('remoterenderer-*.jar'):
            old.unlink()
        shutil.copy(jar, mods / jar.name)
        print('installed into', mods)


if __name__ == '__main__':
    main()
