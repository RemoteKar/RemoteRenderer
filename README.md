# RemoteRenderer — GPU rendering for item-display models (Fabric, Minecraft 26.3, client side)

**English** | [한국어](README.ko.md)

<img src="mod/resources/assets/remoterenderer/icon.png" width="96" align="right">

Models made with **BetterModel** (cube and mesh bones) and **objmc** are drawn by item display entities. The vanilla renderer
re-resolves every display's item model and re-transforms every vertex on the CPU, every frame. RemoteRenderer bakes each
distinct model once into a GPU buffer and draws all displays of it with one instanced draw call.

- **GPU instancing** for BetterModel bones / mesh bones, plain item models and objmc models (which are invisible on vanilla 26.3).
- **Rigs** — one resource-pack file per model adds:
  - skinned joints (bending instead of rigid pieces);
  - the real texture (BetterModel draws meshes as flat-coloured triangles);
  - hair / skirt / cape **physics**;
  - client-side animation clips.
  
  See [tools/rig/README.md](tools/rig/README.md).
- **Server item shaders keep working.** When a server pack replaces the item shader (materials, HUDs, decals), RemoteRenderer runs *that* shader instanced.
- **Off-screen culling** (vanilla draws display entities even behind the camera).
- Settings screen (Mod Menu or `/remoterenderer menu`). English, 한국어, 日本語, 简体中文, 繁體中文.
- Targets the latest **BetterModel (3.6.0)**. Nothing changes on the server; players without the mod see vanilla.

## Install

- Minecraft **26.3**, Fabric Loader **0.19+**, Java 25.
- Put `remoterenderer-<version>+26.3.jar` from [Releases](https://github.com/RemoteKar/RemoteRenderer/releases) into `mods/`.
- Only Fabric Loader is required. Fabric API gives the `/remoterenderer` command; Mod Menu gives the config button.
- OpenGL and Vulkan both work (only 26.3's RenderPearl API is used; the shaders are compiled from the jar).
- Tested with vanilla Fabric and with a 266-mod pack (Sodium, Sodium Extra, Iris, Lithium, ModernFix, EntityCulling, MoreCulling, Mod Menu, …).
  When an Iris shader pack is active, the mod steps aside and Iris draws everything.

## Commands (with Fabric API)

| Command | |
|---|---|
| `/remoterenderer` | state and stats (FPS, frame ms, instances, draws, baked models, VRAM, culled, rigs) |
| `/remoterenderer on` / `off` | GPU path on / off (off = fully vanilla, for comparing) |
| `/remoterenderer menu` | settings screen |
| `/remoterenderer stats` | one stats line |
| `/remoterenderer clear` | drop all baked models |
| `/remoterenderer minquads <n>` | only models with at least n solid quads take the GPU path |
| `/remoterenderer rigs` | loaded rigs and their state; `rigs on` / `off`; `rigs play <clip>` previews a clip on every rig that has it |

Config file `config/remoterenderer.properties`, all settings are in the settings screen too:

| Setting | Meaning |
|---|---|
| `enabled` | GPU path on/off |
| `objmc` | decode and draw objmc models |
| `cacheResolve` | reuse a display's resolved model while its item stays the same |
| `cullOffscreen` | skip models and rigs outside the view |
| `adaptCustomItemShaders` | run a pack's own item shader instanced (false: step aside for such packs) |
| `hideWhileBakingQuads` / `hideWhileBakingMs` | heavy new models are hidden for the moment their first bake takes |
| `bakeThreads` | background threads baking new models |
| `rigs`, `rigPhysics` | rigs and their physics |
| `rigTextureMaxSize` | rig textures are scaled down to this when loaded (default 2048) |
| `rigTextureIdleSeconds` | a rig texture nobody used this long leaves the GPU |
| `minQuads`, `evictSeconds`, `statsLogSeconds` | |

## What takes the GPU path

| | |
|---|---|
| BetterModel cube bones, mesh bones (single / double sided triangles), opaque (cutout) quads of any item model | **GPU instancing** |
| objmc models on item displays | **GPU** (decoded once into a data texture; frames, easing and colour controls computed in the vertex shader) |
| quads with translucent pixels | that layer goes the vanilla way (sorting / OIT stay vanilla) |
| glowing displays, special renderers (chests, heads, banners…), enchantment glint | vanilla |
| a pack that replaces `core/item.vsh` / `item.fsh` or their includes | **that pack's shader, instanced** (see below) |
| items in hand, GUI | untouched |

Tints (`custom_model_data` colours, BetterModel's hit flash) are kept.

## Rigs: skinning, real textures, physics

A rig (`assets/<ns>/remoterenderer/rigs/<name>.json` in any resource pack) names the bone displays of one model by their
`item_model`. RemoteRenderer then draws one skinned mesh instead of them. Every bone's pose becomes a joint matrix, and
skinning happens on the GPU.

- **"bones" geometry:** the bones' own models, with the vertices around listed joints shared between two bones. Knees and elbows bend instead of two boxes crossing.
- **Mesh geometry:** any mesh with skin weights, a skeleton, textures outside the atlas and sampled animation clips, made from glTF or a `.bbmodel`. For example, a BetterModel mesh with its real 4096 texture instead of flat-coloured triangles.
- **Physics:** springs on joints (hair, ribbons, skirts, capes), capsule colliders, and links between neighbouring chains so a skirt or cape moves as one piece.
  Declared in the rig, or on the server side next to the model: `_spring` group names or `<model>.rr.json`.
- **Tools:** `tools/rig/rig_bbmodel.py` (BetterModel model → rig), `rig_gltf.py` (glTF → rig), `rig_nightshift.py`.
- **API:** `kr.gcb.remoterenderer.api.RemoteRendererApi` lets client mods register rigs and move joints (look-at, IK, procedural motion).
- **Placement:** BetterModel 3.6.0's bone placement was measured; `tools/rig/bbmodel_rest.py` reproduces it to 4e-7.

## Server item shaders

A pack's item shader reads the same per-vertex inputs vanilla gives every item quad: Position, Color, UV0, UV1, UV2 and Normal.

1. RemoteRenderer takes the pack's vertex shader as it is and removes those input declarations.
2. It turns the pack's `main()` into an ordinary function.
3. It adds a small `main` that fills the same values from the baked model and the instance, or from skinning for rigs.

The fragment shader is the pack's, and the pipeline copies vanilla's item pipeline.

Measured against the vanilla renderer:
- vanilla shader run this way: identical (max 3/255);
- a robot-battle server pack (normal maps, material codes, lighting markers): 2 pixels differ;
- a top-view server whose HUD and minimap are item displays moved on screen by `item.vsh`: identical except 52 pixels at the cursor.

Under such packs, plain displays are not culled, because the shader may draw them somewhere else than where they stand.

## Measured (RTX 5070 Ti, 1280×720, vsync off)

| Scene | Vanilla | RemoteRenderer |
|---|---:|---:|
| 36 BetterModel mesh bones (2304 triangles each), OpenGL | 22 FPS | 1697 FPS |
| same, Vulkan + Sodium etc. | 21 FPS | 3155 FPS |
| ~2600 BetterModel cube-bone displays, OpenGL | 302 FPS | 603 FPS |
| 100 animated objmc models, Vulkan | 784 FPS* | 2923 FPS |
| 24 heavy mesh bones appearing in the same tick: worst frame | 146–169 ms | 14.5–19.7 ms |

\* vanilla 26.3 does not show objmc models at all.

## Limits

- The entities still exist: the server's cost (entities, transform packets) is unchanged; only drawing moves to the GPU.
- Translucent quads go the vanilla way.
- Physics collides at the chain tails: very fast motion can push a cape's surface between two tails into the body. The spring values are the knob.
- A server shader that does not use vanilla's item inputs could not match (the three packs tried do).
- Tested on one NVIDIA GPU (OpenGL and Vulkan).

## Building

`mod/build.py` compiles with plain javac (no Gradle) against:
- an unobfuscated 26.3 client jar (`CLIENT_JAR` / `CLIENT_LIBS` in the script);
- the jars listed in [mod/lib/README.md](mod/lib/README.md).

```
python mod/build.py                       # build/remoterenderer-<version>.jar
python tools/testpack/make_pack.py        # test resource pack
python tests/run_client.py [--off] [--bm] [--vulkan] [--danmo-all] steps...   # real client tests
```

The test tools assume the developer's folder layout (26.3 server / client, BetterModel jars).

## License

MIT. The objmc format is from [Godlander/objmc](https://github.com/Godlander/objmc) (MIT); the decoder here is a new implementation of it.
