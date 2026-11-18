# -*- coding: utf-8 -*-
"""Writes build/bm/meshtest.bbmodel: a Blockbench "free" model for BetterModel with mesh bones.

  body (bone)          a cube "base"
   +- ring (bone)      a mesh: torus 32 x 12 quads (closed surface -> BetterModel's single sided triangles)
   +- flag (bone)      a mesh: wavy open sheet 12 x 8 (boundary -> double sided triangles)

animation "idle" (8 s, played once by /bm test): the ring turns 720 degrees and bobs, the flag swings.
Outliner groups carry name / origin inline (the Blockbench 4 layout BetterModel reads).
"""
import base64, io, json, math, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/bm/meshtest.bbmodel'


def uid():
    return str(uuid.uuid4())


def texture_png():
    from PIL import Image
    img = Image.new('RGBA', (32, 32))
    for y in range(32):
        for x in range(32):
            h = x / 32.0
            r = int(127 + 127 * math.sin(6.283 * h))
            g = int(127 + 127 * math.sin(6.283 * (h + 0.33)))
            b = int(127 + 127 * math.sin(6.283 * (h + 0.66)))
            if (y // 4) % 2:
                r, g, b = r * 3 // 4, g * 3 // 4, b * 3 // 4
            img.putpixel((x, y), (r, g, b, 255))
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    return 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()


def mesh(name, origin, grid, point):
    """grid (n, m), point(i, j) -> (x, y, z) in pixels relative to origin; one quad face per cell."""
    n, m = grid
    vertices, faces = {}, {}
    for i in range(n + 1):
        for j in range(m + 1):
            x, y, z = point(i, j)
            vertices['v%d_%d' % (i, j)] = [round(x, 4), round(y, 4), round(z, 4)]
    for i in range(n):
        for j in range(m):
            keys = ['v%d_%d' % (i, j), 'v%d_%d' % (i, j + 1), 'v%d_%d' % (i + 1, j + 1), 'v%d_%d' % (i + 1, j)]
            uv = {k: [round(32 * int(k.split('_')[0][1:]) / n, 3), round(32 * int(k.split('_')[1]) / m, 3)] for k in keys}
            faces['f%d_%d' % (i, j)] = {'uv': uv, 'vertices': keys, 'texture': 0}
    return {'name': name, 'type': 'mesh', 'uuid': uid(), 'origin': origin, 'rotation': [0, 0, 0], 'visibility': True,
            'export': True, 'locked': False, 'vertices': vertices, 'faces': faces}


def main():
    big, small = 10.0, 3.5
    n, m = 32, 12

    def torus(i, j):
        u = 2 * math.pi * (i % n) / n
        v = 2 * math.pi * (j % m) / m
        r = big + small * math.cos(v)
        return r * math.cos(u), small * math.sin(v), r * math.sin(u)

    ring = mesh('donut', [0, 16, 0], (n, m), torus)
    # the shared seam vertices of a closed torus: reuse the first column / row keys so the surface is closed
    for f in ring['faces'].values():
        f['vertices'] = ['v%d_%d' % (int(k.split('_')[0][1:]) % n, int(k.split('_')[1]) % m) for k in f['vertices']]
        f['uv'] = {('v%d_%d' % (int(k.split('_')[0][1:]) % n, int(k.split('_')[1]) % m)): uv for k, uv in f['uv'].items()}
    ring['vertices'] = {k: v for k, v in ring['vertices'].items() if int(k.split('_')[0][1:]) < n and int(k.split('_')[1]) < m}

    def sheet(i, j):
        x = 16.0 * i / 12 - 8.0
        return x, 10.0 * j / 8, 2.0 * math.sin(x / 2.0)

    flag = mesh('sheet', [14, 4, 0], (12, 8), sheet)
    base = {'name': 'base', 'type': 'cube', 'uuid': uid(), 'from': [-6, 0, -6], 'to': [6, 4, 6], 'origin': [0, 0, 0], 'rotation': [0, 0, 0],
            'box_uv': False, 'faces': {f: {'uv': [0, 0, 16, 4], 'texture': 0} for f in ('north', 'east', 'south', 'west', 'up', 'down')}}

    ring_bone = {'name': 'ring', 'uuid': uid(), 'origin': [0, 16, 0], 'rotation': [0, 0, 0], 'visibility': True, 'children': [ring['uuid']]}
    flag_bone = {'name': 'flag', 'uuid': uid(), 'origin': [14, 4, 0], 'rotation': [0, 0, 0], 'visibility': True, 'children': [flag['uuid']]}
    body = {'name': 'body', 'uuid': uid(), 'origin': [0, 0, 0], 'rotation': [0, 0, 0], 'visibility': True,
            'children': [base['uuid'], ring_bone, flag_bone]}

    def kf(channel, t, x, y, z):
        return {'channel': channel, 'data_points': [{'x': str(x), 'y': str(y), 'z': str(z)}], 'uuid': uid(), 'time': t, 'interpolation': 'linear'}

    animators = {
        ring_bone['uuid']: {'name': 'ring', 'type': 'bone', 'keyframes':
                            [kf('rotation', t, 0, 90 * t, 0) for t in range(0, 9)] + [kf('position', t, 0, 3 * math.sin(t * 1.5), 0) for t in range(0, 9)]},
        flag_bone['uuid']: {'name': 'flag', 'type': 'bone', 'keyframes': [kf('rotation', t, 0, 30 * math.sin(t * 1.3), 0) for t in range(0, 9)]},
    }
    model = {
        'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': False},
        'name': 'meshtest', 'resolution': {'width': 32, 'height': 32},
        'elements': [base, ring, flag], 'outliner': [body],
        'textures': [{'name': 'meshtest', 'id': '0', 'uuid': uid(), 'width': 32, 'height': 32, 'uv_width': 32, 'uv_height': 32,
                      'render_mode': 'default', 'source': texture_png()}],
        'animations': [{'uuid': uid(), 'name': 'idle', 'loop': 'once', 'override': False, 'length': 8, 'snapping': 20, 'animators': animators}],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(model), encoding='utf-8', newline='\n')
    print('wrote', OUT, 'ring faces', len(ring['faces']), 'flag faces', len(flag['faces']))


if __name__ == '__main__':
    main()
