# RemoteRenderer 리그 (스키닝) 형식

[English](README.md) | **한국어**

서버가 보내는 본 디스플레이(BetterModel 본, 플러그인이 직접 만든 파츠)를 그대로 두고, 클라이언트에서는 그 대신
**가중치가 들어간 메쉬 하나**를 GPU에서 휘어서 그립니다. 정보는 리소스팩 파일로 넣습니다. 모드가 없는 사람은 원래 본 디스플레이를 그대로 봅니다.

- 파일 위치: `assets/<네임스페이스>/remoterenderer/rigs/<이름>.json` (리소스팩 어디든; F3+T로 다시 읽음)
- 확인: `/remoterenderer rigs` (불러온 리그, 상태, 그려진 수), `/remoterenderer rigs off|on`, `/remoterenderer rigs play <클립>` (미리보기)
- 코드에서: `kr.gcb.remoterenderer.api.RemoteRendererApi` (`registerRig`, `addJointHook` - 관절을 클라이언트에서 직접 움직이기: 시선, IK, 물리)

## 동작 방식

1. 아이템 디스플레이의 아이템이 리그의 본이면(`item_model`, 필요하면 `custom_model_data` 숫자까지) 그 디스플레이는 그리지 않고 자세만 받습니다.
2. 같은 엔티티에 탑승한 본들이 한 인스턴스입니다(BetterModel, NightShift 모두 본을 바탕 엔티티에 태움).
3. 본 b의 관절 행렬 = `디스플레이 자세 × rest_b⁻¹`. `rest`는 휴식 자세일 때 그 디스플레이의 transformation입니다.
4. 정점마다 관절 4개까지의 가중치로 GPU에서 섞습니다(선형 블렌드 스키닝, 관절 행렬은 텍셀 버퍼).

"리그 공간" = 디스플레이 transformation이 옮겨 놓는 공간(탈것 기준). 메쉬 정점과 `rest`는 모두 이 공간 기준입니다.

## JSON

```json
{
  "format": 1,
  "bones": [
    {"name": "torso", "item_model": "nightshift:h_clerk_torso", "pivot": [0, 0.9375, 0]},
    {"name": "root", "item_model": "minecraft:leather_horse_armor", "custom_model_data": 60, "rest": [16개, 열 우선]}
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

| 키 | 뜻 |
|---|---|
| `bones[].item_model` | 본 디스플레이 아이템의 `item_model` |
| `bones[].custom_model_data` | (선택) `custom_model_data`의 첫 실수값까지 맞아야 본. BetterModel 3.3처럼 모든 본이 같은 아이템에 번호만 다를 때 |
| `bones[].rest` / `pivot` | 휴식 자세의 디스플레이 transformation(4×4, 열 우선 16개) / 이동만 있으면 `pivot` |
| `anchor` | 위치·조명·색을 가져오는 기준 본(기본: 첫 본) |
| `hide_bones` | false면 본 디스플레이도 그대로 그림(메쉬를 덧붙이기만) |
| `geometry` | `"bones"` 또는 메쉬 파일(아래) |

### `"geometry": "bones"`: 본 모델 그대로, 관절만 부드럽게

각 본의 아이템 모델을 그대로 쓰고, `blend`에 적은 관절 주변 정점만 두 본 사이에서 섞습니다. 파일 하나로 끝납니다(텍스처는 블록 아틀라스 그대로).

- `at` 관절 위치, `axis` 부모→자식 방향, `length` 축 방향으로 섞는 반 길이, `radius` 관절에서 이 거리(1.5배까지 서서히)까지만 영향
- `side`: `both` / `child`(자식 파츠 끝만 휨, 몸통 옷이 찢어지지 않게) / `parent`
- `falloff`: `smooth`(맞닿은 파츠: 관절을 넘어가면 상대 본으로 완전히 넘어감) / `half`(둥근 끝이 겹친 파츠: 양쪽이 최대 절반까지만, 관절이 뾰족해지지 않음)
- 가중치를 하나도 안 주면(`blend: []`) 원래 본 디스플레이와 픽셀 단위로 같게 그려집니다(검증함).

### 메쉬 파일: 진짜 텍스처, 원래 가중치, 클라이언트 애니메이션

```json
"geometry": {"data": "sf2:remoterenderer/rigs/cirno.bin", "vertices": 15048, "indices": 62232,
             "vertex_offset": 0, "index_offset": 601920,
             "submeshes": [{"texture": "sf2:textures/rig/cirno/c_t_06_01.png", "first": 0, "count": 58000, "double_sided": true, "filter": "linear"}]},
"skeleton": {"root": [16], "joints": [{"name": "ORG-hips", "parent": -1, "local": [16], "inverse_bind": [16], "bone": "root"}]},
"clips": {"idle_lp": {"fps": 30, "frames": 74, "loop": true, "joints": [3, 4, 9], "offset": 850000}},
"autoplay": "idle_lp"
```

- `.bin` 정점 40바이트: 위치 float×3, 색 u8×4, UV float×2, 법선 s8×4, extra u8×4(x=발광), 관절 u8×4, 가중치 u8×4(합 255). 인덱스 u32. 리틀 엔디언.
- 텍스처는 `textures/rig/...`에 두는 일반 PNG입니다. 바닐라 아틀라스는 `textures/block`, `textures/item` 등만 모으므로 블록·아이템 아틀라스에 들어가지 않습니다.
  리그 팩을 넣어도 아틀라스 크기는 그대로였습니다(blocks 2048×2048, items 1024×512).
  - 그 모델이 처음 보일 때 백그라운드에서 읽어 밉맵을 만듭니다(0.1~0.3초, 그동안 그 모델만 안 그림).
  - 아무 모델도 `rigTextureIdleSeconds`(기본 60초) 동안 안 쓰면 GPU에서 내리고, 다시 보이면 다시 읽습니다.
  - `rigTextureMaxSize`(기본 2048)보다 크면 읽을 때 줄입니다. 4096 두 장이 GPU 170.7MB → 2048은 42.7MB, 플레이 거리에서는 구분이 안 됐습니다.
- 관절 world: `bone`이 있으면 그 본 디스플레이를 따라가고, 없으면 부모 × local(재생 중인 클립 값 또는 기본값), 루트는 기준 본 × `root` × local. 스킨 행렬 = world × `inverse_bind`. 관절은 256개까지, 부모가 앞에 와야 합니다.
- 클립: 프레임 × 관절 × (이동3, 회전 쿼터니언4, 크기3) float. 서버가 아이템의 `custom_model_data` 문자열 `rr:clip=<이름>`으로 고를 수 있고, 없으면 `autoplay`.

## 물리 (머리카락·치마·리본)

리그 파일에 선언된 관절만 흔들립니다(선언이 없으면 아무 일도 없음). 계산은 각 클라이언트에서만 하고 서버로는 아무것도 보내지 않습니다.
설정 메뉴의 "머리카락·치마 물리"로 끌 수 있습니다.

```json
"skeleton": {"joints": [{"name": "Skirt0101.L", "parent": 7, "local": [...], "inverse_bind": [...],
                          "spring": {"stiffness": 0.12, "drag": 0.18, "gravity": 5, "radius": 0.5, "max_angle": 0}}]},
"bones": [{"name": "hair_spring", "item_model": "...", "rest": [...], "parent": "head", "spring": {}}],
"colliders": [{"joint": "ORG-thigh.L", "from": [0, 0, 0], "to": [0, 0.347, 0], "radius": 0.068}],
"physics": true
```

| 키 | 뜻 |
|---|---|
| `stiffness` | 1/60초마다 애니메이션 자세 쪽으로 돌아가는 비율(0~1). 높을수록 단단함 |
| `drag` | 1/60초마다 잃는 속도 비율(0~1). 높을수록 빨리 멈춤 |
| `gravity` | 아래로 끌리는 정도(블록/초²) |
| `radius` | 끝점 크기(충돌용, 리그 단위) |
| `tail` | (선택) 끝점(관절 공간). 없으면 첫 자식 관절, 그것도 없으면 이 관절이 움직이는 가장 먼 정점 |
| `max_angle` | (선택) 애니메이션 자세에서 벗어날 수 있는 최대 각도(도) |
| `colliders` | 캡슐(`from`~`to`, 관절 공간). 스프링 끝점이 이 안으로 못 들어감. 시작점이 이미 캡슐 안인 관절(허리의 치마 첫 줄)은 그 캡슐을 무시 |
| `links` | `{"a": 관절, "b": 관절, "stiffness": 0.6}`: 두 스프링 끝점이 애니메이션상의 거리를 유지. 치마의 옆 사슬끼리 묶어 갈라지지 않게 함 |

- **동작:** 모든 스프링 끝점을 월드 공간 입자 하나의 시스템으로 풉니다. 1/60초마다 적분한 뒤 길이·연결·충돌 제약을 4번 반복합니다.
  사슬의 아래 마디는 위 마디의 입자에 매달리고, 연결(`links`)이 옆 사슬끼리 간격을 지킵니다. 끝점을 월드 공간 입자로 다뤄서 모델이 걷고 돌고 맞는 데 반응합니다. 렌더링은 마지막 두 스텝 사이를 보간해서 FPS와 상관없이 부드럽습니다. 0.5초 이상 안 그려졌거나 4블록 넘게 순간이동하면 다시 시작합니다.
- **`bones` 리그의 `parent`:** 흔들리는 본 아래 본들도 따라가게 합니다. 서버가 보내는 본 위치에 클라이언트 흔들림을 얹는 방식입니다.
- **서버(플러그인)가 끄기:** 본 아이템의 `custom_model_data` 문자열에 `rr:physics=off`를 넣으면 그 모델은 물리를 끕니다. 컷신 등에 씁니다.

### 긴 머리, 망토

같은 스프링 사슬입니다. 긴 머리는 마디가 많은 사슬이고, 망토는 사슬 여러 줄을 옆으로 묶은 판입니다.
- **긴 머리 (glTF):** VoP 패츄리 `.glb`.
  - 뒷머리 5가닥 × 5마디(뒷머리끼리 연결 20개), 옆머리·앞머리 사슬.
  - 머리·목·가슴·허리·팔 충돌체.
  - 움직이면 머리카락이 뒤로 날리고 몸을 뚫지 않습니다.
  ```
  rig_gltf.py --glb patchouli.glb --meshes Body,Face --anchor rrtest:patchouli --anchor-rest identity --scale=-1.125,1.125,1.125     --spring "Bhair*=0.1,0.16,4,0.05" --spring "Shair*=0.1,0.16,4,0.04" --link-ring "Bhair*=0.5"     --collider "ORG-head>" --collider ORG-neck --collider ORG-chest --collider ORG-spine --collider ORG-hips
  ```
  `--collider joint>`는 구, `joint>child`는 그 자식까지의 캡슐입니다. 기본값은 첫 `ORG-` 자식입니다.
- **망토 (BetterModel 큐브 모델, 서버 쪽 선언):** `make_capetest.py`.
  - 망토 3열 × 4마디 그룹 `mantle_l1_spring > mantle_l2_spring > ...`, 포니테일 5마디.
  - `capetest.rr.json`에 `"link_rings": ["mantle_*"]`(열끼리 연결)과 몸통·다리 캡슐.
  - BetterModel 3.6.0에서 흔들면 망토가 한 장으로 날리고, 물리를 끄면 딱딱하게 붙어 있습니다.
- **BetterModel 예약 이름:** 그룹 이름 앞부분이 BetterModel 태그(`cape`, `h`, `hi`, `b`, `p`, `sp`, `glow`, `li`, `ri`, `seat`, `tag` 등 + `_`)면 특수 본이 됩니다.
  특히 `cape_...`는 플레이어 망토 태그라 일반 본이 만들어지지 않습니다. 망토는 `mantle_...` 같은 이름을 쓰세요.
- **`.rr.json`의 길이는 모델 픽셀** 기준입니다. `from`/`to`는 그 본의 피벗 기준이고, 도구가 관절 공간(블록, 180도)으로 바꿔 줍니다.

### 선언 방법

- **리소스팩:** 위 JSON을 직접 씁니다.
- **서버의 모델 파일 (`rig_bbmodel.py`):** 그룹 이름을 `..._spring`으로 끝내거나, 모델 옆에 `<모델>.rr.json`을 둡니다. BetterModel은 둘 다 무시합니다.
  `{"springs": {"hair*": {"stiffness": 0.1}}, "colliders": [{"bone": "leg_l", "to": [0,-8,0], "radius": 2}]}`
- **glTF (`rig_gltf.py`):** `--spring "Skirt*=0.15,0.2,5,1.6"`(이름 패턴=stiffness,drag,gravity,radius), `--collider ORG-thigh.L`(반경은 메쉬에서 자동),
  `--link-ring "Skirt*=0.6"`(같은 깊이의 사슬 마디를 세운 자세에서 둘레 순서로 이어 고리로 묶음, 빈틈이 100° 넘으면 열린 줄).
- bbmodel 옆 `<모델>.rr.json`: `"link_rings": ["skirt*"]` 또는 `"links": [...]`.
- 치마 수치 예: cirno는 처음 `0.07,0.14,5,0.5`에서 앞이 갈라졌고, 연결 고리 + `0.15,0.2,5,1.6`(두께 1.6px)에서 한 덩어리로 흔들렸습니다.

## 만드는 도구 (기준: BetterModel 최신 3.6.0)

- `rig_bbmodel.py <모델.bbmodel>`: BetterModel 모델에서 바로 리그를 만듭니다. 서버는 그대로 두면 됩니다.
  - 큐브 모델 → `"bones"` 리그 + 팔다리처럼 생긴 관절 자동 blend(눈설왕: 본 78개 중 56곳)
  - 메쉬가 있으면 → 메쉬 리그(진짜 텍스처, 텍스처는 `--max-texture` 기본 2048로 줄여 팩 용량도 줄임)
  - UV는 BetterModel이 읽는 방식(u/16, v 뒤집힘) 그대로 따라서, BetterModel에서 맞게 보이던 모델은 리그에서도 맞습니다.
- `bbmodel_rest.py`: BetterModel이 본을 휴식 자세에 놓는 규칙. `rigprobe.bbmodel`(`make_rigprobe.py`, 중첩·세 축 회전 본)로 3.6.0을 실측한 값과 오차 0.0000004로 맞습니다.
  본 디스플레이 transformation = 위로(-탑승 높이) · Y180 · S(1/16) · W_b · T(pivot) · S(16) · Y180⁻¹, W_b = 그룹마다 T(origin)·Rz·Ry·Rx·T(-origin).

- `rig_nightshift.py`: Test 서버(NightShift) 사람 10종. 본 8개 + 무릎·엉덩이·어깨·목 blend. `--no-blend`는 검증용 강체 리그.
- `rig_gltf.py`: glTF(.glb) 스킨 메쉬 → 메쉬 리그(텍스처, 원래 가중치·뼈대, 샘플링한 클립).
  BetterModel 본에 붙일 때 `--anchor bettermodel:model/<모델>_<본>`, BetterModel 3.3이면 `--anchor minecraft:leather_horse_armor --anchor-cmd <번호>`.
  루트 본의 `rest` = scale(16)·rotateY(180) (bbmodel_rest.py의 규칙과 같음). BetterModel 3.3은 실측하지 않았습니다.

```
python tools/rig/rig_gltf.py --glb ".../CharacterViewer/characters/cirno/cirno.glb" --meshes Body,Face,Wing --ns sf2 \
  --name cirno_idle_lp_static --anchor bettermodel:model/cirno_idle_lp_static_root --scale 18,18,-18 \
  --clips idle_lp,mov_forward_lp,atk_normal_01,act_tedium_lp,dam_damage_m_01 --autoplay idle_lp --cirno-fixes --out build/rr-cirno.zip
```
