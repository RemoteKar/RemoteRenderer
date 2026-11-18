# -*- coding: utf-8 -*-
"""A small encoder for objmc's texture format, written for the RemoteRenderer tests.

It produces the same layout as objmc.py (https://github.com/Godlander/objmc, MIT, see
tools/third_party/objmc/LICENSE): header on row 0, one uv header pixel per face, the texture (flipped), the
position table (24 bit fixed point, +128 block offset), the uv table (16 bit), the vertex table (position
index + uv index, or "compressed" with the uv index in alpha) - and the item model of dummy faces whose uvs
point at the header pixels.
"""
import math
from PIL import Image, ImageOps

BEHAVIOURS = ['pitch', 'yaw', 'roll', 'time', 'scale', 'overlay', 'hurt']


def encode(frames, texture, duration=0, easing=3, interpolation=1, autoplay=True, autorotate=1, colorbehavior=('pitch', 'yaw', 'roll'),
           noshadow=False, visibility=7, compression=None, flipuv=False, scale=1.0, offset=(0.0, 0.0, 0.0)):
    """frames: list of {'positions': [(x,y,z)], 'uvs': [(u,v)], 'faces': [[(pi, ui), ...] 3 or 4 corners]} (0-based).
    Returns (model_json, PIL image)."""
    positions, uvs, vertices = [], [], []
    pos_index, uv_index = {}, {}

    def index(frame, corner):
        p = frame['positions'][corner[0]]
        u = frame['uvs'][corner[1]]
        if p not in pos_index:
            pos_index[p] = len(positions)
            positions.append(p)
        if u not in uv_index:
            uv_index[u] = len(uvs)
            uvs.append(u)
        vertices.append((pos_index[p], uv_index[u]))

    nfaces = len(frames[0]['faces'])
    for frame in frames:
        if len(frame['faces']) != nfaces:
            raise ValueError('every frame needs the same faces')
        for face in frame['faces']:
            for corner in face[:4]:
                index(frame, corner)
            if len(face) == 3:
                index(frame, face[1])

    nframes = len(frames)
    if duration == 0:
        duration = nframes
    x, y = texture.size
    nvertices = nfaces * 4
    uvheight = math.ceil(nfaces / x)
    vpheight = math.ceil(len(positions) * 3 / x)
    vtheight = math.ceil(len(uvs) * 2 / x)
    compressed = len(uvs) <= 255 if compression is None else compression
    vheight = math.ceil(len(vertices) * (1 if compressed else 2) / x)
    ty = 1 + uvheight + y + vpheight + vtheight + vheight
    cb = (BEHAVIOURS.index(colorbehavior[0]) << 6) + (BEHAVIOURS.index(colorbehavior[1]) << 3) + BEHAVIOURS.index(colorbehavior[2])

    out = Image.new('RGBA', (x, ty), (0, 0, 0, 0))
    out.putpixel((0, 0), (12, 34, 56, 79 if compressed else 78))
    out.putpixel((1, 0), (x // 256, x % 256, y // 256, y % 256))
    out.putpixel((2, 0), ((nvertices >> 24) % 256, (nvertices >> 16) % 256, (nvertices >> 8) % 256, nvertices % 256))
    out.putpixel((3, 0), ((nframes >> 16) % 256, (nframes >> 8) % 256, nframes % 256, 1))
    out.putpixel((4, 0), ((duration >> 16) % 256, (duration >> 8) % 256, duration % 256, 128 + (int(autoplay) << 6) + (easing << 4) + (interpolation << 2)))
    out.putpixel((5, 0), (vpheight // 256, vpheight % 256, vtheight // 256, vtheight % 256))
    out.putpixel((6, 0), ((int(noshadow) << 7) + (autorotate << 5) + (visibility << 2) + cb // 256, cb % 256, 255, 255))
    out.paste(texture if flipuv else ImageOps.flip(texture), (0, 1 + uvheight))

    elements = []
    for i in range(nfaces):
        px, py = i % x, i // x + 1
        out.putpixel((px, py), (px // 256, px % 256, py // 256, py % 256))
        elements.append({'from': [8, 0, 8], 'to': [24, 16, 8], 'faces': {'north': {
            'uv': [(px + 0.1) * 16 / x, (py + 0.1) * 16 / ty, (px + 0.9) * 16 / x, (py + 0.9) * 16 / ty], 'texture': '#0', 'tintindex': 0}}})

    row = 1 + uvheight + y
    for i, p in enumerate(positions):
        for j in range(3):
            v = int(8388608 + p[j] * 65536 * scale + offset[j] * 65536)
            k = i * 3 + j
            out.putpixel((k % x, row + k // x), ((v >> 16) % 256, (v >> 8) % 256, v % 256, 255))
    row += vpheight
    for i, u in enumerate(uvs):
        for j in range(2):
            v = int(u[j] * 65535)
            k = i * 2 + j
            out.putpixel((k % x, row + k // x), ((v >> 16) % 256, (v >> 8) % 256, v % 256, 255))
    row += vtheight
    for i, (pi, ui) in enumerate(vertices):
        if compressed:
            out.putpixel((i % x, row + i // x), ((pi >> 16) % 256, (pi >> 8) % 256, pi % 256, ui % 256 + 1))
        else:
            for j, value in enumerate((pi, ui)):
                k = i * 2 + j
                out.putpixel((k % x, row + k // x), ((value >> 16) % 256, (value >> 8) % 256, value % 256, 255))

    model = {'textures': {'0': 'objmc_out'}, 'elements': elements,
             'display': {'thirdperson_righthand': {'rotation': [85, 0, 0]}, 'thirdperson_lefthand': {'rotation': [85, 0, 0]}}}
    return model, out
