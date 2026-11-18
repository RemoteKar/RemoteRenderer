# -*- coding: utf-8 -*-
"""Writes build/bm/rigprobe.bbmodel: nested bones with pivots and rest rotations on every axis, one asymmetric cube
each, no animation. Spawned on the BetterModel test server with -Dremoterenderer.dump=rigprobe, the RR_DUMP lines give
each bone display's transformation at rest and its baked bounds: what tools/rig needs to know about a BetterModel
version (rig_bbmodel.py rest(), checked by tests/rig_probe.py).
"""
import base64, io, json, uuid
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]

# name, parent, origin (px), rotation (deg, Blockbench XYZ), cube from, cube to
BONES = [
    ('body', None, [0, 0, 0], [0, 0, 0], [-4, 0, -2], [4, 12, 3]),
    ('arm', 'body', [4, 12, 0], [10, 20, 30], [4, 3, -1], [6, 12, 2]),
    ('hand', 'arm', [5, 3, 0], [-25, 0, 0], [4, 0, -1], [7, 3, 1]),
    ('tail', 'body', [0, 2, 3], [0, 45, -15], [-1, 1, 3], [1, 3, 9]),
]


def uid(s):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'rigprobe/' + s))


def main():
    img = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), (40 + x * 13, 60 + y * 11, 200 - x * 8, 255))
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    elements, groups = [], {}
    for name, parent, origin, rot, fr, to in BONES:
        faces = {f: {'uv': [0, 0, 16, 16], 'texture': 0} for f in ('north', 'east', 'south', 'west', 'up', 'down')}
        e = {'name': name + '_cube', 'type': 'cube', 'uuid': uid('e/' + name), 'from': fr, 'to': to, 'origin': origin, 'rotation': [0, 0, 0],
             'faces': faces, 'autouv': 0, 'color': 0, 'box_uv': False, 'rescale': False, 'locked': False, 'render_order': 'default',
             'allow_mirror_modeling': True, 'export': True, 'visibility': True}
        elements.append(e)
        groups[name] = {'name': name, 'origin': origin, 'rotation': rot, 'uuid': uid('g/' + name), 'export': True, 'visibility': True,
                        'children': [e['uuid']]}
    roots = []
    for name, parent, *_ in BONES:
        if parent:
            groups[parent]['children'].append(groups[name])
        else:
            roots.append(groups[name])
    model = {'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': False}, 'name': 'rigprobe', 'model_identifier': 'rigprobe',
             'resolution': {'width': 16, 'height': 16}, 'elements': elements, 'outliner': roots,
             'textures': [{'name': 'rigprobe.png', 'id': '0', 'uuid': uid('t'), 'width': 16, 'height': 16, 'uv_width': 16, 'uv_height': 16,
                           'render_mode': 'default', 'render_sides': 'front', 'visible': True, 'internal': True,
                           'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}],
             'animations': []}
    out = ROOT / 'build/bm/rigprobe.bbmodel'
    out.write_text(json.dumps(model), encoding='utf-8')
    print('wrote', out)


if __name__ == '__main__':
    main()
