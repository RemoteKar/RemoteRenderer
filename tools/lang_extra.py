# -*- coding: utf-8 -*-
"""Adds the command messages and Mod Menu description to every language file, and writes ja_jp / zh_cn / zh_tw.

en_us is the default (Minecraft falls back to it for any key a language lacks).
  python tools/lang_extra.py
"""
import json
from pathlib import Path

LANG = Path(__file__).resolve().parents[1] / 'mod/resources/assets/remoterenderer/lang'

COMMON = {
    'en_us': {
        'modmenu.summaryTranslation.remoterenderer': 'Draws item-display models (BetterModel, objmc) on the GPU, with rigs, physics and server shaders.',
        'modmenu.descriptionTranslation.remoterenderer': 'Item-display models (BetterModel bones and meshes, objmc) are baked once and drawn with GPU instancing. '
        'Resource-pack rigs add skinned joints, real textures, hair / skirt / cape physics and client animation clips; a server pack\'s own item shader '
        'keeps working (run instanced). Client side only.',
        'remoterenderer.cmd.on': 'RemoteRenderer: GPU path on',
        'remoterenderer.cmd.off': 'RemoteRenderer: off (vanilla renderer)',
        'remoterenderer.cmd.clear': 'RemoteRenderer: all baked models cleared',
        'remoterenderer.cmd.play.own': 'RemoteRenderer: rigs play their own clips again',
        'remoterenderer.cmd.play': 'RemoteRenderer: every rig with clip %s plays it (preview)',
        'remoterenderer.cmd.rigs.on': 'RemoteRenderer: rigs on',
        'remoterenderer.cmd.rigs.off': 'RemoteRenderer: rigs off (bone displays drawn as they are)',
        'remoterenderer.cmd.minquads': 'RemoteRenderer: minQuads = %s',
        'remoterenderer.cmd.status': 'RemoteRenderer %s%s | %s',
    },
    'ko_kr': {
        'modmenu.summaryTranslation.remoterenderer': '아이템 디스플레이 모델(BetterModel, objmc)을 GPU로 그립니다. 리그·물리·서버 셰이더 지원.',
        'modmenu.descriptionTranslation.remoterenderer': '아이템 디스플레이 모델(BetterModel 본·메쉬, objmc)을 한 번 구워 GPU 인스턴싱으로 그립니다. '
        '리소스팩 리그로 관절 스키닝, 진짜 텍스처, 머리카락·치마·망토 물리, 클라이언트 애니메이션을 더하고, 서버 팩의 아이템 셰이더도 그대로 실행합니다. 클라이언트 전용.',
        'remoterenderer.cmd.on': 'RemoteRenderer: GPU 경로 켜짐',
        'remoterenderer.cmd.off': 'RemoteRenderer: 꺼짐 (바닐라 렌더러)',
        'remoterenderer.cmd.clear': 'RemoteRenderer: 구운 모델을 모두 비웠습니다',
        'remoterenderer.cmd.play.own': 'RemoteRenderer: 리그가 다시 자기 클립을 재생합니다',
        'remoterenderer.cmd.play': 'RemoteRenderer: 클립 %s가 있는 리그는 모두 그 클립을 재생합니다 (미리보기)',
        'remoterenderer.cmd.rigs.on': 'RemoteRenderer: 리그 켜짐',
        'remoterenderer.cmd.rigs.off': 'RemoteRenderer: 리그 꺼짐 (본 디스플레이를 그대로 그림)',
        'remoterenderer.cmd.minquads': 'RemoteRenderer: minQuads = %s',
        'remoterenderer.cmd.status': 'RemoteRenderer %s%s | %s',
    },
}

JA = {
    'remoterenderer.menu.title': 'RemoteRenderer 設定',
    'remoterenderer.menu.displays': 'ディスプレイモデル',
    'remoterenderer.menu.rigsHeader': 'リグ (スキニング・物理)',
    'remoterenderer.menu.enabled': 'GPUで描画',
    'remoterenderer.menu.enabled.tip': 'オフにすると、すべてのアイテムディスプレイをバニラのレンダラーが描画します。',
    'remoterenderer.menu.objmc': 'objmc モデル',
    'remoterenderer.menu.objmc.tip': 'objmc のテクスチャメッシュを展開して描画します(26.3 のバニラでは表示されないモデル)。',
    'remoterenderer.menu.cacheResolve': '同じアイテムを再利用',
    'remoterenderer.menu.cacheResolve.tip': 'アイテムが変わらないディスプレイはモデルを再解決しません。',
    'remoterenderer.menu.cullOffscreen': '画面外を省略',
    'remoterenderer.menu.cullOffscreen.tip': '画面外のディスプレイモデルとリグを描画しません。バニラはディスプレイエンティティを背後でも描画します。',
    'remoterenderer.menu.ignoreCustomItemShaders': 'パックのシェーダーを無視',
    'remoterenderer.menu.ignoreCustomItemShaders.tip': 'リソースパックがアイテムシェーダーを置き換えても、独自のシェーダーで描画します(パックの効果は失われます)。',
    'remoterenderer.menu.adaptCustomItemShaders': 'サーバーのシェーダーに対応',
    'remoterenderer.menu.adaptCustomItemShaders.tip': 'リソースパックがアイテムシェーダーを置き換えた場合、そのシェーダーをそのままインスタンシングで実行します(材質・HUD 効果を維持)。オフにするとそのパックでは身を引きます。',
    'remoterenderer.menu.hideWhileBakingQuads': 'ベイク中は非表示',
    'remoterenderer.menu.hideWhileBakingQuads.tip': 'この数以上の面を持つ新しいモデルは、最初のベイクの短い間だけ描画しません(バニラで描くとカクつくため)。',
    'remoterenderer.menu.bakeThreads': 'ベイクスレッド',
    'remoterenderer.menu.bakeThreads.tip': '新しいモデルをベイクするバックグラウンドスレッド数。再起動後に適用されます。',
    'remoterenderer.menu.rigs': 'リグを使う',
    'remoterenderer.menu.rigs.tip': 'リソースパックのリグ(remoterenderer/rigs)があれば、ボーンディスプレイの代わりに一つのスキンメッシュで描画します。',
    'remoterenderer.menu.rigPhysics': '髪・スカートの物理',
    'remoterenderer.menu.rigPhysics.tip': 'リグで宣言されたスプリング関節が揺れます。各クライアントでのみ計算されます。',
    'remoterenderer.menu.rigTextureMaxSize': 'リグテクスチャ最大',
    'remoterenderer.menu.rigTextureMaxSize.tip': 'これより大きいリグテクスチャは読み込み時に縮小します。4096 二枚 = 171MB、2048 = 43MB。',
    'remoterenderer.menu.original': '元のまま',
    'remoterenderer.menu.rigTextureIdleSeconds': 'テクスチャ解放',
    'remoterenderer.menu.rigTextureIdleSeconds.tip': 'この時間使われなかったリグテクスチャは GPU から解放します(再び見えたら読み直します)。',
    'remoterenderer.menu.statsLogSeconds': '統計ログ',
    'remoterenderer.menu.statsLogSeconds.tip': 'この間隔で RR_STATS 行をログに書きます。',
    'remoterenderer.menu.clear': 'ベイク済みモデルを消去',
    'remoterenderer.menu.clear.tip': 'ベイクしたモデルをすべて捨てて、もう一度ベイクします。',
    'remoterenderer.menu.reload': 'リソースを再読み込み',
    'remoterenderer.menu.reload.tip': 'F3+T と同じです。リグファイルを編集した後に押してください。',
    'remoterenderer.menu.state.on': '動作中',
    'remoterenderer.menu.state.off': 'オフ',
    'remoterenderer.menu.state.customShader': 'リソースパックがアイテムシェーダーを置き換え: %s',
    'remoterenderer.menu.state.packShader': 'サーバーのシェーダーで実行: %s',
    'remoterenderer.menu.stats': '%s fps · %sms · ディスプレイ %s 個 · 描画 %s 回 · 画面外省略 %s',
    'remoterenderer.menu.rigStats': 'リグ %s 種 · 描画インスタンス %s · 画面外 %s · リグテクスチャ %sMB',
    'modmenu.summaryTranslation.remoterenderer': 'アイテムディスプレイのモデル(BetterModel、objmc)を GPU で描画します。リグ・物理・サーバーシェーダー対応。',
    'modmenu.descriptionTranslation.remoterenderer': 'アイテムディスプレイのモデル(BetterModel のボーン・メッシュ、objmc)を一度ベイクし、GPU インスタンシングで描画します。'
    'リソースパックのリグで関節スキニング、本来のテクスチャ、髪・スカート・マントの物理、クライアントアニメーションを追加し、サーバーパックのアイテムシェーダーもそのまま実行します。クライアント専用。',
    'remoterenderer.cmd.on': 'RemoteRenderer: GPU 描画オン',
    'remoterenderer.cmd.off': 'RemoteRenderer: オフ(バニラのレンダラー)',
    'remoterenderer.cmd.clear': 'RemoteRenderer: ベイク済みモデルをすべて消去しました',
    'remoterenderer.cmd.play.own': 'RemoteRenderer: リグは再び自分のクリップを再生します',
    'remoterenderer.cmd.play': 'RemoteRenderer: クリップ %s を持つすべてのリグがそれを再生します(プレビュー)',
    'remoterenderer.cmd.rigs.on': 'RemoteRenderer: リグ オン',
    'remoterenderer.cmd.rigs.off': 'RemoteRenderer: リグ オフ(ボーンディスプレイをそのまま描画)',
    'remoterenderer.cmd.minquads': 'RemoteRenderer: minQuads = %s',
    'remoterenderer.cmd.status': 'RemoteRenderer %s%s | %s',
}

ZH_CN = {
    'remoterenderer.menu.title': 'RemoteRenderer 设置',
    'remoterenderer.menu.displays': '展示实体模型',
    'remoterenderer.menu.rigsHeader': '骨骼绑定 (蒙皮 · 物理)',
    'remoterenderer.menu.enabled': '用 GPU 绘制',
    'remoterenderer.menu.enabled.tip': '关闭后,所有物品展示实体都由原版渲染器绘制。',
    'remoterenderer.menu.objmc': 'objmc 模型',
    'remoterenderer.menu.objmc.tip': '解码 objmc 纹理网格并直接绘制(在 26.3 原版中不可见的模型)。',
    'remoterenderer.menu.cacheResolve': '复用相同物品',
    'remoterenderer.menu.cacheResolve.tip': '物品未改变的展示实体不再重新解析模型。',
    'remoterenderer.menu.cullOffscreen': '跳过屏幕外',
    'remoterenderer.menu.cullOffscreen.tip': '不绘制屏幕外的展示模型和骨骼绑定。原版即使在身后也会绘制展示实体。',
    'remoterenderer.menu.ignoreCustomItemShaders': '忽略资源包着色器',
    'remoterenderer.menu.ignoreCustomItemShaders.tip': '即使资源包替换了物品着色器也用自己的着色器绘制(资源包的效果会丢失)。',
    'remoterenderer.menu.adaptCustomItemShaders': '适配服务器着色器',
    'remoterenderer.menu.adaptCustomItemShaders.tip': '资源包替换物品着色器时,直接以实例化方式运行该着色器(保留材质、HUD 效果)。关闭则对此类资源包让路。',
    'remoterenderer.menu.hideWhileBakingQuads': '烘焙时隐藏',
    'remoterenderer.menu.hideWhileBakingQuads.tip': '面数达到此值的新模型在首次烘焙的短暂时间内不绘制(用原版绘制会卡顿)。',
    'remoterenderer.menu.bakeThreads': '烘焙线程',
    'remoterenderer.menu.bakeThreads.tip': '烘焙新模型的后台线程数。重启后生效。',
    'remoterenderer.menu.rigs': '使用骨骼绑定',
    'remoterenderer.menu.rigs.tip': '若资源包中有骨骼绑定(remoterenderer/rigs),则用一个蒙皮网格代替骨骼展示实体绘制。',
    'remoterenderer.menu.rigPhysics': '头发·裙子物理',
    'remoterenderer.menu.rigPhysics.tip': '骨骼绑定中声明的弹簧关节会摆动。只在各自的客户端计算。',
    'remoterenderer.menu.rigTextureMaxSize': '绑定纹理上限',
    'remoterenderer.menu.rigTextureMaxSize.tip': '大于此尺寸的绑定纹理在加载时缩小。两张 4096 = 171MB,2048 = 43MB。',
    'remoterenderer.menu.original': '原始',
    'remoterenderer.menu.rigTextureIdleSeconds': '纹理释放',
    'remoterenderer.menu.rigTextureIdleSeconds.tip': '在此时间内无人使用的绑定纹理会从 GPU 释放(再次出现时重新加载)。',
    'remoterenderer.menu.statsLogSeconds': '统计日志',
    'remoterenderer.menu.statsLogSeconds.tip': '按此间隔向日志写入 RR_STATS 行。',
    'remoterenderer.menu.clear': '清除已烘焙模型',
    'remoterenderer.menu.clear.tip': '丢弃所有已烘焙的模型并重新烘焙。',
    'remoterenderer.menu.reload': '重新加载资源',
    'remoterenderer.menu.reload.tip': '与 F3+T 相同。修改绑定文件后使用。',
    'remoterenderer.menu.state.on': '运行中',
    'remoterenderer.menu.state.off': '关闭',
    'remoterenderer.menu.state.customShader': '资源包替换了物品着色器: %s',
    'remoterenderer.menu.state.packShader': '以服务器着色器运行: %s',
    'remoterenderer.menu.stats': '%s fps · %sms · 展示实体 %s 个 · 绘制 %s 次 · 屏幕外跳过 %s',
    'remoterenderer.menu.rigStats': '骨骼绑定 %s 种 · 绘制实例 %s · 屏幕外 %s · 绑定纹理 %sMB',
    'modmenu.summaryTranslation.remoterenderer': '用 GPU 绘制物品展示实体模型(BetterModel、objmc),支持骨骼绑定、物理和服务器着色器。',
    'modmenu.descriptionTranslation.remoterenderer': '物品展示实体模型(BetterModel 的骨骼与网格、objmc)只烘焙一次,并以 GPU 实例化绘制。'
    '资源包骨骼绑定可添加关节蒙皮、原始纹理、头发·裙子·披风物理和客户端动画;服务器资源包的物品着色器也会照常运行。仅客户端。',
    'remoterenderer.cmd.on': 'RemoteRenderer: GPU 绘制已开启',
    'remoterenderer.cmd.off': 'RemoteRenderer: 已关闭(原版渲染器)',
    'remoterenderer.cmd.clear': 'RemoteRenderer: 已清除所有烘焙模型',
    'remoterenderer.cmd.play.own': 'RemoteRenderer: 骨骼绑定恢复播放自己的动画',
    'remoterenderer.cmd.play': 'RemoteRenderer: 所有含动画 %s 的骨骼绑定都会播放它(预览)',
    'remoterenderer.cmd.rigs.on': 'RemoteRenderer: 骨骼绑定已开启',
    'remoterenderer.cmd.rigs.off': 'RemoteRenderer: 骨骼绑定已关闭(按原样绘制骨骼展示实体)',
    'remoterenderer.cmd.minquads': 'RemoteRenderer: minQuads = %s',
    'remoterenderer.cmd.status': 'RemoteRenderer %s%s | %s',
}

# Traditional Chinese: the simplified text with the characters that differ swapped
TW = str.maketrans({'设': '設', '置': '置', '展': '展', '示': '示', '实': '實', '体': '體', '骨': '骨', '绑': '綁', '定': '定', '蒙': '蒙',
                    '理': '理', '绘': '繪', '制': '製', '关': '關', '闭': '閉', '后': '後', '有': '有', '物': '物', '品': '品', '都': '都',
                    '由': '由', '原': '原', '版': '版', '渲': '渲', '染': '染', '器': '器', '码': '碼', '纹': '紋', '网': '網', '格': '格',
                    '并': '並', '直': '直', '接': '接', '见': '見', '复': '複', '用': '用', '相': '相', '同': '同', '变': '變', '的': '的',
                    '再': '再', '重': '重', '新': '新', '解': '解', '析': '析', '模': '模', '型': '型', '跳': '跳', '过': '過', '屏': '螢',
                    '幕': '幕', '外': '外', '即': '即', '使': '使', '在': '在', '身': '身', '会': '會', '忽': '忽', '略': '略', '资': '資',
                    '源': '源', '包': '包', '着': '著', '色': '色', '替': '替', '换': '換', '效': '效', '果': '果', '丢': '丟', '失': '失',
                    '适': '適', '配': '配', '务': '務', '时': '時', '运': '運', '行': '行', '该': '該', '例': '例', '化': '化', '保': '保',
                    '留': '留', '材': '材', '质': '質', '则': '則', '对': '對', '此': '此', '类': '類', '让': '讓', '路': '路', '烘': '烘',
                    '焙': '焙', '隐': '隱', '藏': '藏', '面': '面', '数': '數', '达': '達', '到': '到', '值': '值', '首': '首', '次': '次',
                    '短': '短', '暂': '暫', '间': '間', '内': '內', '不': '不', '卡': '卡', '顿': '頓', '线': '線', '程': '程', '台': '台',
                    '启': '啟', '生': '生', '若': '若', '中': '中', '则': '則', '一': '一', '个': '個', '代': '代', '头': '頭', '发': '髮',
                    '裙': '裙', '子': '子', '声': '聲', '明': '明', '弹': '彈', '簧': '簧', '节': '節', '摆': '擺', '动': '動', '只': '只',
                    '各': '各', '自': '自', '客': '客', '户': '戶', '端': '端', '计': '計', '算': '算', '上': '上', '限': '限', '大': '大',
                    '于': '於', '尺': '尺', '寸': '寸', '加': '加', '载': '載', '缩': '縮', '小': '小', '两': '兩', '张': '張', '释': '釋',
                    '放': '放', '无': '無', '人': '人', '出': '出', '现': '現', '统': '統', '日': '日', '志': '誌', '按': '按', '写': '寫',
                    '清': '清', '除': '除', '已': '已', '弃': '棄', '所': '所', '修': '修', '改': '改', '文': '文', '件': '件', '与': '與',
                    '号': '號', '态': '態', '种': '種', '画': '畫', '还': '還', '开': '開', '恢': '恢', '播': '播', '己': '己', '照': '照',
                    '常': '常', '仅': '僅', '持': '持', '风': '風', '披': '披', '添': '添', '可': '可', '始': '始', '样': '樣', '按': '按'})


def main():
    base = {}
    for lang in ('en_us', 'ko_kr'):
        p = LANG / (lang + '.json')
        d = json.loads(p.read_text(encoding='utf-8'))
        d.update(COMMON[lang])
        p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        if lang == 'en_us':
            base = d
    for lang, d in (('ja_jp', JA), ('zh_cn', ZH_CN), ('zh_tw', {k: v.translate(TW).replace('設置', '設定') for k, v in ZH_CN.items()})):
        missing = [k for k in base if k not in d]
        if missing:
            raise SystemExit(lang + ' lacks ' + ', '.join(missing))
        (LANG / (lang + '.json')).write_text(json.dumps(d, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('languages:', sorted(p.stem for p in LANG.glob('*.json')), len(base), 'keys')


if __name__ == '__main__':
    main()
