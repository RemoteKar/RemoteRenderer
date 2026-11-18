# -*- coding: utf-8 -*-
"""Builds build/dm-testpack.zip, the resource pack the RemoteRenderer tests run with:

- dmtest:torus / sphere / flag: mesh bones as BetterModel 3.5 makes them (java-mesh composites of
  bettermodel:mesh_triangle_single/duplex with a constant tint per triangle) - see MeshGen.java
- bettermodel:model/pk_abomasnow_*: a real BetterModel cube-bone model (one item per bone, custom_model_data
  tint), taken from the CobbleMon server's BetterModel build
- dmtest:objmc_twist: an animated objmc model (8 frames, linear easing, autoplay, autorotate), objmc format
  written by objmc_encode.py
- dmtest:objmc_spin: a still objmc model turned by the item colour (colorbehavior pitch yaw roll, autorotate off)

  python tools/testpack/make_pack.py
"""
import json, math, shutil, subprocess, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
GCB = ROOT.parent
JAVA = GCB / 'Java/Java_25/bin/java.exe'
LIBS = GCB / 'BrawlStars/build/client/libraries'
BM_BUILD = GCB / 'CobbleMon/plugins/BetterModel/build.zip'
WORK = ROOT / 'build/testpack-work'
PACK = WORK / 'pack'
OUT = ROOT / 'build/dm-testpack.zip'


def lib(name):
    found = sorted(LIBS.rglob(name))
    if not found:
        raise SystemExit('missing library ' + name)
    return str(found[-1])


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, separators=(',', ':')), encoding='utf-8', newline='\n')


def mesh_items():
    cp = [str(HERE / 'lib/java-mesh-0.0.1.jar'), lib('gson-*.jar'), lib('joml-*.jar'), lib('fastutil-*.jar')]
    ann = sorted(LIBS.rglob('annotations-*.jar'))
    if ann:
        cp.append(str(ann[-1]))
    r = subprocess.run([str(JAVA), '-cp', ';'.join(cp), str(HERE / 'MeshGen.java'), str(PACK)], capture_output=True, text=True)
    print(r.stdout.strip())
    if r.returncode != 0:
        print(r.stderr)
        raise SystemExit('MeshGen failed')


def bettermodel_files():
    with zipfile.ZipFile(BM_BUILD) as z:
        names = [n for n in z.namelist() if n.endswith(('.json', '.png')) and (
            'pk_abomasnow' in n or 'mesh_triangle' in n or n.endswith('mesh_pixel.png'))]
        for n in names:
            dst = PACK / n
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(z.read(n))
    print('bettermodel files:', len(names))


def torus_frame(n, m, big, small, twist):
    """One frame (objmc's obj input): the same topology in every frame, quads, 0-based indices."""
    positions, uvs, faces = [], [], []
    for i in range(n + 1):
        for j in range(m + 1):
            u = 2 * math.pi * i / n
            v = 2 * math.pi * j / m + twist * math.sin(u)
            r = big + small * math.cos(v)
            positions.append((round(r * math.cos(u), 5), round(small * math.sin(v) + 0.5, 5), round(r * math.sin(u), 5)))
            uvs.append((round(i / n, 5), round(j / m, 5)))
    for i in range(n):
        for j in range(m):
            a, b, c, d = i * (m + 1) + j, (i + 1) * (m + 1) + j, (i + 1) * (m + 1) + j + 1, i * (m + 1) + j + 1
            # counter-clockwise seen from outside (OBJ convention), so the outer faces survive back-face culling
            faces.append([(a, a), (d, d), (c, c), (b, b)])
    return {'positions': positions, 'uvs': uvs, 'faces': faces}


def checker():
    from PIL import Image
    img = Image.new('RGBA', (32, 32))
    for y in range(32):
        for x in range(32):
            on = ((x // 4) + (y // 4)) % 2 == 0
            img.putpixel((x, y), (230, 120, 40, 255) if on else (40, 90, 200, 255))
    return img


def objmc_model(name, frames, **options):
    sys.path.insert(0, str(HERE))
    from objmc_encode import encode
    data = [torus_frame(16, 8, 0.6, 0.22, 0.9 * math.sin(2 * math.pi * f / frames) if frames > 1 else 0.0) for f in range(frames)]
    model, image = encode(data, checker(), **options)
    print('objmc', name, 'faces', len(data[0]['faces']), 'frames', frames, 'texture', image.size)
    model['textures'] = {'0': 'dmtest:item/' + name, 'particle': 'dmtest:item/' + name}
    write_json(PACK / ('assets/dmtest/models/item/%s.json' % name), model)
    tex = PACK / ('assets/dmtest/textures/item/%s.png' % name)
    tex.parent.mkdir(parents=True, exist_ok=True)
    image.save(tex)
    # the objmc faces have tintindex 0: the item colour comes from custom_model_data colors[0]
    write_json(PACK / ('assets/dmtest/items/%s.json' % name),
               {'model': {'type': 'model', 'model': 'dmtest:item/' + name, 'tints': [{'type': 'custom_model_data', 'default': 0}]}})


def glassbox():
    """A model with opaque and half-clear texels: its solid quads take the GPU path, the translucent ones stay vanilla."""
    from PIL import Image
    img = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), (200, 60, 60, 255) if x < 8 else (80, 160, 255, 110))
    tex = PACK / 'assets/dmtest/textures/item/glassbox.png'
    tex.parent.mkdir(parents=True, exist_ok=True)
    img.save(tex)
    faces = lambda u0, u1: {f: {'uv': [u0, 0, u1, 16], 'texture': '#0'} for f in ('north', 'south', 'east', 'west', 'up', 'down')}
    write_json(PACK / 'assets/dmtest/models/item/glassbox.json', {
        'textures': {'0': 'dmtest:item/glassbox', 'particle': 'dmtest:item/glassbox'},
        'elements': [{'from': [4, 0, 4], 'to': [12, 8, 12], 'faces': faces(0, 8)},
                     {'from': [2, 8, 2], 'to': [14, 14, 14], 'faces': faces(8, 16)}]})
    write_json(PACK / 'assets/dmtest/items/glassbox.json', {'model': {'type': 'model', 'model': 'dmtest:item/glassbox'}})


def main():
    if WORK.exists():
        shutil.rmtree(WORK)
    PACK.mkdir(parents=True)
    write_json(PACK / 'pack.mcmeta', {'pack': {'description': 'RemoteRenderer test pack', 'min_format': 97, 'max_format': 97}})
    mesh_items()
    bettermodel_files()
    objmc_model('objmc_twist', 8, duration=40, easing=1, autoplay=True, autorotate=1)
    objmc_model('objmc_spin', 1, autoplay=False, autorotate=0)
    glassbox()
    OUT.unlink(missing_ok=True)
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(PACK.rglob('*')):
            if p.is_file():
                z.write(p, p.relative_to(PACK).as_posix())
    print('pack', OUT, OUT.stat().st_size, 'bytes')


if __name__ == '__main__':
    main()
