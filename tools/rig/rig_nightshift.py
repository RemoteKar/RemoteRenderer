# -*- coding: utf-8 -*-
"""RemoteRenderer rigs for the NightShift (Test server) mesh people: "bones" geometry with smooth joints.

Each person is eight item displays riding one root display (Figure.java): torso, head, arms, thighs, shins, every
part's model centred on its joint pivot, posed with display transformations base * translate(pivot) * rotation.
So a part's rest transformation is translate(pivot) and nothing else has to be known: the rig only lists the bones and
the joints whose vertices are shared between two parts.

  python tools/rig/rig_nightshift.py [--pack <NightShift-pack.zip>] [--out build/rr-nightshift.zip] [--no-assets]

Writes a resource pack with assets/nightshift/remoterenderer/rigs/human_<look>.json for every look, plus (unless
--no-assets) the NightShift human models and textures so a test client can show the people without the server's pack.
For the real server, copy only the rigs folder into the NightShift pack.
"""
import argparse, json, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GCB = ROOT.parent
TEST = GCB / 'Test'

# joints: parent, child, axis, blend half-length along the axis and radius around the joint (blocks), which side is
# blended, falloff. The parts overlap with rounded ends ("half": both ends turn half way, rounding the joint off);
# at the hips, shoulders and neck only the limb's end is blended - the torso's jacket stays as it is.
JOINTS = [
    ('torso', 'head', (0, 1, 0), 0.030, 0.085, 'child', 'half'),
    ('torso', 'arm_l', (0, -1, 0), 0.035, 0.075, 'child', 'half'),
    ('torso', 'arm_r', (0, -1, 0), 0.035, 0.075, 'child', 'half'),
    ('torso', 'thigh_l', (0, -1, 0), 0.045, 0.100, 'child', 'half'),
    ('torso', 'thigh_r', (0, -1, 0), 0.045, 0.100, 'child', 'half'),
    ('thigh_l', 'shin_l', (0, -1, 0), 0.050, 0.095, 'both', 'half'),
    ('thigh_r', 'shin_r', (0, -1, 0), 0.050, 0.095, 'both', 'half'),
]


def rig(look, pivots):
    bones = [{'name': p, 'item_model': 'nightshift:h_%s_%s' % (look, p), 'pivot': pivots[p]}
             for p in ('torso', 'head', 'arm_l', 'arm_r', 'thigh_l', 'thigh_r', 'shin_l', 'shin_r')]
    blend = [{'parent': a, 'child': b, 'at': pivots[b], 'axis': list(axis), 'length': length, 'radius': radius, 'side': side, 'falloff': falloff}
             for a, b, axis, length, radius, side, falloff in JOINTS]
    return {'format': 1, 'bones': bones, 'anchor': 'torso', 'geometry': 'bones', 'blend': blend}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pack', default=str(TEST / 'resource-pack/NightShift-pack.zip'))
    ap.add_argument('--out', default=str(ROOT / 'build/rr-nightshift.zip'))
    ap.add_argument('--no-assets', action='store_true')
    ap.add_argument('--no-blend', action='store_true', help='rigid rigs (every vertex on its own bone): must look exactly like the bone displays')
    a = ap.parse_args()
    meta = json.loads((TEST / 'plugin-src/resources/rig.json').read_text(encoding='utf-8'))
    pivots = meta['pivots']
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('pack.mcmeta', json.dumps({'pack': {'description': 'RemoteRenderer rigs: NightShift people', 'min_format': [97, 0],
                                                       'max_format': [2147483647, 0]}}, ensure_ascii=False))
        for look in meta['looks']:
            r = rig(look, pivots)
            if a.no_blend:
                r['blend'] = []
            z.writestr('assets/nightshift/remoterenderer/rigs/human_%s.json' % look, json.dumps(r, indent=1))
            n += 1
        if not a.no_assets:
            with zipfile.ZipFile(a.pack) as src:
                for name in src.namelist():
                    base = name.rsplit('/', 1)[-1]
                    if name.startswith(('assets/nightshift/items/h_', 'assets/nightshift/models/item/h_', 'assets/nightshift/textures/block/h_')) or \
                            (name.startswith('assets/minecraft/atlases/') and name.endswith('.json')):
                        z.writestr(name, src.read(name))
    print('wrote', out, n, 'rigs')


if __name__ == '__main__':
    main()
