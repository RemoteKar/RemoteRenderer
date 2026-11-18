# -*- coding: utf-8 -*-
"""Where BetterModel puts a bone at rest - the one thing a rig for a BetterModel model has to know.

Measured on BetterModel 3.6.0 (and 3.5.1 for a root bone) with build/bm/rigprobe.bbmodel and -Dremoterenderer.dump:
  - a bone's item model holds its elements at (v - pivot) / 16 (bbmodel pixels -> blocks, no flip);
  - its display transformation at rest = up(-h) * Y180 * S(1/16) * W_b * T(pivot_b) * S(16) * Y180^-1, where W_b is the
    Blockbench rest pose of the bone (each group: T(origin) * Rz * Ry * Rx * T(-origin), parents first) and h the base
    entity's passenger height (0.86875 for a pig) - and ItemDisplayRenderer turns the model half way round once more.

A rig made from a .bbmodel works in "bbmodel space" (Blockbench's pixels, rest pose); then a bone's rest is
W_b * T(pivot_b) * S(16) * Y180 (the up(-h) is common to all bones of a model and cancels out).
  python tools/rig/bbmodel_rest.py <client.log>   compares the RR_DUMP lines of rigprobe with this formula
"""
import json, re, sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]


def rx(d):
    c, s = np.cos(np.radians(d)), np.sin(np.radians(d))
    return np.array([[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]])


def ry(d):
    c, s = np.cos(np.radians(d)), np.sin(np.radians(d))
    return np.array([[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]])


def rz(d):
    c, s = np.cos(np.radians(d)), np.sin(np.radians(d))
    return np.array([[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]])


def T(v):
    m = np.eye(4)
    m[:3, 3] = v
    return m


def S(k):
    return np.diag([k, k, k, 1.0])


Y180 = ry(180)


def euler(r):
    """Blockbench group rotation (degrees): ZYX order, X applied first."""
    return rz(r[2]) @ ry(r[1]) @ rx(r[0])


def groups(model):
    """{name: (origin, rotation, parent name)} for Blockbench 4 (inline) and 5 (separate "groups") files."""
    out = {}
    by_uuid = {g['uuid']: g for g in model.get('groups', [])} if isinstance(model.get('groups'), list) else {}

    def walk(node, parent):
        if isinstance(node, str):
            return
        g = by_uuid.get(node.get('uuid'), node)
        name = g.get('name', node.get('name'))
        out[name] = (np.array(g.get('origin', [0, 0, 0]), float), np.array(g.get('rotation', [0, 0, 0]), float), parent)
        for c in node.get('children', []):
            walk(c, name)
    for n in model['outliner']:
        walk(n, None)
    return out


def world(gs, name):
    """W_b: Blockbench's rest pose of the bone (stored coordinates -> shown coordinates)."""
    o, r, p = gs[name]
    m = T(o) @ euler(r) @ T(-o)
    return (world(gs, p) @ m) if p else m


def rest(gs, name):
    """The bone's rest in bbmodel space (pixels): see the module comment."""
    o = gs[name][0]
    return world(gs, name) @ T(o) @ S(16) @ Y180


def display_at_rest(gs, name, h):
    o = gs[name][0]
    return T([0, -h, 0]) @ Y180 @ S(1 / 16) @ world(gs, name) @ T(o) @ S(16) @ np.linalg.inv(Y180)


def main():
    log = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/client.log'
    model = json.loads((ROOT / 'build/bm/rigprobe.bbmodel').read_text(encoding='utf-8'))
    gs = groups(model)
    worst = 0.0
    for m in re.finditer(r'RR_DUMP bettermodel:model/rigprobe_(\w+) .*?transform\(rowmajor\)=\[([^\]]+)\]', log.read_text(encoding='utf-8', errors='replace')):
        name = m.group(1)
        got = np.array([float(x) for x in m.group(2).replace(' ', '').split(',')]).reshape(3, 4)
        want = display_at_rest(gs, name, 0.86875)[:3]
        err = np.abs(got - want).max()
        worst = max(worst, err)
        print('%-5s max error %.6f' % (name, err))
    print('worst', worst, 'OK' if worst < 1e-4 else 'MISMATCH')


if __name__ == '__main__':
    main()
