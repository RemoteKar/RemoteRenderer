# -*- coding: utf-8 -*-
"""Writes build/bm/capetest.bbmodel + capetest.rr.json: a BetterModel cube character with a cape and a long ponytail,
both declared as physics on the server side (group names ending in "_spring", the sidecar links the cape's columns and
gives the body a collider). BetterModel itself sees an ordinary model; RemoteRenderer clients see the cape swing.

  cape: 3 columns (l, c, r) x 4 segments, each segment a group "mantle_<col><n>_spring" (not cape_: BetterModel reserves the cape tag) under the one above
  hair: 5 segments "hair<n>_spring" from the back of the head
"""
import base64, io, json, uuid
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
COLORS = {'body': (90, 110, 140), 'skin': (230, 190, 160), 'cape': (170, 30, 40), 'capein': (110, 20, 30), 'hair': (60, 40, 90), 'leg': (50, 50, 60)}


def uid(s):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'capetest/' + s))


def main():
    # texture: one 4x4 cell per colour, uv in a 16 x 16 grid
    img = Image.new('RGBA', (16, 16), (255, 255, 255, 255))
    cells = {}
    for i, (name, c) in enumerate(COLORS.items()):
        x, y = (i % 4) * 4, (i // 4) * 4
        for dx in range(4):
            for dy in range(4):
                shade = 1.0 - 0.06 * ((dx + dy) % 2)
                img.putpixel((x + dx, y + dy), tuple(int(v * shade) for v in c) + (255,))
        cells[name] = [x + 0.5, y + 0.5, x + 3.5, y + 3.5]
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    elements, groups = [], {}

    def cube(name, fr, to, color, inner=None):
        faces = {f: {'uv': cells[color], 'texture': 0} for f in ('north', 'east', 'south', 'west', 'up', 'down')}
        if inner:
            faces['north'] = {'uv': cells[inner], 'texture': 0}
        e = {'name': name, 'type': 'cube', 'uuid': uid('e/' + name), 'from': fr, 'to': to, 'origin': [0, 0, 0], 'rotation': [0, 0, 0],
             'faces': faces, 'autouv': 0, 'color': 0, 'box_uv': False, 'rescale': False, 'export': True, 'visibility': True}
        elements.append(e)
        return e['uuid']

    def group(name, origin, children, parent=None):
        g = {'name': name, 'origin': origin, 'rotation': [0, 0, 0], 'uuid': uid('g/' + name), 'export': True, 'visibility': True, 'children': children}
        groups[name] = g
        if parent:
            groups[parent]['children'].append(g)
        return g

    root = group('body', [0, 12, 0], [cube('torso', [-4, 12, -2], [4, 24, 2], 'body'), cube('leg_l', [0.2, 0, -1.8], [3.8, 12, 1.8], 'leg'),
                                      cube('leg_r', [-3.8, 0, -1.8], [-0.2, 12, 1.8], 'leg')])
    group('head', [0, 24, 0], [cube('head', [-4, 24, -4], [4, 32, 4], 'skin')], 'body')
    # cape: hangs from the shoulders behind the back (+z is the back: the model faces -z in Blockbench)
    for col, (x0, x1) in (('l', (1.5, 5)), ('c', (-1.5, 1.5)), ('r', (-5, -1.5))):
        parent = 'body'
        for n in range(4):
            top = 23.5 - n * 5.5
            name = 'mantle_%s%d_spring' % (col, n + 1)
            group(name, [(x0 + x1) / 2, top, 2.6], [cube(name, [x0, top - 5.5, 2.3], [x1, top, 2.9], 'cape', 'capein')], parent)
            parent = name
    # ponytail from the back of the head
    parent = 'head'
    for n in range(5):
        top = 30 - n * 3.6
        name = 'hair%d_spring' % (n + 1)
        group(name, [0, top, 4.4], [cube(name, [-1.2, top - 3.6, 3.9], [1.2, top, 5.1], 'hair')], parent)
        parent = name
    model = {'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': False}, 'name': 'capetest', 'model_identifier': 'capetest',
             'resolution': {'width': 16, 'height': 16}, 'elements': elements, 'outliner': [root],
             'textures': [{'name': 'capetest.png', 'id': '0', 'uuid': uid('t'), 'width': 16, 'height': 16, 'uv_width': 16, 'uv_height': 16,
                           'render_mode': 'default', 'render_sides': 'front', 'visible': True, 'internal': True,
                           'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}],
             'animations': []}
    out = ROOT / 'build/bm/capetest.bbmodel'
    out.write_text(json.dumps(model), encoding='utf-8')
    side = {
        'springs': {'mantle_*': {'stiffness': 0.1, 'drag': 0.14, 'gravity': 6, 'radius': 0.6},
                    'hair*': {'stiffness': 0.08, 'drag': 0.12, 'gravity': 6, 'radius': 0.8}},
        'link_rings': ['mantle_*'],
        'colliders': [{'bone': 'body', 'from': [0, 0, 0], 'to': [0, 11, 0], 'radius': 3.0},
                      {'bone': 'body', 'from': [1.8, -1, 0], 'to': [1.8, -11, 0], 'radius': 2.2},
                      {'bone': 'body', 'from': [-1.8, -1, 0], 'to': [-1.8, -11, 0], 'radius': 2.2}],
    }
    (ROOT / 'build/bm/capetest.rr.json').write_text(json.dumps(side, indent=1), encoding='utf-8')
    print('wrote', out, len(elements), 'elements', len(groups), 'groups')


if __name__ == '__main__':
    main()
