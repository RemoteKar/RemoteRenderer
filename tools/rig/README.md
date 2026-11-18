# RemoteRenderer rigs (skinning, real textures, physics)

**English** | [한국어](README.ko.md)

The server keeps sending its bone displays (BetterModel bones, a plugin's own parts). Clients with RemoteRenderer draw
**one skinned mesh** in their place, bent on the GPU. The information comes from a resource-pack file. Players without
the mod see the bone displays as before.

- File: `assets/<namespace>/remoterenderer/rigs/<name>.json` in any resource pack (F3+T reloads it).
- Check: `/remoterenderer rigs` (loaded rigs, state, instances drawn), `rigs on|off`, `rigs play <clip>` (preview).
- From code: `kr.gcb.remoterenderer.api.RemoteRendererApi` (`registerRig`, `addJointHook` to move joints on the client: look-at, IK, physics).

## How it works

1. A display whose item is a bone of a rig (by `item_model`, optionally also the first `custom_model_data` float) is not drawn; only its pose is taken.
2. The bones riding the same entity form one instance (BetterModel puts all bone displays on the model's base entity).
3. The joint matrix of bone b = `display pose × rest_b⁻¹`, where `rest` is that display's transformation in the rest pose.
4. Each vertex blends up to four joints on the GPU (linear blend skinning; joint matrices in a texel buffer).

"Rig space" is the space the display transformations map into (relative to the vehicle). Mesh vertices and `rest` are in it.

## JSON

```json
{
  "format": 1,
  "bones": [
    {"name": "torso", "item_model": "myplugin:human_torso", "pivot": [0, 0.9375, 0]},
    {"name": "root", "item_model": "minecraft:leather_horse_armor", "custom_model_data": 60, "rest": [16 numbers, column major]}
  ],
  "anchor": "torso",
  "hide_bones": true,
  "geometry": "bones",
  "blend": [
    {"parent": "thigh_l", "child": "shin_l", "at": [0.0825, 0.5125, 0.006], "axis": [0, -1, 0],
     "length": 0.05, "radius": 0.095, "side": "both", "falloff": "half"}
  ]
}
```

| Key | Meaning |
|---|---|
| `bones[].item_model` | the `item_model` of the bone display's item |
| `bones[].custom_model_data` | (optional) the first `custom_model_data` float must match too — for plugins that tell bones apart by number |
| `bones[].rest` / `pivot` | the display's transformation in the rest pose (4×4, column major) / `pivot` when it is a translation only |
| `anchor` | bone that gives the instance its light and tint (default: the first) |
| `hide_bones` | false: the bone displays are drawn too (the mesh is added on top) |
| `geometry` | `"bones"` or a mesh file (below) |

### `"geometry": "bones"` — the bones' own models, smooth joints

Every bone's item model is used as it is; only the vertices around the joints listed in `blend` are shared between two bones.
One small file, textures stay in the block atlas.

- `at` joint position, `axis` parent → child direction, `length` half the blended length along the axis, `radius` affected distance from the joint (fading out to 1.5×).
- `side`: `both` / `child` (only the limb's end bends — a jacket over the hips does not tear) / `parent`.
- `falloff`: `smooth` (parts that meet at the joint: past it a vertex belongs to the other bone) / `half` (parts that overlap with rounded ends: each side goes at most half way, so the joint does not pinch).
- With no blends (`"blend": []`) the result is pixel-identical to the bone displays (verified).

### Mesh file — real textures, original weights, client animation

```json
"geometry": {"data": "ns:remoterenderer/rigs/hero.bin", "vertices": 15048, "indices": 62232,
             "vertex_offset": 0, "index_offset": 601920,
             "submeshes": [{"texture": "ns:textures/rig/hero/body.png", "first": 0, "count": 58000, "double_sided": true, "filter": "linear"}]},
"skeleton": {"root": [16], "joints": [{"name": "hips", "parent": -1, "local": [16], "inverse_bind": [16], "bone": "root"}]},
"clips": {"idle": {"fps": 30, "frames": 74, "loop": true, "joints": [3, 4, 9], "offset": 850000}},
"autoplay": "idle"
```

- `.bin` vertex = 40 bytes:
  - position float×3, colour u8×4, UV float×2, normal s8×4;
  - extra u8×4 (x = light emission), joints u8×4, weights u8×4 (sum 255).
  - Indices are u32, little endian.
- **Textures** are plain PNGs under `textures/rig/…`. No atlas collects that folder, so they never grow the block or item atlas.
  - They are decoded and mipped in the background the first time their model is drawn (0.1–0.3 s; that model waits).
  - They leave the GPU after `rigTextureIdleSeconds` unused.
  - They are scaled down to `rigTextureMaxSize` (default 2048: two 4096 textures 171 MB → 43 MB, the same picture at play distance).
- **Joint world matrices:**
  - a joint with `bone` follows that bone display;
  - otherwise parent × local (from the playing clip, or the rest local);
  - roots use anchor × `root` × local.
  - Skin matrix = world × `inverse_bind`. At most 256 joints, parents first.
- **Clips:** frames × joints × (translation 3, rotation quaternion 4, scale 3) floats. A server picks one with the item's `custom_model_data` string `rr:clip=<name>`; otherwise `autoplay` plays.

## Physics (hair, skirts, capes, ribbons)

Only joints the rig declares move; nothing is sent to or from the server. The settings screen has an off switch.

```json
"skeleton": {"joints": [{"name": "Skirt0101.L", "parent": 7, "local": [...], "inverse_bind": [...],
                          "spring": {"stiffness": 0.15, "drag": 0.2, "gravity": 5, "radius": 1.6, "max_angle": 0}}]},
"bones": [{"name": "hair_spring", "item_model": "...", "rest": [...], "parent": "head", "spring": {}}],
"colliders": [{"joint": "ORG-thigh.L", "from": [0, 0, 0], "to": [0, 0.347, 0], "radius": 0.068}],
"links": [{"a": "Skirt0101.L", "b": "Skirt0201.L", "stiffness": 0.6}],
"physics": true
```

| Key | Meaning |
|---|---|
| `stiffness` | share of the way back to the animated pose per 1/60 s (0–1); higher = stiffer |
| `drag` | share of the velocity lost per 1/60 s (0–1); higher = settles faster |
| `gravity` | downward pull, blocks/s² |
| `radius` | size of the tail against colliders (rig units) |
| `tail` | (optional) the tail point in joint space; default: the first child joint, else the farthest vertex the joint moves |
| `max_angle` | (optional) how far the joint may turn away from the animated pose (degrees) |
| `colliders` | capsules (`from`–`to` in a joint's space). A spring whose head already lies inside one (a skirt's first ring at the hips) ignores it |
| `links` | two spring tails keep the distance the animation gives them — neighbouring chains of a skirt or cape move as one piece |

- **Simulation:** all spring tails are one particle system in world space, so it reacts to the model walking, turning and being hit.
  - Every 1/60 s step integrates the particles, then solves the length, link and collider constraints four times.
  - A chain's lower joint hangs from the particle of the one above.
  - Rendering interpolates between the last two steps, so it is smooth at any frame rate.
  - It restarts after 0.5 s undrawn or a jump over 4 blocks.
- **`parent` in a bones rig** makes the bones below a swinging bone follow it; client sway is added on top of the server's bone poses.
- **A server turns it off** for a model with the `custom_model_data` string `rr:physics=off` on a bone item (cut-scenes).

### Long hair, capes

The same spring chains: long hair is a long chain, a cape is several chains side by side, linked.
- **Long hair (glTF):** springs on the back-hair chains, links between them, colliders on head, neck, chest, spine, hips:
  ```
  rig_gltf.py --glb character.glb --anchor ns:anchor_model --anchor-rest identity --scale=-1.125,1.125,1.125 \
    --spring "Bhair*=0.1,0.16,4,0.05" --link-ring "Bhair*=0.5" \
    --collider "ORG-head>" --collider ORG-neck --collider ORG-chest --collider ORG-spine --collider ORG-hips
  ```
  `--collider joint>` is a sphere, `joint>child` a capsule to that child (default: the first `ORG-` child), `=radius` sets the radius (default: from the mesh).
- **Cape (BetterModel cube model, declared on the server side):** see `make_capetest.py`.
  - 3 columns × 4 segment groups `mantle_l1_spring > mantle_l2_spring > …`.
  - `capetest.rr.json` with `"link_rings": ["mantle_*"]` and body/leg capsules.
- **BetterModel reserved names:** a group name starting with a BetterModel tag (`cape`, `h`, `hi`, `b`, `p`, `sp`, `glow`, `li`, `ri`, `seat`, `tag`, … followed by `_`) becomes a special bone. `cape_…` is the player-cape tag and gets no display at all — name capes `mantle_…`.
- **Lengths in `<model>.rr.json` are model pixels**; `from`/`to` are relative to the bone's pivot (the tool converts to joint space).

### Where to declare

- **Resource pack:** the JSON above.
- **Next to the server's model (`rig_bbmodel.py`):** end a group name in `_spring`, or put `<model>.rr.json` beside the `.bbmodel` (BetterModel ignores both):
  `{"springs": {"hair*": {"stiffness": 0.1}}, "link_rings": ["mantle_*"], "colliders": [{"bone": "leg_l", "to": [0,-8,0], "radius": 2}]}`
- **glTF (`rig_gltf.py`):**
  - `--spring "Skirt*=0.15,0.2,5,1.6"` (pattern=stiffness,drag,gravity,radius);
  - `--collider ORG-thigh.L`;
  - `--link-ring "Skirt*=0.6"`: chain joints at the same depth are linked around the upright body; a gap over 100° leaves the ring open.
- **Tuning example:** a skirt split at the front with `0.07,0.14,5,0.5`. With a link ring and `0.15,0.2,5,1.6` it swung as one piece.

## Tools (target: BetterModel 3.6.0)

- **`rig_bbmodel.py <model.bbmodel>`:** a rig straight from a BetterModel model; the server stays as it is.
  - Cube models → a `"bones"` rig, plus automatic blends at limb-like joints.
  - Models with meshes → a mesh rig with the real textures (scaled to `--max-texture`, default 2048, which also keeps the pack small).
  - Mesh UVs are read the way BetterModel reads them, so a model that looks right in BetterModel looks right as a rig.
- **`bbmodel_rest.py`:** where BetterModel puts a bone at rest. It matches BetterModel 3.6.0 to 4e-7, measured with `make_rigprobe.py` (nested bones rotated on all axes):
  `display transformation = up(-passenger height) · Y180 · S(1/16) · W_b · T(pivot) · S(16) · Y180⁻¹`, where `W_b` = for every group `T(origin)·Rz·Ry·Rx·T(-origin)`.
- **`rig_gltf.py`:** a skinned glTF (.glb) → mesh rig with its textures, original weights, skeleton and sampled clips.
  - Anchor it to a BetterModel bone with `--anchor bettermodel:model/<model>_<bone>`; that bone's `rest` is scale(16)·rotateY(180).
- **`rig_nightshift.py`:** rigs for a plugin's mesh people (8 parts, knee / hip / shoulder / neck blends); `--no-blend` makes a rigid rig for checking.
- **`rig_links.py`:** the link rings shared by the two tools.
