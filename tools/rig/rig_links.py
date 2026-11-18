# -*- coding: utf-8 -*-
"""Rings of links between neighbouring spring chains (a skirt), shared by rig_gltf.py and rig_bbmodel.py.

The spring joints matching a pattern are grouped by how deep they sit in their chain (the first joint below the hips,
the second, ...). Each group is sorted by its angle around the vertical line through its centre, and each joint is
linked to the next one: the skirt's rings. A ring is closed when the chains go all the way round (no gap above 100
degrees). The client keeps every link at the distance the animation gives, so the chains swing together.
"""
import fnmatch, math


def rings(names, parents, positions, springs, pattern, stiffness=0.6):
    """names / parents (index or -1) / positions (x, y, z rest heads) of all joints, springs: set of spring joint indices."""
    chosen = [i for i in range(len(names)) if i in springs and fnmatch.fnmatchcase(names[i], pattern)]
    chosen_set = set(chosen)
    depth = {}
    for i in chosen:
        d, p = 0, parents[i]
        while p >= 0:
            if p in chosen_set:
                d += 1
            p = parents[p]
        depth[i] = d
    links = []
    for d in sorted(set(depth.values())):
        group = [i for i in chosen if depth[i] == d]
        if len(group) < 2:
            continue
        cx = sum(positions[i][0] for i in group) / len(group)
        cz = sum(positions[i][2] for i in group) / len(group)
        group.sort(key=lambda i: math.atan2(positions[i][2] - cz, positions[i][0] - cx))
        angles = [math.atan2(positions[i][2] - cz, positions[i][0] - cx) for i in group]
        gaps = [(angles[(k + 1) % len(group)] - angles[k]) % (2 * math.pi) for k in range(len(group))]
        closed = max(gaps) < math.radians(100)
        for k in range(len(group) if closed else len(group) - 1):
            a, b = group[k], group[(k + 1) % len(group)]
            links.append({'a': names[a], 'b': names[b], 'stiffness': stiffness})
    return links
