# chacademi-npc-model — Citizens NPC 3D 외형 (클라이언트 모드)

Citizens NPC의 **외형만** 리깅된 GLB 모델로 바꿔 그린다. 클릭 판정·이동·대화·상점·퀘스트·스토리 클릭은 서버 엔티티 그대로다.
서버 쪽은 `chaca-npc-paper`(ChacaNPC)의 `appearance` 패키지, 패킷 계약은 `chaca-npc-model-protocol`.

- Minecraft 1.21.4 · Fabric loader 0.16.10 · fabric-api 0.119.4+1.21.4 · 모장 공식 매핑 (chacademy-motion 과 같은 조합)
- 빌드: `cd client-mods/chacademi-npc-model; .\gradlew.bat test build` → `build/libs/chacademi-npc-model-0.1.0-npcmodel.1.jar`
- 다른 모드 의존 없음. Unity 같은 외부 실행 환경 없음. 기존 PortableVFX GLB 로더는 스킨을 지원하지 않아 쓰지 않음(BC7 인코더만 복사).

## 1. 동작 요약

| 상황 | 결과 |
|---|---|
| 모드 있음 + 모델 준비됨 | 바닐라 몸·장비·손 아이템 숨김, 3D 모델 그림, 이름표를 모델 꼭대기로 |
| 모드 없음 / 모델 파일 없음 / 해시 불일치 / 손상 / 미지원 형식 / 로드 중 | **원래 플레이어 외형 그대로** (투명화 패킷 없음) |
| 프로토콜 버전 다름 | 서버는 그 플레이어에게 안 보냄(콘솔 경고), 모드는 패킷 무시 → 원래 외형 |
| 서버 재시작·서버 이동 | 세션 번호가 바뀌어 받은 연결 전부 버림 → 새로 받음 |
| NPC despawn·재생성·청크 재진입·재접속 | 엔티티 제거 때 연결 해제, 다시 추적되면 서버가 다시 보냄 (번호+UUID+revision 확인) |

## 2. 클라이언트 리소스 폴더 규격

런처가 미리 깔아 두는 곳: **`<게임폴더>/chacademi/npc-models/`** (서버는 파일을 보내지 않고 모델 ID만 보낸다).

```
chacademi/npc-models/
  models.json            모델 목록 (ID → 파일·sha256·클립 이름)
  luka.glb, ria.glb      정리 도구로 만든 GLB (이 폴더 바로 안, 하위 폴더·.. 불가)
  client.properties      (선택) 압축·거리·프레임 예산 — client.properties.example 참고
  .cache/                (자동) BC7 압축 결과. 지워도 됨, 다음 로드 때 다시 만듦
```

`models.json` (예시는 `dist/npc-models/models.json`):

```json
{"formatVersion":1,"models":{
  "luka":{"file":"luka.glb","sha256":"<64자리>",
          "clips":{"idle":"idle","idleVariants":["look_around"],"walk":"walk","talk":"wait","greet":null},
          "walkSpeed":0, "scale":1.0, "nameplateOffset":0.0,
          "boneMap":{}, "clipFiles":[], "lods":[]}}}
```

| 키 | 뜻 |
|---|---|
| `file`, `sha256` | 폴더 안 파일 이름(.glb)과 해시. 다르면 그 모델은 거부(원래 외형) |
| `clips.idle/walk/talk/greet` | 상태별 실제 클립 이름. `null`·없는 이름이면 대체 동작(아래 4절) |
| `clips.idleVariants` | 가만히 있을 때 15~35초마다 한 번 섞는 클립들 |
| `walkSpeed` | 걷기 클립 1배속일 때 이동 속도(블록/초). 0이면 GLB에 기록된 값 → 없으면 1.3 |
| `scale` | 모델 자체 크기 보정. 서버 `/NPC관리 외형 크기` 와 곱해짐 (`height` 가 있으면 무시) |
| `height` | 기본 자세 키(블록, 모자·머리카락 포함). 0 이면 `scale` 사용. 서버 크기와 곱해짐 |
| `proportion` | `{"legs":0.82,"torso":0.95,"arms":0.92}` 다리·몸통(허리~목)·팔 길이 배율(0.5~1.5). 읽을 때 뼈 길이를 바꿔 비율을 고침 — GLB 다시 만들 필요 없음, 동작(회전)은 그대로 |
| `nameplateOffset` | 이름표 높이 미세 조정(블록, −2~2) |
| `boneMap` | 클립 파일 뼈 이름 → 이 모델 뼈 이름. 비우면 같은 이름 → `:` 앞 접두어 뗀 이름 순으로 맞춤 (Mixamo 이름 하드코딩 없음) |
| `clipFiles` | `[{"file":"ria_greet.glb","sha256":"…"}]` 같은 골격의 클립만 든 GLB를 추가로 붙임 |
| `lods` | `[{"from":16,"file":"ria_lod1.glb","sha256":"…"}]` 별도 제작한 저폴리 메쉬. 없으면 모든 거리에서 원본 메쉬 + 자세 갱신 빈도만 낮춤 |

파일 상한(넘으면 거부): 48MB, 노드 512, 관절 256, 정점 20만, 인덱스 150만, 그림 8장·한 변 4096, 클립 64.
거부: 외부/데이터 URI, `extensionsRequired`, sparse accessor, 범위 밖 인덱스, 순환 계층, NaN.
무시 + 로그 진단: 모프 타깃·weights 애니메이션, 노멀/오클루전/발광 맵, 미지원 확장, JOINTS_1.

## 3. 모델 정리 도구 (FBX → 배포용 GLB)

`tools/npc-model/normalize_npc_model.py` — Python 3.10+, numpy, Pillow, [FBX2glTF v0.9.7](https://github.com/facebookincubator/FBX2glTF/releases)

```powershell
python tools\npc-model\normalize_npc_model.py --input 루카.fbx --textures 루카.fbm --fbx2gltf FBX2glTF-windows-x64.exe --out luka.glb --height 1.8
python tools\npc-model\preview_npc_model.py luka.glb --clip walk --times 0,0.6,1.2 --out walk.png   # 게임 없이 자세 확인
```

결과 규격: **+Y 위, 모델 정면 +Z, 1 = 1블록, 원점 = 두 발 사이 바닥**. 최상위 `chaca_root` 노드가 단위·방향·원점을 맡고 원래 계층은 그대로.
- 정면은 골반(좌우 허벅지 뼈) 축으로 자동 계산. 틀리면 `--yaw <도>`.
- 클립의 수평 루트 이동은 지우고(제자리), 원래 속도를 `asset.extras.chaca.clips.<이름>.rootSpeed` 에 남김 → 모드가 걷기 재생 속도 계산에 씀. root motion 으로 이중 이동하지 않음.
- Blender FBX의 `Armature|Armature|walk` 접두어 제거.
- 텍스처: 메쉬 노드 이름으로 `.fbm` 그림을 찾아 다시 붙이고, 최대 2048로 줄이며 UV 섬 가장자리 8px 패딩(축소·밉맵 때 이웃 섬 색 번짐 방지). GPU에서는 BC7로 한 번 더 압축.
- 실행 결과 JSON(`--report`)에 키·크기·정면 각도·클립·sha256 이 나온다. 그 sha256 을 models.json 에 넣는다.

좌표 기준: 마인크래프트 yaw 0(남쪽, +Z)을 볼 때 모델 정면 +Z 가 앞. 몸 방향은 엔티티 `bodyRot`. pitch(고개)는 모델에 적용하지 않음.

## 4. 애니메이션 규칙

| 우선순위 | 상태 | 시작 | 끝 | 대체 |
|---|---|---|---|---|
| 1 | walk | 수평 속도 > 0.45블록/초가 0.15초 지속 | < 0.20블록/초가 0.25초 지속 (히스테리시스) | 클립 없으면 idle |
| 2 | greet | 서버 인사 번호가 바뀔 때 1회 (대화창 열림·스토리 대화 열림) | 클립 끝 | 클립 없으면 건너뜀 |
| 3 | talk | 서버 상태 TALK (AI 대화 중) | 서버 상태 IDLE | 클립 없으면 idle |
| 4 | idle | 기본 | — | 클립 없으면 기본 자세 |

- 걷기가 시작되면 인사·대화 동작은 끊긴다. 전환은 교차 페이드(이동 0.3초, 대화 0.35초, 인사 0.2초 진입/0.3초 복귀).
- 걷기 재생 속도 = 실제 속도 ÷ walkSpeed, 0.6~1.8배로 제한하고 부드럽게 따라감.
- 클립에 있는 보조 뼈(머리카락·치마 뼈 등)도 그대로 재생. 클립에 없는 뼈는 기본 자세 유지.
- 실시간 천·머리카락 물리, 얼굴 모프, 립싱크는 이번 범위가 아님. 관통은 모델·웨이트·클립 문제로 렌더러가 자동으로 고치지 않음.
- 애니메이션은 표시용. 서버는 재생 여부를 모르고, 보상·스토리 완료 판정에 쓰지 않음.

## 5. 거리·성능

- 자세 갱신: 0~16블록 30Hz, 16~32 15Hz, 32~64 8Hz, 그 너머 3Hz (client.properties 로 조정). 화면 밖이면 계산·그리기 모두 안 함 (모델 NPC는 히트박스 대신 모델 크기 상자로 판정 — 큰 동상이 발만 화면 밖일 때 사라지지 않게), 다시 보이면 게임 시각 기준으로 이어짐.
- 프레임당 자세 계산 예산 기본 3ms. 넘으면 남은 NPC는 지난 자세를 그림.
- 같은 모델 NPC끼리 메쉬·텍스처·클립 1벌 공유(파싱·업로드 1회), NPC마다 자세·변형 정점만 따로. 쓰는 NPC가 없으면 2분 뒤 GPU에서 내림.
- 파일 읽기·해시·파싱·JPEG 디코딩·BC7 인코딩은 작업 스레드 2개, GL 텍스처 생성·삭제는 렌더 스레드.
- 텍스처 GPU 메모리(2048 2장 + 밉맵): RGBA8 약 43MB → BC7 약 11MB / 모델 1종. BC7 미지원 GPU는 풀어서 올림.

## 5-1. 게임 안에서 비율·키 조절 (내 화면만)

| 명령 | 내용 |
|---|---|
| `/npc모델 정보 [모델]` | 지금 비율·키 |
| `/npc모델 비율 <모델> <다리\|몸통\|팔> <0.5~1.5>` | 바로 다시 읽어서 적용 |
| `/npc모델 키 <모델> <블록>` | 0 = models.json scale 사용 |
| `/npc모델 저장 <모델>` | 지금 값을 내 models.json 에 기록 (다른 사람에게는 런처 배포본 models.json 을 바꿔야 적용) |
| `/npc모델 되돌리기` | models.json 다시 읽기 |

마크 기본 캐릭터는 머리·몸통이 큰 비율이라, 실제 사람 비율 모델은 같은 키에서도 작아 보인다. 키를 크게 올리는 대신
다리를 줄이고(0.8 안팎) 키를 조금만 올리면(학생 1.9~1.95, 어른 2.1) 덩치가 비슷해 보인다.

## 6. 다른 모드에서 쓰기

`kr.chacademi.npcmodel.NpcModelClient.modelTopHeight(Entity)` → 3D 모델로 그려지는 중이면 발밑 기준 모델 꼭대기 높이(블록), 아니면 −1.
스토리 머리 위 징표(아직 미구현)는 이 값이 0 이상이면 `entity.getBbHeight()` 대신 이 값을 쓰면 된다 (리플렉션 호출 가능, 정적·원시 타입).

## 7. 알려진 한계

- Citizens가 이름을 홀로그램(별도 엔티티)으로 띄우는 설정이면 그 높이는 이 모드가 바꾸지 않는다 (바닐라 이름표만 조정).
- 반투명(BLEND) 재질은 삼각형 단위 정렬을 하지 않아 겹치면 순서가 틀릴 수 있다. 루카·리아는 둘 다 불투명.
- 정점 변형은 CPU, 그리기는 바닐라 엔티티 버퍼(매 프레임 정점 전송). 근거리 NPC가 많으면 정점 전송 비용이 크다 → LOD 메쉬 권장, 필요하면 후속으로 GPU 스키닝.
