# -*- coding: utf-8 -*-
"""glTF (.glb) skinned mesh -> RemoteRenderer rig (mesh geometry): real textures, the original skin weights and
skeleton, and sampled animation clips that the client plays by itself.

The rig is anchored to one bone display that the server already sends (for a BetterModel model: its item model
bettermodel:model/<model>_<bone>). The display keeps placing the model in the world (position, body yaw, the hit
flash tint); RemoteRenderer draws the skinned mesh there instead of the display's own geometry.

  python tools/rig/rig_gltf.py --glb cirno.glb --meshes Body,Face,Wing --ns sf2 --name cirno_idle_lp_static \
      --anchor bettermodel:model/cirno_idle_lp_static_root --scale 18,18,-18 --clips idle_lp,mov_forward_lp --autoplay idle_lp \
      --out build/rr-cirno.zip

Spaces: glTF units * --scale = the BetterModel model's pixels (what build_static_idle.py wrote into the .bbmodel).
BetterModel puts a bone's model at pixels / 16 in item space and the display draws it turned half way round, so the
anchor bone's rest is scale(16) * rotateY(180) (--anchor-rest bettermodel). Measured with -Dremoterenderer.dump on
BetterModel 3.5.1 (tests: build/client.log RR_DUMP).
"""
import argparse, base64, fnmatch, io, json, struct, zipfile
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]

COMP = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NCOMP = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}


def read_glb(path):
    data = Path(path).read_bytes()
    magic, version, length = struct.unpack('<III', data[:12])
    assert magic == 0x46546C67, 'not a glb'
    off, js, bin_ = 12, None, None
    while off < length:
        n, kind = struct.unpack('<II', data[off:off + 8])
        chunk = data[off + 8:off + 8 + n]
        if kind == 0x4E4F534A:
            js = json.loads(chunk)
        elif kind == 0x004E4942:
            bin_ = chunk
        off += 8 + n
    return js, bin_


def accessor(j, b, i):
    a = j['accessors'][i]
    v = j['bufferViews'][a['bufferView']]
    dt = np.dtype(COMP[a['componentType']]).newbyteorder('<')
    nc = NCOMP[a['type']]
    count = a['count']
    start = v.get('byteOffset', 0) + a.get('byteOffset', 0)
    stride = v.get('byteStride', 0)
    item = dt.itemsize * nc
    if stride and stride != item:
        raw = np.frombuffer(b, np.uint8, count=stride * (count - 1) + item, offset=start)
        rows = np.lib.stride_tricks.as_strided(raw, (count, item), (stride, 1))
        out = np.frombuffer(rows.copy().tobytes(), dt).reshape(count, nc)
    else:
        out = np.frombuffer(b, dt, count=count * nc, offset=start).reshape(count, nc)
    if a.get('normalized'):
        out = out.astype(np.float32) / np.iinfo(dt).max
    return out


def quat_to_mat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def trs(t, r, s):
    m = np.eye(4)
    m[:3, :3] = quat_to_mat(r) @ np.diag(s)
    m[:3, 3] = t
    return m


def node_trs(n):
    if 'matrix' in n:
        m = np.array(n['matrix'], float).reshape(4, 4).T
        t = m[:3, 3]
        s = np.linalg.norm(m[:3, :3], axis=0)
        r3 = m[:3, :3] / s
        # matrix -> quaternion
        w = np.sqrt(max(0.0, 1 + r3[0, 0] + r3[1, 1] + r3[2, 2])) / 2
        x = np.copysign(np.sqrt(max(0.0, 1 + r3[0, 0] - r3[1, 1] - r3[2, 2])) / 2, r3[2, 1] - r3[1, 2])
        y = np.copysign(np.sqrt(max(0.0, 1 - r3[0, 0] + r3[1, 1] - r3[2, 2])) / 2, r3[0, 2] - r3[2, 0])
        z = np.copysign(np.sqrt(max(0.0, 1 - r3[0, 0] - r3[1, 1] + r3[2, 2])) / 2, r3[1, 0] - r3[0, 1])
        return np.array(t), np.array([x, y, z, w]), np.array(s)
    return (np.array(n.get('translation', [0, 0, 0]), float), np.array(n.get('rotation', [0, 0, 0, 1]), float),
            np.array(n.get('scale', [1, 1, 1]), float))


def colmajor(m):
    return [round(float(x), 7) for x in np.asarray(m).T.reshape(-1)]


def sample_channel(times, values, interp, t, path):
    if t <= times[0]:
        return values[0] if interp != 'CUBICSPLINE' else values[1]
    if t >= times[-1]:
        return values[-1] if interp != 'CUBICSPLINE' else values[-2]
    i = int(np.searchsorted(times, t, side='right') - 1)
    t0, t1 = times[i], times[i + 1]
    a = (t - t0) / (t1 - t0) if t1 > t0 else 0.0
    if interp == 'STEP':
        return values[i]
    if interp == 'CUBICSPLINE':
        v0, v1 = values[3 * i + 1], values[3 * (i + 1) + 1]
    else:
        v0, v1 = values[i], values[i + 1]
    if path == 'rotation':
        if np.dot(v0, v1) < 0:
            v1 = -v1
        q = v0 * (1 - a) + v1 * a
        return q / np.linalg.norm(q)
    return v0 * (1 - a) + v1 * a


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--glb', required=True)
    ap.add_argument('--meshes', default='', help='mesh node names to keep (comma list; empty = all skinned meshes)')
    ap.add_argument('--ns', required=True)
    ap.add_argument('--name', required=True)
    ap.add_argument('--anchor', required=True, help='item model id of the bone display the rig replaces')
    ap.add_argument('--anchor-rest', default='bettermodel', help='bettermodel | identity')
    ap.add_argument('--anchor-cmd', type=float, default=None, help='custom_model_data number of the anchor bone (BetterModel 3.3: item leather_horse_armor)')
    ap.add_argument('--scale', default='18,18,-18', help='glTF units -> rig space (BetterModel pixels)')
    ap.add_argument('--clips', default='', help='animation names to include (comma list)')
    ap.add_argument('--autoplay', default='')
    ap.add_argument('--fps', type=float, default=30.0)
    ap.add_argument('--max-texture', type=int, default=2048, help='textures larger than this are scaled down in the pack (0 = keep)')
    ap.add_argument('--cirno-fixes', action='store_true', help='the ankle recolour build_static_idle.py applied (removed shoes)')
    ap.add_argument('--spring', action='append', default=[], help='joint name pattern=stiffness,drag,gravity[,radius] (e.g. "Skirt*=0.06,0.15,6,0.6"): physics on those joints')
    ap.add_argument('--link-ring', action='append', default=[], help='pattern[=stiffness]: link neighbouring spring chains (a skirt) ring by ring')
    ap.add_argument('--collider', action='append', default=[], help='joint[>child][=radius]: a capsule from the joint to a child (radius in rig units; auto from the mesh if omitted); "joint>" = sphere')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()

    j, b = read_glb(a.glb)
    nodes = j['nodes']
    parent = {c: i for i, n in enumerate(nodes) for c in n.get('children', [])}
    keep = set(x for x in a.meshes.split(',') if x)
    mesh_nodes = [i for i, n in enumerate(nodes) if 'mesh' in n and 'skin' in n and (not keep or n.get('name') in keep)]
    assert mesh_nodes, 'no skinned mesh node matched'

    # skeleton: every skin joint of the kept meshes and all their ancestors, parents first
    needed = set()
    for mi in mesh_nodes:
        for jn in j['skins'][nodes[mi]['skin']]['joints']:
            k = jn
            while k is not None and k not in needed:
                needed.add(k)
                k = parent.get(k)
    order = []

    def visit(i):
        order.append(i)
        for c in nodes[i].get('children', []):
            if c in needed:
                visit(c)
    for i in sorted(needed):
        if parent.get(i) not in needed:
            visit(i)
    index = {n: k for k, n in enumerate(order)}
    G = np.diag([*map(float, a.scale.split(',')), 1.0])
    Ginv = np.linalg.inv(G)

    # inverse bind matrices by node (shared between skins when equal)
    ibm = {}
    for mi in mesh_nodes:
        skin = j['skins'][nodes[mi]['skin']]
        mats = accessor(j, b, skin['inverseBindMatrices']).reshape(-1, 4, 4).transpose(0, 2, 1)
        for jn, m in zip(skin['joints'], mats):
            if jn in ibm and not np.allclose(ibm[jn], m, atol=1e-4):
                raise SystemExit('node %s has different inverse bind matrices in two skins (not supported yet)' % nodes[jn].get('name'))
            ibm[jn] = m
    joints = []
    for n in order:
        t, r, s = node_trs(nodes[n])
        p = parent.get(n)
        entry = {'name': nodes[n].get('name', 'node%d' % n), 'parent': index[p] if p in index else -1, 'local': colmajor(trs(t, r, s))}
        entry['inverse_bind'] = colmajor((ibm[n] if n in ibm else np.linalg.inv(world_of(n, nodes, parent))) @ Ginv)
        joints.append(entry)
    assert len(joints) <= 256, '%d joints' % len(joints)

    # rest world (node defaults) for the cirno ankle fix
    def rest_world(n):
        t, r, s = node_trs(nodes[n])
        m = trs(t, r, s)
        p = parent.get(n)
        return rest_world(p) @ m if p is not None else m

    textures, tex_index, tex_png = [], {}, {}
    verts = []      # bytes per vertex
    tris_by_tex = {}
    vcount = 0
    for mi in mesh_nodes:
        n = nodes[mi]
        skin = j['skins'][n['skin']]
        for prim in j['meshes'][n['mesh']]['primitives']:
            at = prim['attributes']
            pos = accessor(j, b, at['POSITION']).astype(np.float64)
            nrm = accessor(j, b, at['NORMAL']).astype(np.float64) if 'NORMAL' in at else np.zeros_like(pos)
            uv = accessor(j, b, at['TEXCOORD_0']).astype(np.float64)
            jo = accessor(j, b, at['JOINTS_0']).astype(np.int64)
            we = accessor(j, b, at['WEIGHTS_0']).astype(np.float64)
            idx = accessor(j, b, prim['indices']).reshape(-1, 3).astype(np.int64)
            mat = j['materials'][prim['material']]
            img_i = j['textures'][mat['pbrMetallicRoughness']['baseColorTexture']['index']]['source']
            if img_i not in tex_index:
                img = j['images'][img_i]
                v = j['bufferViews'][img['bufferView']]
                raw = bytes(b[v.get('byteOffset', 0):v.get('byteOffset', 0) + v['byteLength']])
                name = Path(img.get('name', 'image%d' % img_i)).stem.lower()
                tex_index[img_i] = len(textures)
                textures.append({'id': '%s:textures/rig/%s/%s.png' % (a.ns, a.name, name), 'path': 'assets/%s/textures/rig/%s/%s.png' % (a.ns, a.name, name),
                                 'double': bool(mat.get('doubleSided', False))})
                tex_png[img_i] = raw
            ti = tex_index[img_i]
            # skin joint -> rig joint
            remap = np.array([index[x] for x in skin['joints']])
            rj = remap[jo]
            if a.cirno_fixes and n.get('name') == 'Body':
                # build_static_idle.py: the ankle bands left by the removed shoes get a skin texel
                pil = np.array(Image.open(io.BytesIO(tex_png[img_i])).convert('RGBA'))
                h, w = pil.shape[:2]
                ib = accessor(j, b, skin['inverseBindMatrices']).reshape(-1, 4, 4).transpose(0, 2, 1)
                mats = np.array([rest_world(x) @ m for x, m in zip(skin['joints'], ib)])
                hp = np.column_stack([pos, np.ones(len(pos))])
                rp = np.zeros((len(pos), 3))
                for k in range(4):
                    rp += np.einsum('nij,nj->ni', mats[jo[:, k]], hp)[:, :3] * we[:, k, None]
                rp *= [18, 18, -18]

                def sample(c):
                    return pil[np.clip((c[:, 1] * h).astype(int), 0, h - 1), np.clip((c[:, 0] * w).astype(int), 0, w - 1), :3].astype(float)
                col = sample(uv)
                mask = (rp[:, 1] > 4) & (rp[:, 1] < 10) & (col[:, 0] > col[:, 1] + 12) & (col[:, 1] > col[:, 2] + 8)
                ix = np.flatnonzero(mask)
                skinuv = uv[ix[len(ix) // 2]] if len(ix) else np.array([.32, .83])
                repaired = 0
                new_idx = []
                extra = []
                for tri in idx:
                    c = sample(uv[tri]).mean(axis=0)
                    if rp[tri, 1].max() < 7 and (c[2] > c[0] - 10 or c[1] > c[0] - 8):
                        base = len(pos) + len(extra)
                        for vi in tri:
                            extra.append(vi)
                        new_idx.append([base, base + 1, base + 2])
                        repaired += 1
                    else:
                        new_idx.append(list(tri))
                if extra:
                    ex = np.array(extra)
                    pos = np.vstack([pos, pos[ex]])
                    nrm = np.vstack([nrm, nrm[ex]])
                    uv = np.vstack([uv, np.tile(skinuv, (len(ex), 1))])
                    rj = np.vstack([rj, rj[ex]])
                    we = np.vstack([we, we[ex]])
                idx = np.array(new_idx)
                print('ankle triangles recoloured:', repaired)
            P = (np.column_stack([pos, np.ones(len(pos))]) @ G.T)[:, :3]
            N = nrm @ np.diag(np.sign(np.diag(G)[:3]))
            ln = np.linalg.norm(N, axis=1, keepdims=True)
            N = np.where(ln > 1e-9, N / np.maximum(ln, 1e-9), 0)
            for k in range(len(P)):
                w4 = we[k]
                s = w4.sum()
                q = np.round(w4 / s * 255).astype(int) if s > 0 else np.array([255, 0, 0, 0])
                q[int(np.argmax(q))] += 255 - q.sum()
                verts.append(struct.pack('<3f4B2f4b4B4B4B', *P[k], 255, 255, 255, 255, uv[k, 0], uv[k, 1],
                                         *[int(round(x * 127)) for x in N[k]], 0, 0, 0, 0, 0, *[int(x) for x in rj[k]], *[int(x) for x in q]))
            tris_by_tex.setdefault(ti, []).append(idx + vcount)
            vcount += len(P)

    # index buffer grouped by texture; the scale mirrors z, so the winding flips
    flip = np.linalg.det(G[:3, :3]) < 0
    indices, submeshes = [], []
    for ti in sorted(tris_by_tex):
        t = np.vstack(tris_by_tex[ti])
        if flip:
            t = t[:, [0, 2, 1]]
        submeshes.append({'texture': textures[ti]['id'], 'first': sum(len(x) for x in indices) * 3, 'count': len(t) * 3,
                          'double_sided': textures[ti]['double']})
        indices.append(t)
    index_data = np.vstack(indices).astype('<u4').tobytes()
    vertex_data = b''.join(verts)

    # clips: frames x clip joints x (t3 r4 s3), sampled at --fps
    clip_meta, clip_blobs = {}, []
    offset = len(vertex_data) + len(index_data)
    anims = {an['name']: an for an in j.get('animations', [])}
    for name in [x for x in a.clips.split(',') if x]:
        an = anims[name]
        chans = {}
        dur = 0.0
        for ch in an['channels']:
            node = ch['target'].get('node')
            if node not in index or ch['target']['path'] not in ('translation', 'rotation', 'scale'):
                continue
            s = an['samplers'][ch['sampler']]
            times = accessor(j, b, s['input']).reshape(-1).astype(np.float64)
            vals = accessor(j, b, s['output']).astype(np.float64)
            chans.setdefault(node, {})[ch['target']['path']] = (times, vals, s.get('interpolation', 'LINEAR'))
            dur = max(dur, float(times[-1]))
        cj = sorted(chans, key=lambda x: index[x])
        frames = max(2, int(round(dur * a.fps)) + 1)
        data = np.zeros((frames, len(cj), 10), np.float32)
        for fi in range(frames):
            t = min(dur, fi / a.fps)
            for k, node in enumerate(cj):
                bt, br, bs = node_trs(nodes[node])
                c = chans[node]
                tt = sample_channel(*c['translation'], t, 'translation') if 'translation' in c else bt
                rr = sample_channel(*c['rotation'], t, 'rotation') if 'rotation' in c else br
                ss = sample_channel(*c['scale'], t, 'scale') if 'scale' in c else bs
                data[fi, k] = [*tt, *rr, *ss]
        blob = data.astype('<f4').tobytes()
        clip_meta[name] = {'fps': a.fps, 'frames': frames, 'loop': name.endswith('_lp') or name.endswith('lp'), 'joints': [index[x] for x in cj], 'offset': offset}
        clip_blobs.append(blob)
        offset += len(blob)
        print('clip', name, '%.2fs' % dur, frames, 'frames', len(cj), 'joints')

    # physics: springs on joints matching the patterns, capsules along the collider joints
    for spec in a.spring:
        pat, _, vals = spec.partition('=')
        v = [float(x) for x in vals.split(',')] if vals else []
        params = {'stiffness': v[0] if len(v) > 0 else 0.08, 'drag': v[1] if len(v) > 1 else 0.12, 'gravity': v[2] if len(v) > 2 else 6.0}
        if len(v) > 3:
            params['radius'] = v[3]
        hit = 0
        for jt in joints:
            if fnmatch.fnmatchcase(jt['name'], pat):
                jt['spring'] = dict(params)
                hit += 1
        print('spring', pat, params, hit, 'joints')
    colliders = []
    if a.collider:
        vdata_np = np.frombuffer(vertex_data, dtype=np.dtype([('p', '<f4', 3), ('c', 'u1', 4), ('uv', '<f4', 2), ('n', 'i1', 4), ('e', 'u1', 4),
                                                             ('j', 'u1', 4), ('w', 'u1', 4)]))
        dom = vdata_np['j'][np.arange(len(vdata_np)), np.argmax(vdata_np['w'], axis=1)]
        rest_world = []
        for k, jt in enumerate(joints):
            m = np.array(jt['local']).reshape(4, 4).T
            rest_world.append((rest_world[jt['parent']] if jt['parent'] >= 0 else G) @ m)
        for spec in a.collider:
            # joint[>child][=radius]: a capsule from the joint to the child (default: its first ORG- child, else its first
            # child; "joint>" alone = a sphere), radius in rig units (default: from the joint's own vertices)
            spec, _, rad = spec.partition('=')
            name, arrow, child_name = spec.partition('>')
            ji = next(k for k, jt in enumerate(joints) if jt['name'] == name)
            kids = [k for k, jt in enumerate(joints) if jt['parent'] == ji]
            if arrow:
                child = next((k for k in kids if joints[k]['name'] == child_name), None) if child_name else None
            else:
                child = next((k for k in kids if joints[k]['name'].startswith('ORG-')), kids[0] if kids else None)
            to = np.array(joints[child]['local']).reshape(4, 4).T[:3, 3] if child is not None else np.zeros(3)
            if rad:
                radius = float(rad)
            else:
                # 80th percentile distance of the joint's own vertices from the segment (rig units)
                # the mesh is in bind pose: into the joint's space with its inverse bind matrix (not the node's rest)
                inv = np.array(joints[ji]['inverse_bind']).reshape(4, 4).T
                pts = (np.column_stack([vdata_np['p'][dom == ji], np.ones((dom == ji).sum())]) @ inv.T)[:, :3]
                if len(pts) == 0:
                    raise SystemExit('collider %s: no vertices' % name)
                seg = to
                t = np.clip(pts @ seg / max(seg @ seg, 1e-12), 0, 1)
                radius = float(np.percentile(np.linalg.norm(pts - np.outer(t, seg), axis=1), 80))
            colliders.append({'joint': name, 'from': [0, 0, 0], 'to': [round(float(x), 5) for x in to], 'radius': round(radius, 4)})
            print('collider', name, 'radius %.3f (joint space)' % radius)

    if a.anchor_rest == 'bettermodel':
        rest = np.diag([-16.0, 16.0, -16.0, 1.0])
    else:
        rest = np.eye(4)
    rig = {
        'format': 1,
        'bones': [dict({'name': 'anchor', 'item_model': a.anchor, 'rest': colmajor(rest)}, **({'custom_model_data': a.anchor_cmd} if a.anchor_cmd is not None else {}))],
        'geometry': {'data': '%s:remoterenderer/rigs/%s.bin' % (a.ns, a.name), 'vertices': vcount, 'indices': len(index_data) // 4,
                     'vertex_offset': 0, 'index_offset': len(vertex_data), 'submeshes': submeshes},
        'skeleton': {'root': colmajor(G), 'joints': joints},
        'clips': clip_meta,
    }
    if colliders:
        rig['colliders'] = colliders
    if a.link_ring:
        import rig_links
        names = [jt['name'] for jt in joints]
        parents = [jt['parent'] for jt in joints]
        # rest heads in rig space, upright (the node rest pose; the bind pose of a glTF may lie on its back, Z up)
        rw = []
        for jt in joints:
            m = np.array(jt['local']).reshape(4, 4).T
            rw.append((rw[jt['parent']] if jt['parent'] >= 0 else G) @ m)
        heads = [list(w[:3, 3]) for w in rw]
        springs = {k for k, jt in enumerate(joints) if 'spring' in jt}
        links = []
        for spec in a.link_ring:
            pat, _, stiff = spec.partition('=')
            got = rig_links.rings(names, parents, heads, springs, pat, float(stiff) if stiff else 0.6)
            print('link ring', pat, len(got), 'links')
            links += got
        rig['links'] = links
    if a.autoplay:
        rig['autoplay'] = a.autoplay
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('pack.mcmeta', json.dumps({'pack': {'description': 'RemoteRenderer rig: ' + a.name, 'min_format': [97, 0], 'max_format': [2147483647, 0]}}))
        z.writestr('assets/%s/remoterenderer/rigs/%s.json' % (a.ns, a.name), json.dumps(rig))
        z.writestr('assets/%s/remoterenderer/rigs/%s.bin' % (a.ns, a.name), vertex_data + index_data + b''.join(clip_blobs))
        for img_i, ti in tex_index.items():
            png = tex_png[img_i]
            img = Image.open(io.BytesIO(png))
            if a.max_texture and max(img.size) > a.max_texture:
                k = a.max_texture / max(img.size)
                buf = io.BytesIO()
                img.convert('RGBA').resize((max(1, round(img.width * k)), max(1, round(img.height * k))), Image.LANCZOS).save(buf, 'PNG', optimize=True)
                png = buf.getvalue()
            z.writestr(textures[ti]['path'], png)
    print('wrote', out, '| vertices', vcount, 'triangles', len(index_data) // 12, 'joints', len(joints), 'textures', len(textures),
          'clips', len(clip_meta), '| bin %.1f MB' % ((len(vertex_data) + len(index_data) + sum(map(len, clip_blobs))) / 1e6))


def world_of(n, nodes, parent):
    t, r, s = node_trs(nodes[n])
    m = trs(t, r, s)
    p = parent.get(n)
    return world_of(p, nodes, parent) @ m if p is not None else m


if __name__ == '__main__':
    main()
