# -*- coding: utf-8 -*-
"""Real-client test for RemoteRenderer.

Starts a vanilla 26.3 server (build/testserver, offline mode, flat world, port RR_PORT=25671) and a Fabric 26.3
client (build/client-game) with Fabric API + RemoteRenderer and the test pack (tools/testpack/make_pack.py), joins,
runs the steps and keeps the client log in build/client.log.

  python tests/run_client.py [--off] [--no-mod] [--danmo] [--vulkan] [--focus] steps...

--off      RemoteRenderer installed but disabled (-Dremoterenderer.enabled=false): the vanilla renderer draws
--no-mod   RemoteRenderer not installed at all
--danmo    also the render mods of the user's Danmo263 instance (Sodium, Sodium Extra, Iris, EntityCulling, MoreCulling)
--vulkan   the client's Vulkan backend (default OpenGL)

Steps: a server console command; "@N" wait N seconds; "shot:<name>" screenshot into build/shots;
"cexpect:<regex>" wait for a client log line; "scene:row" / "scene:grid:<n>:<model>[:spin]" / "scene:clear"
(item displays summoned by console); "view:<x>:<y>:<z>:<yaw>:<pitch>" puts the player there (spectator).
"""
import hashlib, json, os, queue, re, shutil, struct, subprocess, sys, threading, time, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GCB = ROOT.parent
JAVA = GCB / 'Java/Java_25/bin/java.exe'
SRC_CLIENT = GCB / 'BrawlStars/build/client'
CLIENT_JAR = GCB / 'BrawlStars/build/minecraft-26.3-client.jar'
VERSION_JSON = GCB / 'NerfWar/build/26.3.json'
SERVER_JAR = Path.home() / '.gradle/caches/fabric-loom/26.3/minecraft-server.jar'
DANMO = Path(os.environ.get('RR_DANMO', str(GCB.parent / 'ATLauncher/instances/Danmo263')))
SERVER = ROOT / 'build/testserver'
GAME = Path(os.environ.get('RR_GAME_DIR', str(ROOT / 'build/client-game')))
SHOTS = ROOT / 'build/shots'
PACK = ROOT / 'build/dm-testpack.zip'
PORT = int(os.environ.get('RR_PORT', '25671'))
NAME = 'DmTester'

flags = {a for a in sys.argv[1:] if a.startswith('--')}
steps = [a for a in sys.argv[1:] if not a.startswith('--')]
SHOTS.mkdir(parents=True, exist_ok=True)

# --bm: DivineMC 26.3 + BetterModel (latest release, build/bm36; RR_BM_JAR for another), models build/bm/*.bbmodel;
# the client gets the pack BetterModel builds itself
BM = '--bm' in flags
if BM:
    SERVER = ROOT / 'build/bmserver'
    PORT = int(os.environ.get('RR_BM_PORT', '25672'))
# --tv: a copy of the TopViewDemo server (the ragdolls: Jolt physics + BetterModel player limbs, BetterModel creatures);
# its own pack comes from the server, hooks via "tv test DmTester ..."
TV = '--tv' in flags
if TV:
    SERVER = ROOT / 'build/tvserver'
    PORT = int(os.environ.get('RR_TV_PORT', '25673'))
TOPVIEW = GCB / 'TopViewDemo'
DIVINEMC = GCB / 'TopViewDemo'
BM_PLUGIN = GCB / 'CobbleMon/plugins'


def prepare_bm_server():
    SERVER.mkdir(parents=True, exist_ok=True)
    if not (SERVER / 'divinemc-26.3-5.jar').exists():
        shutil.copy(DIVINEMC / 'divinemc-26.3-5.jar', SERVER)
        for d in ('cache', 'libraries', 'versions'):
            shutil.copytree(DIVINEMC / d, SERVER / d)
    plugins = SERVER / 'plugins'
    (plugins / 'BetterModel/models').mkdir(parents=True, exist_ok=True)
    # the latest BetterModel by default (build/bm36, from the GitHub release); RR_BM_JAR picks another build
    bm_jar = Path(os.environ.get('RR_BM_JAR', str(ROOT / 'build/bm36/bettermodel-3.6.0-paper.jar')))
    shutil.copy(bm_jar, plugins / 'BetterModel.jar')
    for extra in (ROOT / 'build/bm').glob('*.bbmodel'):
        shutil.copy(extra, plugins / 'BetterModel/models' / extra.name)
    if not (plugins / 'BetterModel/.libs').exists():
        shutil.copytree(BM_PLUGIN / 'BetterModel/.libs', plugins / 'BetterModel/.libs')
    cfg = (BM_PLUGIN / 'BetterModel/config.yml').read_text(encoding='utf-8')
    for key, value in (('player-animation', 'false'), ('player-armor', 'false'), ('sight-trace', 'false'), ('metrics', 'false'),
                       ('version-check', 'false'), ('use-purpur-afk', 'false'), ('follow-mob-invisibility', 'false')):
        cfg = re.sub(rf'(?m)^(\s*){key}:.*$', rf'\g<1>{key}: {value}', cfg)
    (plugins / 'BetterModel/config.yml').write_text(cfg, encoding='utf-8')
    shutil.copy(ROOT / 'build/bm/meshtest.bbmodel', plugins / 'BetterModel/models/meshtest.bbmodel')
    # real Pokemon models of the CobbleMon server (cube bones) for the hit flash test
    for pk in ('pk_abomasnow', 'pk_charizard'):
        shutil.copy(BM_PLUGIN / f'BetterModel/models/pokemon/{pk}.bbmodel', plugins / f'BetterModel/models/{pk}.bbmodel')
    probe = ROOT / 'build/DmProbe.jar'
    if probe.exists():
        shutil.copy(probe, plugins / 'DmProbe.jar')
    (plugins / 'BetterModel/build.zip').unlink(missing_ok=True)
    (SERVER / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (SERVER / 'server.properties').write_text('\n'.join([
        'online-mode=false', f'server-port={PORT}', 'level-type=minecraft\\:flat', 'generate-structures=false', 'spawn-protection=0',
        'gamemode=spectator', 'force-gamemode=true', 'difficulty=peaceful', 'view-distance=8', 'simulation-distance=6', 'motd=RemoteRenderer BM test',
        'enforce-secure-profile=false', 'white-list=false', 'enforce-whitelist=false', 'spawn-monsters=false', 'allow-flight=true',
    ]) + '\n', encoding='utf-8')
    for w in ('world', 'world_nether', 'world_the_end'):
        if (SERVER / w).exists():
            shutil.rmtree(SERVER / w)


def prepare_tv_server():
    if not (SERVER / 'divinemc-26.3-5.jar').exists():
        # read-only copy of the user's TopViewDemo server (never run in its own folder)
        shutil.copytree(TOPVIEW, SERVER, ignore=shutil.ignore_patterns('build', 'logs', 'crash-reports', 'plugin-src', 'tools', 'docs', 'tests',
                                                                       'session.lock', '*.log'))
        pending = SERVER / 'plugins/TopView.jar.pending'
        if pending.exists():
            pending.replace(SERVER / 'plugins/TopView.jar')
    props = (SERVER / 'server.properties').read_text(encoding='utf-8')
    props = re.sub(r'(?m)^server-port=.*$', f'server-port={PORT}', props)
    props = re.sub(r'(?m)^online-mode=.*$', 'online-mode=false', props)
    (SERVER / 'server.properties').write_text(props, encoding='utf-8')


def prepare_server():
    if BM:
        prepare_bm_server()
        return
    if TV:
        prepare_tv_server()
        return
    SERVER.mkdir(parents=True, exist_ok=True)
    if not (SERVER / 'server.jar').exists():
        shutil.copy(SERVER_JAR, SERVER / 'server.jar')
    (SERVER / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (SERVER / 'server.properties').write_text('\n'.join([
        'online-mode=false', f'server-port={PORT}', 'level-type=minecraft\\:flat', 'generate-structures=false', 'spawn-protection=0',
        'gamemode=spectator', 'force-gamemode=true', 'difficulty=peaceful', 'view-distance=8', 'simulation-distance=6', 'motd=RemoteRenderer test',
        'enforce-secure-profile=false', 'white-list=false', 'enforce-whitelist=false', 'spawn-monsters=false', 'allow-flight=true', 'sync-chunk-writes=false',
    ]) + '\n', encoding='utf-8')
    # a fresh flat world every run: displays left in unloaded chunks would survive a kill command
    if (SERVER / 'world').exists():
        shutil.rmtree(SERVER / 'world')
    pack = SERVER / 'world/datapacks/dmtest'
    (pack / 'data/dmtest/function').mkdir(parents=True, exist_ok=True)
    (pack / 'data/minecraft/tags/function').mkdir(parents=True, exist_ok=True)
    (pack / 'pack.mcmeta').write_text(json.dumps({'pack': {'description': 'dm test', 'min_format': 121, 'max_format': 121}}), encoding='utf-8')
    # BetterModel-like motion: every display tagged "spin" turns a little each tick (client-side interpolated)
    (pack / 'data/dmtest/function/tick.mcfunction').write_text('execute as @e[type=minecraft:item_display,tag=spin] at @s run rotate @s ~6 ~\n', encoding='utf-8')
    (pack / 'data/minecraft/tags/function/tick.json').write_text(json.dumps({'values': ['dmtest:tick']}), encoding='utf-8')


def prepare_client():
    GAME.mkdir(parents=True, exist_ok=True)
    mods = GAME / 'mods'
    if mods.exists():
        shutil.rmtree(mods)
    mods.mkdir()
    shutil.copy(DANMO / 'mods/fabric-api-0.163.0+26.3.jar', mods)
    if '--no-mod' not in flags:
        jars = [Path(os.environ['RR_MOD_JAR'])] if os.environ.get('RR_MOD_JAR') else sorted((ROOT / 'build').glob('remoterenderer-*.jar'))
        shutil.copy(jars[-1], mods)
    if '--danmo-all' in flags:
        # every mod and config of the user's Danmo263 instance, copied (the instance itself is only read)
        for j in (DANMO / 'mods').glob('*.jar'):
            if not j.name.startswith('fabric-api-0.163.0'):
                shutil.copy(j, mods)
        if (GAME / 'config').exists():
            shutil.rmtree(GAME / 'config')
        shutil.copytree(DANMO / 'config', GAME / 'config')
    elif '--danmo' in flags:
        for pat in ('sodium-fabric-*.jar', 'sodium-extra-fabric-*.jar', 'iris-fabric-*.jar', 'entityculling-fabric-*.jar', 'moreculling-fabric-*.jar',
                    'reeses-sodium-options-*.jar', 'cloth-config-*.jar'):
            for j in (DANMO / 'mods').glob(pat):
                shutil.copy(j, mods)
    iris_cfg = GAME / 'config/iris.properties'
    if os.environ.get('RR_SHADERPACK'):
        # a real shader pack (zip) for Iris: RR_SHADERPACK=<path>
        sp = Path(os.environ['RR_SHADERPACK'])
        (GAME / 'shaderpacks').mkdir(exist_ok=True)
        shutil.copy(sp, GAME / 'shaderpacks' / sp.name)
        iris_cfg.parent.mkdir(parents=True, exist_ok=True)
        iris_cfg.write_text('enableShaders=true\nshaderPack=%s\nmaxShadowRenderDistance=32\n' % sp.name, encoding='utf-8')
    elif '--iris-pack' in flags:
        # a minimal shader pack (no programs): enough for Iris to report "shader pack in use"
        import zipfile
        (GAME / 'shaderpacks').mkdir(exist_ok=True)
        with zipfile.ZipFile(GAME / 'shaderpacks/dm-minimal.zip', 'w') as z:
            z.writestr('shaders/shaders.properties', '# RemoteRenderer test: no programs\n')
        iris_cfg.parent.mkdir(parents=True, exist_ok=True)
        iris_cfg.write_text('enableShaders=true\nshaderPack=dm-minimal.zip\n', encoding='utf-8')
    elif iris_cfg.exists():
        iris_cfg.unlink()
    (GAME / 'resourcepacks').mkdir(exist_ok=True)
    shutil.copy(PACK, GAME / 'resourcepacks/dm-testpack.zip')
    extra = [Path(x) for x in os.environ.get('RR_EXTRA_PACKS', '').split(',') if x]
    for x in extra:
        shutil.copy(x, GAME / 'resourcepacks' / x.name)
    opts = (SRC_CLIENT / 'game/options.txt').read_text(encoding='utf-8')
    rep = {
        'resourcePacks': json.dumps(['vanilla'] + ([] if TV else ['file/dm-testpack.zip']) + ['file/' + x.name for x in extra], separators=(',', ':')), 'enableVsync': 'false', 'maxFps': '260', 'inactivityFpsLimit': '"minimized"',
        'renderDistance': '8', 'preferredGraphicsBackend': '"vulkan"' if '--vulkan' in flags else '"opengl"', 'fullscreen': 'false',
        'overrideWidth': os.environ.get('RR_W', '1280'), 'overrideHeight': os.environ.get('RR_H', '720'), 'pauseOnLostFocus': 'false',
        'hideGui': 'false',
        'lang': os.environ.get('RR_LANG', 'en_us'),
    }
    for k, v in rep.items():
        if re.search(rf'(?m)^{k}:', opts):
            opts = re.sub(rf'(?m)^{k}:.*$', f'{k}:{v}', opts)
        else:
            opts += f'\n{k}:{v}'
    (GAME / 'options.txt').write_text(opts, encoding='utf-8', newline='\n')
    cfg = GAME / 'config/remoterenderer.properties'
    cfg.unlink(missing_ok=True)

    def nbt_str(s):
        b = s.encode('utf-8')
        return struct.pack('>H', len(b)) + b
    entry = bytes([8]) + nbt_str('ip') + nbt_str('localhost:%d' % PORT) + bytes([8]) + nbt_str('name') + nbt_str('DM test') + bytes([1]) + nbt_str('acceptTextures') + bytes([1]) + bytes([0])
    (GAME / 'servers.dat').write_bytes(bytes([10]) + nbt_str('') + bytes([9]) + nbt_str('servers') + bytes([10]) + struct.pack('>i', 1) + entry + bytes([0]))


MODELS = ['dmtest:torus', 'dmtest:sphere', 'dmtest:flag', 'dmtest:objmc_twist', 'dmtest:objmc_spin']


def summon(x, y, z, model, tags=('dm',), color=None, scale=1.0, extra=''):
    comps = '"minecraft:item_model":"%s"' % model
    if color is not None:
        comps += ',"minecraft:custom_model_data":{colors:[%d]}' % color
    tagstr = ','.join('"%s"' % t for t in tags)
    tr = ''
    if scale != 1.0:
        tr = ',transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[%sf,%sf,%sf]}' % (scale, scale, scale)
    return 'summon minecraft:item_display %.2f %.2f %.2f {Tags:[%s],view_range:4f,item:{id:"minecraft:stick",count:1,components:{%s}}%s%s}' % (x, y, z, tagstr, comps, tr, extra)


def bone_models():
    import zipfile
    with zipfile.ZipFile(PACK) as z:
        return sorted('bettermodel:model/' + Path(n).stem for n in z.namelist() if n.startswith('assets/bettermodel/items/model/pk_abomasnow'))


def scene(spec):
    parts = spec.split(':')
    cmds = []
    if parts[0] == 'clear':
        cmds.append('kill @e[type=minecraft:item_display]')
    elif parts[0] == 'row':
        # every kind side by side, 2.5 blocks apart, along +x at z = 6
        for i, m in enumerate(MODELS):
            color = 0x204060 if m.endswith('objmc_spin') else None
            cmds.append(summon(i * 2.5, -59.0 if 'objmc' in m else -60.0, 6.0, m, color=color))
        for i, m in enumerate(bone_models()):
            cmds.append(summon(len(MODELS) * 2.5 + 1.0 + (i % 8) * 0.9, -60.0 + (i // 8) * 0.9, 6.0, m))
    elif parts[0] == 'bm':
        # BetterModel content only (both renderers can draw it): meshes + the cube-bone set, close together
        for i, m in enumerate(['dmtest:torus', 'dmtest:sphere', 'dmtest:flag']):
            cmds.append(summon(i * 2.4, -60.0, 6.0, m))
        for i, m in enumerate(bone_models()):
            cmds.append(summon(-2.6 - (i % 6) * 0.8, -60.0 + (i // 6) * 0.8, 6.0, m))
    elif parts[0] == 'mixed':
        # item_display contexts (layer transforms, left hand), tints, a half-clear texture, a glowing display
        head = 'bettermodel:model/pk_abomasnow_mega_head'
        for i, ctx in enumerate(['none', 'fixed', 'head', 'ground', 'thirdperson_lefthand', 'thirdperson_righthand', 'gui']):
            cmds.append(summon(i * 1.4 - 4.2, -59.5, 6.0, head, extra=',item_display:"%s"' % ctx))
        cmds.append(summon(-4.2, -60.0, 4.0, head, color=0xFF3030))
        cmds.append(summon(-2.4, -60.0, 4.0, head, color=0x30FF30))
        for i in range(3):
            cmds.append(summon(-0.4 + i * 1.2, -60.0, 4.0, 'dmtest:glassbox', extra=',transformation:{left_rotation:[0f,0.38f,0f,0.92f],right_rotation:[0f,0f,0f,1f],translation:[0f,0.5f,0f],scale:[1f,1f,1f]}'))
        cmds.append(summon(3.6, -60.0, 4.0, 'dmtest:sphere', extra=',Glowing:1b'))
        cmds.append(summon(5.2, -60.0, 4.0, 'dmtest:glassbox', extra=',brightness:{block:15,sky:15}'))
    elif parts[0] == 'vanilla':
        # ordinary items: block atlas, tint sources, translucent, special renderers (vanilla path), foil (vanilla path)
        items = ['stone', 'grass_block', 'oak_leaves', 'glass', 'chest', 'diamond_sword', 'potion', 'leather_horse_armor', 'player_head',
                 'shield', 'oak_stairs', 'red_stained_glass_pane']
        comps = {'diamond_sword': '"minecraft:enchantments":{"minecraft:sharpness":1}', 'potion': '"minecraft:potion_contents":{potion:"minecraft:healing"}',
                 'leather_horse_armor': '"minecraft:dyed_color":3381759'}
        for i, it in enumerate(items):
            c = comps.get(it, '')
            cmds.append('summon minecraft:item_display %.2f -59.50 %.2f {Tags:["dm"],item:{id:"minecraft:%s",count:1%s}}'
                        % ((i % 6) * 1.3 - 3.2, 6.0 + (i // 6) * 1.5, it, (',components:{%s}' % c) if c else ''))
    elif parts[0] == 'objmc':
        # objmc models next to a BetterModel torus, up in the air so colour rotations stay above the ground
        cmds.append(summon(0.0, -59.0, 5.0, 'dmtest:objmc_twist'))
        cmds.append(summon(2.2, -59.0, 5.0, 'dmtest:objmc_spin', color=0x204060))
        cmds.append(summon(4.4, -59.0, 5.0, 'dmtest:objmc_spin', color=0x000000))
        cmds.append(summon(-2.4, -60.0, 5.0, 'dmtest:torus'))
    elif parts[0] == 'grid':
        n, model = int(parts[1]), 'dmtest:' + parts[2]
        spin = len(parts) > 3 and parts[3] == 'spin'
        tags = ('dm', 'spin') if spin else ('dm',)
        for i in range(n):
            for j in range(n):
                color = (i * 37 + j * 91) * 2654435761 % 0xFFFFFF if 'objmc_spin' in model else None
                cmds.append(summon(i * 2.2 - n * 1.1, -60.0, 4.0 + j * 2.2, model, tags=tags, color=color))
    elif parts[0] == 'burst':
        # six different heavy meshes x 4, all appearing in the same tick (the first-appearance stall test)
        for k in range(6):
            for c in range(4):
                cmds.append(summon((k - 2.5) * 2.4, -60.0, 5.0 + c * 2.4, 'dmtest:torus_%d' % k, tags=('dm', 'spin')))
    elif parts[0] == 'bones':
        # n x n copies of the whole BetterModel bone set
        n = int(parts[1])
        spin = len(parts) > 2 and parts[2] == 'spin'
        tags = ('dm', 'spin') if spin else ('dm',)
        bones = bone_models()
        for i in range(n):
            for j in range(n):
                for b in bones:
                    cmds.append(summon(i * 2.5 - n * 1.25, -60.0, 4.0 + j * 2.5, b, tags=tags))
    elif parts[0] == 'figure':
        # a NightShift person (Figure.java): a root display carrying 8 part displays, posed like Figure.pose
        # figure:<look>:<x>:<z>:<thigh deg>:<knee deg>:<arm deg>[:<yaw deg>[:<tag>]]
        look, x, z = parts[1], float(parts[2]), float(parts[3])
        thigh, knee, arm = float(parts[4]), float(parts[5]), float(parts[6])
        yaw = float(parts[7]) if len(parts) > 7 else 0.0
        tag = parts[8] if len(parts) > 8 else 'fig'
        cmds.append(figure_cmd(look, x, -60.0, z, figure_pose(thigh, knee, arm, yaw), tag))
    elif parts[0] == 'figpose':
        # figpose:<tag>:<thigh>:<knee>:<arm>:<yaw>:<ticks> - move a figure to a new pose, interpolated over ticks
        tag = parts[1]
        pose = figure_pose(float(parts[2]), float(parts[3]), float(parts[4]), float(parts[5]))
        ticks = int(parts[6])
        for part, m in pose.items():
            cmds.append('data merge entity @e[type=minecraft:item_display,tag=%s_%s,limit=1] {start_interpolation:0,interpolation_duration:%d,transformation:%s}'
                        % (tag, part, ticks, mat_nbt(m)))
    return cmds


FIG_PIVOTS = None


def figure_pose(thigh, knee, arm, yaw=0.0, lean=0.0):
    """Figure.pose with a fixed walk phase: left thigh/knee and right arm get the given angles, the others mirrored."""
    import numpy as np
    global FIG_PIVOTS
    if FIG_PIVOTS is None:
        FIG_PIVOTS = json.loads((GCB / 'Test/plugin-src/resources/rig.json').read_text(encoding='utf-8'))['pivots']
    P = {k: np.array(v, float) for k, v in FIG_PIVOTS.items()}
    D = np.pi / 180

    def T(v):
        m = np.eye(4)
        m[:3, 3] = v
        return m

    def R(axis, deg):
        c, s = np.cos(deg * D), np.sin(deg * D)
        m = np.eye(4)
        i, j = {'x': (1, 2), 'y': (2, 0), 'z': (0, 1)}[axis]
        m[i, i], m[i, j], m[j, i], m[j, j] = c, -s, s, c
        return m
    base = R('y', -yaw)
    out = {'torso': base @ T(P['torso']) @ R('x', lean), 'head': base @ T(P['head'])}
    out['arm_l'] = base @ T(P['arm_l']) @ R('x', -arm - 2) @ R('z', 3)
    out['arm_r'] = base @ T(P['arm_r']) @ R('x', arm - 2) @ R('z', -3)
    for side, th, kn in (('l', thigh, knee), ('r', -thigh * 0.3, 3.0)):
        hip, kp = P['thigh_' + side], P['shin_' + side]
        t = base @ T(hip) @ R('x', th)
        out['thigh_' + side] = t
        out['shin_' + side] = t @ T(kp - hip) @ R('x', kn)
    return out


def mat_nbt(m):
    return '[' + ','.join('%.6ff' % v for v in m.reshape(-1)) + ']'


def figure_cmd(look, x, y, z, pose, tag):
    passengers = []
    for part, m in pose.items():
        passengers.append('{id:"minecraft:item_display",Tags:["%s","%s_%s"],view_range:4f,item_display:"none",interpolation_duration:2,'
                          'item:{id:"minecraft:paper",count:1,components:{"minecraft:item_model":"nightshift:h_%s_%s"}},transformation:%s}'
                          % (tag, tag, part, look, part, mat_nbt(m)))
    return 'summon minecraft:item_display %.2f %.2f %.2f {Tags:["%s","%s_root"],Passengers:[%s]}' % (x, y, z, tag, tag, ','.join(passengers))


def main():
    prepare_server()
    prepare_client()
    server_cmd = [str(JAVA), '-Xms1G', '-Xmx3G', '-jar', 'server.jar', '--nogui']
    if BM or TV:
        server_cmd = [str(JAVA), '-Xms1G', '-Xmx3G', '--add-modules=jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED', '-Dfile.encoding=UTF-8',
                      '-Dstdout.encoding=UTF-8', '-jar', 'divinemc-26.3-5.jar', '--nogui']
    server = subprocess.Popen(server_cmd, cwd=SERVER, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    lines = queue.Queue()
    slog = open(ROOT / 'build/server.log', 'w', encoding='utf-8')

    def pump():
        for l in server.stdout:
            slog.write(l)
            slog.flush()
            lines.put(l)
            if re.search(r'Exception|ERROR|joined the game|left the game', l):
                print('[server]', l.rstrip()[:240], flush=True)
    threading.Thread(target=pump, daemon=True).start()

    def wait_for(pat, timeout):
        end = time.time() + timeout
        rx = re.compile(pat)
        while time.time() < end:
            if server.poll() is not None and lines.empty():
                raise SystemExit('server exited')
            try:
                l = lines.get(timeout=1)
            except queue.Empty:
                continue
            if rx.search(l):
                return l
        return None

    def send(c):
        server.stdin.write(c + '\n')
        server.stdin.flush()

    clog_path = ROOT / 'build/client.log'
    client = None
    try:
        print('server ready', bool(wait_for(r'TV_READY' if TV else r'Done \(', 600)), flush=True)
        if BM:
            # BetterModel builds its pack asynchronously after start: hand that pack to the client
            built = SERVER / 'plugins/BetterModel/build.zip'
            end = time.time() + 180
            last = -1
            while time.time() < end:
                size = built.stat().st_size if built.exists() else -1
                if size > 0 and size == last:
                    break
                last = size
                time.sleep(2)
            print('bettermodel pack', built.exists() and built.stat().st_size, flush=True)
            shutil.copy(built, GAME / 'resourcepacks/dm-testpack.zip')
        if not TV:
            send('datapack enable "file/dmtest"')
            send('time set 6000')
            send('weather clear')
            send('kill @e[type=minecraft:item_display]')
        d = json.loads(VERSION_JSON.read_text(encoding='utf-8'))
        cp = [str(p) for p in (SRC_CLIENT / 'libraries').rglob('*.jar')] + [str(CLIENT_JAR)]
        inst = DANMO / 'instance.json'
        libs = inst.parents[2] / 'libraries'
        for lib in json.loads(inst.read_text(encoding='utf-8'))['libraries']:
            if lib['name'].startswith(('org.ow2.asm:', 'net.fabricmc:')):
                cp.append(str(libs / lib['downloads']['artifact']['path']))
        nat = SRC_CLIENT / 'natives'
        # own extraction folders: another test client (other projects) may hold the shared ones open
        own = ROOT / 'build/natives'
        for sub in ('jna', 'lwjgl', 'netty'):
            (own / sub).mkdir(parents=True, exist_ok=True)
        off = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + NAME).encode()).digest(), version=3)
        jvm = [str(JAVA), '-Xmx' + os.environ.get('RR_XMX', '4G'), '-XX:StackShadowPages=32', '--enable-native-access=ALL-UNNAMED',
               '--add-exports', 'java.base/jdk.internal.misc=ALL-UNNAMED', f'-Djava.library.path={nat}/java', f'-Djna.tmpdir={own}/jna',
               f'-Dorg.lwjgl.system.SharedLibraryExtractPath={own}/lwjgl', f'-Dio.netty.native.workdir={own}/netty',
               '-DFabricMcEmu= net.minecraft.client.main.Main ', '-Dremoterenderer.statsLogSeconds=' + os.environ.get('RR_STATS', '2')]
        if '--off' in flags:
            jvm.append('-Dremoterenderer.enabled=false')
        if '--no-rigs' in flags:
            jvm.append('-Dremoterenderer.rigs=false')
        jvm += os.environ.get('RR_JVM', '').split()
        client = subprocess.Popen(jvm + ['-cp', ';'.join(cp), 'net.fabricmc.loader.impl.launch.knot.KnotClient', '--username', NAME, '--version', '26.3',
                                         '--gameDir', str(GAME), '--assetsDir', str(SRC_CLIENT / 'assets'), '--assetIndex', d['assetIndex']['id'], '--uuid', off.hex,
                                         '--accessToken', '0', '--userType', 'legacy', '--versionType', 'release',
                                         '--width', os.environ.get('RR_W', '1280'), '--height', os.environ.get('RR_H', '720'),
                                         '--quickPlayMultiplayer', f'localhost:{PORT}'],
                                  cwd=GAME, stdout=open(clog_path, 'w', encoding='utf-8'), stderr=subprocess.STDOUT)
        if '--focus' not in flags:
            subprocess.Popen(['powershell', '-ExecutionPolicy', 'Bypass', '-File', str(ROOT / 'tests/background.ps1'), '-ProcessId', str(client.pid), '-Seconds', '900'],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print('joined', bool(wait_for(rf'{NAME} joined the game', 240)), 'client exit', client.poll(), flush=True)
        if TV:
            m = wait_for(rf'TV_PACK_STATUS {NAME} (SUCCESSFULLY_LOADED|FAILED_DOWNLOAD|FAILED_RELOAD|DECLINED|INVALID_URL|DISCARDED)', 120)
            print('pack status', m and m.strip()[-40:], flush=True)
            print('entered', bool(wait_for(rf'TV_(ENTER|HUB) {NAME}', 60)), flush=True)
            send(f'op {NAME}')
            time.sleep(4)
        else:
            time.sleep(6)
            send(f'gamemode spectator {NAME}')

        def client_wait(pat, timeout):
            end = time.time() + timeout
            rx = re.compile(pat)
            while time.time() < end:
                if rx.search(clog_path.read_text(encoding='utf-8', errors='replace')):
                    return True
                time.sleep(0.5)
            return False

        for s in steps:
            if s.startswith('@'):
                time.sleep(float(s[1:]))
            elif s.startswith('shot:'):
                out = SHOTS / (s[5:] + '.png')
                out.unlink(missing_ok=True)
                for _ in range(4):
                    r = subprocess.run(['powershell', '-ExecutionPolicy', 'Bypass', '-File', str(ROOT / 'tests/shot.ps1'), '-ProcessId', str(client.pid), '-Out', str(out)],
                                       capture_output=True, text=True)
                    if out.exists() and out.stat().st_size > 20000:
                        break
                    time.sleep(1.0)
                print('shot', out.name, (r.stdout or r.stderr).strip()[:150], flush=True)
            elif s.startswith('mark:'):
                print('MARK', s[5:], int(time.time() * 1000), flush=True)
            elif s.startswith('cexpect:'):
                print('cexpect', s[8:], client_wait(s[8:], 30), flush=True)
            elif s.startswith('scene:'):
                cmds = scene(s[6:])
                for c in cmds:
                    send(c)
                print('scene', s[6:], len(cmds), 'commands', flush=True)
            elif s.startswith('view:'):
                x, y, z, yaw, pitch = s[5:].split(':')
                send(f'tp {NAME} {x} {y} {z} {yaw} {pitch}')
            else:
                send(s)
        time.sleep(1)
    finally:
        if client:
            client.kill()
        try:
            send('stop')
            server.wait(90)
        except Exception:
            server.kill()
        text = clog_path.read_text(encoding='utf-8', errors='replace') if clog_path.exists() else ''
        noise = r'authlib|Realms|realms|PerfOS|user properties|SignedJWT|MinecraftClientHttpException|\tat |Caused by'
        interesting = [l for l in text.splitlines() if re.search(r'RemoteRenderer|objmc|Exception|Mixin.*(fail|error)|ERROR|WARN.*(pack|shader|pipeline)', l)
                       and 'RR_STATS' not in l and not re.search(noise, l)]
        print('--- client log (RemoteRenderer / errors) ---')
        for l in interesting[-60:]:
            print(l[:400])
        stats = [l for l in text.splitlines() if 'RR_STATS' in l]
        print('--- RR_STATS (last %d of %d) ---' % (min(len(stats), int(os.environ.get('RR_STATS_TAIL', '8'))), len(stats)))
        for l in stats[-int(os.environ.get('RR_STATS_TAIL', '8')):]:
            print(l[l.index('RR_STATS'):][:330])
        print('done', flush=True)


if __name__ == '__main__':
    main()
