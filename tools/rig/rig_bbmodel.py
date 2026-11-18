# -*- coding: utf-8 -*-
"""BetterModel model (.bbmodel) -> RemoteRenderer rig, for the latest BetterModel (3.6.0; bone placement measured, see
bbmodel_rest.py). The server keeps running BetterModel unchanged; clients with RemoteRenderer draw:

  - cube models: the bones' own models, with smooth joints ("bones" geometry + blends picked automatically for
    limb-like bones; edit the "blend" list in the written JSON to tune or add joints);
  - models with mesh elements: the meshes with their real textures (BetterModel itself draws a mesh as flat-coloured
    triangles), one skinned mesh for the whole model, each vertex on its bone (+ the same automatic joint blends).

  python tools/rig/rig_bbmodel.py <model.bbmodel> [--out build/rr-<model>.zip] [--ns bettermodel] [--blend auto|none]
                                  [--max-texture 2048] [--uv bettermodel|blockbench]

Physics, declared on the server side with the model (BetterModel ignores both):
  - a group whose name ends in "_spring" (hair_spring, skirt_front_spring) swings, bones below it follow;
  - <model>.rr.json next to the .bbmodel: {"springs": {"<group name or pattern>": {"stiffness": 0.08, "drag": 0.12,
    "gravity": 6, "radius": 0.5, "max_angle": 0}}, "colliders": [{"bone": "leg_l", "from": [0,0,0], "to": [0,-8,0], "radius": 2}]}
    (lengths in the model's pixels, from/to in the bone's own rest space: its pivot is 0,0,0);
    "link_rings": ["skirt*"] links neighbouring spring chains ring by ring, "links": [{"a": .., "b": .., "stiffness": 0.6}] by hand.

Bones are the groups that hold elements (BetterModel makes one item display each: bettermodel:model/<model>_<group>).
"""
import argparse, base64, fnmatch, io, json, math, struct, zipfile
from pathlib import Path

import numpy as np
from PIL import Image

import bbmodel_rest as br

ROOT = Path(__file__).resolve().parents[2]


def colmajor(m):
    return [round(float(x), 7) for x in np.asarray(m).T.reshape(-1)]


def element_points(e):
    """Stored-space vertices of an element: mesh {key: xyz}, cube {corner: xyz}."""
    origin = np.array(e.get('origin', [0, 0, 0]), float)
    rot = br.euler(e.get('rotation', [0, 0, 0]))[:3, :3]
    if e.get('type') == 'mesh':
        return {k: origin + rot @ np.array(v, float) for k, v in e['vertices'].items()}
    f, t = np.array(e['from'], float), np.array(e['to'], float)
    inflate = float(e.get('inflate', 0))
    f, t = f - inflate, t + inflate
    pts = {}
    for i, x in enumerate((f[0], t[0])):
        for j, y in enumerate((f[1], t[1])):
            for k, z in enumerate((f[2], t[2])):
                pts['%d%d%d' % (i, j, k)] = origin + rot @ (np.array([x, y, z]) - origin)
    return pts


# cube faces: corner keys (counter-clockwise seen from outside, starting top-left of the texture) like vanilla
CUBE_FACES = {
    'north': ('110', '010', '000', '100'), 'south': ('011', '111', '101', '001'),
    'east': ('111', '110', '100', '101'), 'west': ('010', '011', '001', '000'),
    'up': ('010', '110', '111', '011'), 'down': ('001', '101', '100', '000'),
}


def smoothstep(a, b, x):
    t = min(1.0, max(0.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


def weights(blends, own, p, nb):
    """Port of RigGeometry.weights (the same rules the client uses for "bones" geometry)."""
    w = np.zeros(nb)
    w[own] = 1.0
    for bl in blends:
        parent, child = bl['_p'] == own, bl['_c'] == own
        if not parent and not child or parent and bl['side'] == 'child' or child and bl['side'] == 'parent':
            continue
        d3 = p - np.array(bl['at'])
        d = float(d3 @ np.array(bl['axis']))
        g = 1 - smoothstep(bl['radius'], bl['radius'] * 1.5, float(np.linalg.norm(d3)))
        if g <= 0:
            continue
        if bl['falloff'] == 'half':
            t = 0.5 * smoothstep(-bl['length'], 0, d) if parent else 0.5 + 0.5 * smoothstep(0, bl['length'], d)
        else:
            t = smoothstep(-bl['length'], bl['length'], d)
        if parent:
            move = w[own] * t * g
            w[own] -= move
            w[bl['_c']] += move
        else:
            move = w[own] * (1 - t) * g
            w[own] -= move
            w[bl['_p']] += move
    return w


def quant(w):
    idx = np.argsort(-w)[:4]
    val = w[idx]
    s = val.sum()
    q = np.round(val / s * 255).astype(int) if s > 0 else np.array([255, 0, 0, 0])
    q[0] += 255 - q.sum()
    return list(idx) + [0] * (4 - len(idx)), list(q) + [0] * (4 - len(q))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('bbmodel')
    ap.add_argument('--name', help='BetterModel model name (default: file name)')
    ap.add_argument('--ns', default='bettermodel')
    ap.add_argument('--out')
    ap.add_argument('--blend', default='auto', choices=('auto', 'none'))
    ap.add_argument('--max-texture', type=int, default=2048, help='mesh textures larger than this are scaled down (0 = keep)')
    ap.add_argument('--uv', default='bettermodel', choices=('bettermodel', 'blockbench'),
                    help='mesh UVs as BetterModel reads them (u/16, flipped v - what the model was made to look like) or as Blockbench does')
    a = ap.parse_args()
    src = Path(a.bbmodel)
    model = json.loads(src.read_text(encoding='utf-8'))
    name = (a.name or src.stem).lower()
    out = Path(a.out) if a.out else ROOT / ('build/rr-%s.zip' % name)

    gs = br.groups(model)
    elements = {e['uuid']: e for e in model['elements']}
    owner = {}       # element uuid -> group name

    def walk(node, parent):
        if isinstance(node, str):
            if parent is not None:
                owner[node] = parent
            return
        for c in node.get('children', []):
            walk(c, node.get('name') if 'name' in node else (model.get('groups') and next(g['name'] for g in model['groups'] if g['uuid'] == node['uuid'])))
    for n in model['outliner']:
        walk(n, None)
    bone_names = [g for g in gs if any(owner.get(u) == g and elements[u].get('export', True) for u in elements)]
    bone_index = {g: i for i, g in enumerate(bone_names)}
    W = {g: br.world(gs, g) for g in gs}

    def to_visual(g, p):
        return (W[g] @ np.append(p, 1.0))[:3]

    # geometry per bone (visual bbmodel space, pixels)
    pts_by_bone = {g: [] for g in bone_names}
    has_mesh = False
    for u, e in elements.items():
        g = owner.get(u)
        if g not in bone_index or not e.get('export', True):
            continue
        has_mesh |= e.get('type') == 'mesh'
        pts_by_bone[g] += [to_visual(g, p) for p in element_points(e).values()]

    def nearest_bone_ancestor(g):
        p = gs[g][2]
        while p is not None and p not in bone_index:
            p = gs[p][2]
        return p

    blends = []
    if a.blend == 'auto':
        for g in bone_names:
            parent = nearest_bone_ancestor(g)
            if parent is None or not pts_by_bone[g] or not pts_by_bone[parent]:
                continue
            at = to_visual(g, gs[g][0])
            pts = np.array(pts_by_bone[g])
            axis = pts.mean(axis=0) - at
            if np.linalg.norm(axis) < 1e-6:
                continue
            axis /= np.linalg.norm(axis)
            along = (pts - at) @ axis
            perp = np.linalg.norm((pts - at) - np.outer(along, axis), axis=1)
            length_total, thick = along.max() - along.min(), max(perp.max(), 0.25)
            # limb-like: longer than thick, and its joint at the parent's surface (inside the parent's box, 1 px slack)
            ppts = np.array(pts_by_bone[parent])
            inside = np.all(at >= ppts.min(axis=0) - 1.0) and np.all(at <= ppts.max(axis=0) + 1.0)
            if length_total < 1.5 * thick or not inside:
                continue
            blends.append({'parent': parent, 'child': g, 'at': [round(float(x), 4) for x in at], 'axis': [round(float(x), 4) for x in axis],
                           'length': round(float(min(3.0, max(0.5, 0.2 * length_total))), 3),
                           'radius': round(float(min(8.0, max(1.0, 1.3 * thick))), 3), 'side': 'both', 'falloff': 'smooth',
                           '_p': bone_index[parent], '_c': bone_index[g]})
    bones = [{'name': g, 'item_model': 'bettermodel:model/%s_%s' % (name, g.lower()), 'rest': colmajor(br.rest(gs, g))} for g in bone_names]
    for b in bones:
        parent = nearest_bone_ancestor(b['name'])
        if parent is not None:
            b['parent'] = parent
    # physics declared with the model: "_spring" groups and <model>.rr.json
    side = src.with_name(src.stem + '.rr.json')
    decl = json.loads(side.read_text(encoding='utf-8')) if side.exists() else {}
    springs = {}
    for g in bone_names:
        if g.lower().endswith('_spring'):
            springs[g] = {}
    for pat, params in decl.get('springs', {}).items():
        for g in bone_names:
            if fnmatch.fnmatchcase(g, pat):
                springs[g] = dict(params)
    for b in bones:
        if b['name'] in springs:
            sp = {'stiffness': 0.08, 'drag': 0.12, 'gravity': 6.0}
            sp.update(springs[b['name']])
            if 'radius' in sp:
                sp['radius'] = sp['radius'] / 16
            if 'tail' in sp:
                sp['tail'] = [-sp['tail'][0] / 16, sp['tail'][1] / 16, -sp['tail'][2] / 16]
            b['spring'] = sp
    # the sidecar speaks the model's pixels, relative to the bone's pivot; a bone's joint space is its display's space
    # (blocks, turned half way round: see bbmodel_rest.py)
    def to_joint(v):
        return [round(-v[0] / 16, 6), round(v[1] / 16, 6), round(-v[2] / 16, 6)]
    colliders = []
    for c in decl.get('colliders', []):
        cc = dict(c)
        cc['from'] = to_joint(c.get('from', [0, 0, 0]))
        cc['to'] = to_joint(c.get('to', c.get('from', [0, 0, 0])))
        cc['radius'] = c.get('radius', 1.0) / 16
        colliders.append(cc)
    if springs:
        print('springs:', ', '.join(sorted(springs)), '| colliders:', len(colliders), '(%s)' % side.name if side.exists() else '')
    rig = {'format': 1, 'bones': bones, 'anchor': bone_names[0]}
    if colliders:
        rig['colliders'] = colliders
    links = list(decl.get('links', []))
    if decl.get('link_rings'):
        import rig_links
        names = list(bone_names)
        parents = [bone_index.get(b.get('parent'), -1) if b.get('parent') else -1 for b in bones]
        heads = [list(to_visual(g, gs[g][0])) for g in bone_names]
        spring_set = {i for i, b in enumerate(bones) if 'spring' in b}
        for pat in decl['link_rings']:
            got = rig_links.rings(names, parents, heads, spring_set, pat)
            print('link ring', pat, len(got), 'links')
            links += got
    if links:
        rig['links'] = links
    files = {}
    if not has_mesh:
        rig['geometry'] = 'bones'
        rig['blend'] = [{k: v for k, v in b.items() if not k.startswith('_')} for b in blends]
    else:
        textures = model.get('textures', [])
        res = model.get('resolution', {'width': 16, 'height': 16})
        tex_out, verts, tris = {}, [], {}
        nb = len(bone_names)
        for u, e in elements.items():
            g = owner.get(u)
            if g not in bone_index or not e.get('export', True):
                continue
            pts = {k: to_visual(g, p) for k, p in element_points(e).items()}
            faces = []
            if e.get('type') == 'mesh':
                for f in e['faces'].values():
                    keys = f['vertices']
                    if len(keys) < 3 or f.get('texture') is None:
                        continue
                    if len(keys) == 4:
                        # Blockbench stores quad corners in any order: sort them around the centre
                        c = np.mean([pts[k] for k in keys], axis=0)
                        nrm = np.cross(pts[keys[1]] - pts[keys[0]], pts[keys[2]] - pts[keys[0]])
                        ref = pts[keys[0]] - c
                        ang = [math.atan2(np.dot(np.cross(ref, pts[k] - c), nrm / (np.linalg.norm(nrm) + 1e-12)), np.dot(ref, pts[k] - c)) for k in keys]
                        keys = [k for _, k in sorted(zip(ang, keys))]
                    tex = textures[f['texture']]
                    uw, uh = tex.get('uv_width', res['width']), tex.get('uv_height', res['height'])
                    uvs = {}
                    for k in keys:
                        uu, vv = f['uv'][k]
                        uvs[k] = (uu / uw, 1 - vv / uh) if a.uv == 'bettermodel' else (uu / uw, vv / uh)
                    for i in range(1, len(keys) - 1):
                        faces.append((f['texture'], [keys[0], keys[i], keys[i + 1]], uvs))
            else:
                for fname, f in e.get('faces', {}).items():
                    if f.get('texture') is None or fname not in CUBE_FACES:
                        continue
                    tex = textures[f['texture']]
                    uw, uh = tex.get('uv_width', res['width']), tex.get('uv_height', res['height'])
                    u0, v0, u1, v1 = f['uv']
                    corners = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
                    r = int(f.get('rotation', 0)) // 90 % 4
                    corners = corners[-r:] + corners[:-r] if r else corners
                    keys = CUBE_FACES[fname]
                    uvs = {k: (c[0] / uw, c[1] / uh) for k, c in zip(keys, corners)}
                    # corners run clockwise seen from outside: reversed, so the normal from the first three points out
                    faces += [(f['texture'], [keys[0], keys[2], keys[1]], uvs), (f['texture'], [keys[0], keys[3], keys[2]], uvs)]
            # smooth normals per element (shared vertex keys)
            acc = {k: np.zeros(3) for k in pts}
            for _, ks, _ in faces:
                n = np.cross(pts[ks[1]] - pts[ks[0]], pts[ks[2]] - pts[ks[0]])
                for k in ks:
                    acc[k] += n
            for ti, ks, uvs in faces:
                base = len(verts)
                for k in ks:
                    n = acc[k] / (np.linalg.norm(acc[k]) + 1e-12)
                    w = weights(blends, bone_index[g], pts[k], nb)
                    ji, wq = quant(w)
                    verts.append(struct.pack('<3f4B2f4b4B4B4B', *pts[k], 255, 255, 255, 255, *uvs[k], *[int(round(x * 127)) for x in n], 0,
                                             0, 0, 0, 0, *[int(x) for x in ji], *[int(x) for x in wq]))
                tris.setdefault(ti, []).append([base, base + 1, base + 2])
        submeshes, index = [], []
        for ti in sorted(tris):
            tex = textures[ti]
            raw = base64.b64decode(tex['source'].split(',', 1)[1])
            img = Image.open(io.BytesIO(raw)).convert('RGBA')
            if a.max_texture and max(img.size) > a.max_texture:
                k = a.max_texture / max(img.size)
                img = img.resize((max(1, round(img.width * k)), max(1, round(img.height * k))), Image.LANCZOS)
            buf = io.BytesIO()
            img.save(buf, 'PNG', optimize=True)
            path = 'textures/rig/%s/%d.png' % (name, ti)
            files['assets/%s/%s' % (a.ns, path)] = buf.getvalue()
            submeshes.append({'texture': '%s:%s' % (a.ns, path), 'first': len(index) * 3, 'count': len(tris[ti]) * 3, 'double_sided': True})
            index += tris[ti]
        vdata = b''.join(verts)
        idata = np.array(index, '<u4').tobytes()
        files['assets/%s/remoterenderer/rigs/%s.bin' % (a.ns, name)] = vdata + idata
        joints = []
        for b in bones:
            rest = br.rest(gs, b['name'])
            parent = b.get('parent')
            local = rest if parent is None else np.linalg.inv(br.rest(gs, parent)) @ rest
            jt = {'name': b['name'], 'parent': bone_index[parent] if parent is not None else -1, 'local': colmajor(local),
                  'inverse_bind': colmajor(np.linalg.inv(rest)), 'bone': b['name']}
            if 'spring' in b:
                jt['spring'] = b['spring']
            joints.append(jt)
        rig['geometry'] = {'data': '%s:remoterenderer/rigs/%s.bin' % (a.ns, name), 'vertices': len(verts), 'indices': len(index) * 3,
                           'vertex_offset': 0, 'index_offset': len(vdata), 'submeshes': submeshes}
        rig['skeleton'] = {'joints': joints}
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('pack.mcmeta', json.dumps({'pack': {'description': 'RemoteRenderer rig: ' + name, 'min_format': [97, 0], 'max_format': [2147483647, 0]}}))
        z.writestr('assets/%s/remoterenderer/rigs/%s.json' % (a.ns, name), json.dumps(rig, indent=1))
        for k, v in files.items():
            z.writestr(k, v)
    print('wrote', out, '|', 'mesh' if has_mesh else 'bones', 'geometry |', len(bone_names), 'bones |', len(blends), 'joint blends:',
          ', '.join('%s>%s' % (b['parent'], b['child']) for b in blends[:24]) + (' ...' if len(blends) > 24 else ''))


if __name__ == '__main__':
    main()
