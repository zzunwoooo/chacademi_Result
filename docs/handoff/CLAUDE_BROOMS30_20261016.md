# 빗자루 30종 개편: 모델 + 능력치 + 조작 + 빗자루별 궤적 (Claude, 2026-10-16)

브랜치: `claude/broom-30-20261016` (기준: `claude/broom-trails-20261016` @ 30a12a3, 그 위에 이어서 작업)
상태: **빌드·실게임 안 함.** 클라우드에서 Gradle 의존성 다운로드가 막혀 있어서 순수 로직만 스텁 JUnit으로 돌렸다(물리 테스트 15개 통과, `BroomType`으로 새 `brooms.yml` 30종 읽기 확인). Gradle 빌드·배포는 GPT가 한다.
사용자 확정: 30종 이름·등급·능력치·설명, 궤적 26종(일반은 기존 basic). 궤적 미리보기는 사용자 PC `차카데미 vfx_Claude\BroomTrails_Rare\BroomTrails_Preview.html`에 있다.

## 0. 먼저 볼 것 (배포 주의)
1. **빗자루 ID를 등급 순서 `broom_1`~`broom_30`으로 다시 매겼다.**
   - 예전 ID는 다른 빗자루를 가리킨다. 예: 예전 broom_1(견습생) = 지금 broom_4(차큐스 100).
   - 사용자 확인: **실제 플레이어에게 빗자루를 지급한 적이 없다.** 그래서 권한·장착 기록 변환은 넣지 않았다.
   - 혹시 테스트로 준 `chacademy.broom.own.broom_N` 권한이 있으면 새 번호 기준으로 다시 확인할 것.
2. **서버의 `plugins/ChacademyMotion/brooms.yml`은 자동으로 교체된다.**
   - 새 기본 파일에 `config-version: 2`가 있다.
   - 플러그인이 버전 없는(=1) 파일을 찾으면 `brooms.yml.v1.bak`으로 옮기고(이미 있으면 `.bak2`, `.bak3`…) 새 기본 파일을 쓴다.
   - 빗자루 목록과 `practice-broom`은 새 기본값을 쓴다. 나머지 서버 설정(`mastery-full-hours`, 쿨다운 3개, `blocked-worlds`, `debug`)은 예전 값을 옮겨 적는다. 로그 경고 1회.
   - 파일 이동이 실패하면 예전 파일을 그대로 쓰고, 실제 오류를 경고로 남긴다. 이때는 수동으로 교체해야 한다(예전 파일의 수치는 등급 배수가 사라져서 원래 의도보다 낮게 나온다).
3. **클라이언트 배포물 3가지를 같이 바꿔야 한다.**
   - 모션 모드 jar: 모델 30종, 물리, 궤적 연결표가 들어 있다.
   - PortableVFX 팩 **4개**: `BroomTrails`(교체), `BroomTrailsWood`, `BroomTrailsUnique`, `BroomTrailsLegend`(신규). 위치는 `.minecraft/config/portablevfx/effects/<팩>/`, 원본은 `portable-vfx-runtime/assets/<팩>/`.
     - 클라이언트가 팩 하나에 시스템 16개 미만만 받기 때문에 나눴다(`ClaudeEffect.java:388`). 50개를 한 팩에 넣으면 팩 전체가 거절된다.
     - 4개 모두 실제 클라이언트 파서(`ClaudeEffect.parse`)로 통과를 확인했다. 시스템 수: 10 / 12 / 14 / 14.
   - magic-codex jar: 장비창 보유 목록 상한 10 → 30(`BroomUiLink`). magic-codex 버전 문자열은 GPT 쪽 버전 체계와 충돌하지 않게 건드리지 않았으니 배포 때 올려 줄 것.
4. **패킷 형식은 그대로다.** `climb`은 능력치가 아니라 공통값이다: `brooms.yml`의 `climb-power`(기본 0.055)를 모든 빗자루에 보낸다. 빗자루별 `climb` 키를 넣으면 그 값이 우선한다. (2026-10-17 사용자 요청으로 스페이스 상승을 되살림)
5. 버전: 모션 모드 `0.3.0-codex.3-hud.2-trails.2-brooms30.1`, 모션 플러그인 `0.3.0-codex.3-carrier.1-brooms30.1`.

## 1. 30종 표 (단계 → 실제 값)
단계 → 값 공식(`brooms.yml` 머리말에도 적혀 있음):

| 능력치 | 공식 | 1단계 → 10단계 |
|---|---|---|
| 최고 속도 (b/t) | (10.9 + 22.1·(L−1)/9) / 20 | 10.9 → 33 b/s (1단계 = 크리에이티브 비행). 부스트 ×1.45 |
| 가속 | 0.025 + 0.005·(L−1) | 0.025 → 0.07 |
| 선회 (°/틱) | 6 + L | 7 → 16 (최고 속도에서 ×0.6, 부스트 중 ×0.6). 상하에도 적용 |
| 제동 | 0.025 + 0.005·(L−1) | 0.025 → 0.07 |
| 안정성 | 0.6 + 1.2·(L−1)/9 | 0.6 → 1.8. 최대 균형 = 90 + 3·L |

| ID | 이름 | 등급 | 속·가·선·제·안 | 모델 원본 | 궤적 키 |
|---|---|---|---|---|---|
| broom_1 | 견습생 빗자루 | 일반 | 2·2·2·2·4 | 예전 broom_2 | basic |
| broom_2 | 대충 만든 빗자루 | 일반 | 4·3·2·1·1 | 예전 broom_8 | basic |
| broom_3 | 먼지 털이 | 일반 | 1·3·4·4·3 | 예전 broom_4 | basic |
| broom_4 | 차큐스 100 | 일반 | 3·3·3·3·3 | 예전 broom_1 | basic |
| broom_5 | 차큐스 100S | 일반 | 4·4·4·4·2 | 예전 broom_6 | basic |
| broom_6 | 차큐스 200 | 희귀 | 4·4·4·4·4 | 예전 broom_10 | rare_plus |
| broom_7 | 차큐스 300 | 희귀 | 5·4·4·4·4 | 예전 broom_9 | rare_plus |
| broom_8 | 고급스러운 빗자루 | 희귀 | 3·4·5·5·6 | 예전 broom_5 | rare_gold |
| broom_9 | 차큐스 레이서 1 | 희귀 | 6·5·6·4·3 | 예전 broom_3 | wave1 |
| broom_10 | 차큐스 레이서 2 | 희귀 | 7·6·5·2·3 | 예전 broom_7 | wave2 |
| broom_11 | 차큐스 400 (참나무) | 희귀 | 5·5·5·5·5 | 신규 h_oak | leaf_oak |
| broom_12 | 차큐스 400 (벚나무) | 희귀 | 5·5·5·5·5 | 신규 h_cherry | leaf_cherry |
| broom_13 | 차큐스 400 (자작나무) | 희귀 | 5·5·5·5·5 | 신규 h_birch | leaf_birch |
| broom_14 | 차큐스 400 (마호가니) | 희귀 | 5·5·5·5·5 | 신규 h_mahogany | leaf_mahogany |
| broom_15 | 차큐스 400 (대나무) | 희귀 | 5·5·5·5·5 | 신규 h_bamboo | leaf_bamboo |
| broom_16 | 초보 마녀의 빗자루 | 희귀 | 4·7·5·5·4 | 신규 h_starnight | galaxy |
| broom_17 | 차큐스 500 | 유일 | 7·7·6·6·6 | 신규 racer | u_dark |
| broom_18 | 차큐스 500 (미드나잇) | 유일 | 6·7·7·7·6 | 신규 racer_navy | u_classic |
| broom_19 | 차큐스 500 (에메랄드) | 유일 | 8·3·5·2·5 | 신규 u_emerald | u_emerald |
| broom_20 | 차큐스 500 (크림슨) | 유일 | 7·7·7·7·7 | 신규 u_crimson | u_crimson |
| broom_21 | 쏜살같은 빗자루 | 유일 | 8·7·6·4·4 | 신규 u_arrow | u_sonic |
| broom_22 | 건메탈 레이서 | 유일 | 6·8·8·8·8 | 신규 u_gunmetal | u_holo |
| broom_23 | 바이올렛 트윈테일 | 유일 | 10·10·1·1·2 | 신규 u_violet | u_twin |
| broom_24 | 마법소녀 빗자루 | 전설 | 9·8·8·7·8 | 신규 heart | l_heart |
| broom_25 | 타락한 마법소녀 빗자루 | 전설 | 9·8·8·8·7 | 신규 dark_heart | l_darkheart |
| broom_26 | 드래고니아 | 전설 | 9·7·8·8·8 | 신규 dragon | l_dragon |
| broom_27 | 블러디 플라이 | 전설 | 10·8·7·7·7 | 신규 vampire2 | l_butterfly |
| broom_28 | 아홉꼬리 빗자루 | 전설 | 7·10·10·10·10 | 신규 gumiho | l_foxfire |
| broom_29 | 차큐스 1000 (엔젤) | 전설 | 8·8·8·8·8 | 신규 angel | l_angel |
| broom_30 | 차큐스 1000 (데빌) | 전설 | 9·9·9·9·6 | 신규 demon | l_devil |

- 획득권 아이템 설명은 `brooms.yml`의 `description`에 넣었다. 플러그인은 아직 읽지 않는다(획득권 아이템 만들 때 사용).
- 모든 신규 모델은 기존 bbmodel 형식 그대로다: 그룹 `broom`, `p_pilot`, 64×64 내장 텍스처. 애니메이션 5개는 broom_7과 같은 uuid를 재사용한다.

## 2. 코드 변경
| 파일 | 내용 |
|---|---|
| `plugin/.../BroomType.java` | 등급별 배수(`tier`)를 없앴다. yml 값을 그대로 쓴다. 등급은 일반/희귀/유일/전설로 정규화한다(예전 이름 기본→일반, 고급→희귀). `climb` = 공통 `climb-power` |
| `plugin/.../BroomService.java` | `CONFIG_VERSION = 2`, `upgrade()`: 예전 파일을 백업하고 새 기본 파일로 교체, 빗자루 목록 밖의 서버 설정은 유지 |
| `plugin/.../brooms.yml` | 30종 (위 표), `config-version: 2`, 공식 주석 |
| `plugin/.../plugin.yml` | 설명 문구 10종 → 30종 |
| `mod/.../BroomPhysics.java` | ① 스페이스 상승은 유지한다(2026-10-17 사용자 요청). 시선 상승과 더해져서, 위를 보고 W + 스페이스를 같이 누르면 더 빨리 오른다. ② 새 필드 `flightPitch`가 시선 pitch를 틱마다 `maxTurn`만큼만 따라간다(좌우와 같은 값: 최고 속도에서 ×0.6, 부스트 중 ×0.6). 첫 틱은 시선과 같은 값으로 시작한다. 상승·하강 속도 계산은 이제 camPitch 대신 flightPitch를 쓴다. ③ `balanceCap() = 90 + 3·단계`(단계 = 1 + (stability−0.6)·7.5, 1~10으로 자름). 시작 균형 = cap, 피로 곡선 `maximumBalance()`도 100 대신 cap 기준 |
| `mod/.../BroomHud.java` | 균형 아치 = balance / balanceCap (시작하면 가득 참) |
| `mod/.../api/BroomApi.java` | `gauges()`가 수치에서 단계를 되돌려 계산해 `단계/10`을 준다. 장비창은 ×10을 칸 수로 쓰므로 1~10칸이 기획 단계와 같다. 예전 `norm()` 삭제 |
| `mod/.../BroomModels.java` | 주석만 |
| `mod/.../broom_trails.json` | 궤적 키 25개, 빗자루 30개 연결. cost는 vfx.json에서 계산(Σ rateOverDistance×평균 수명, constCost = Σ rateOverTime×평균 수명) |
| `mod/assets/chacademy_motion/brooms/` | bbmodel 30개 (1.1 MB) |
| `magic-codex-fabric/.../BroomUiLink.java` | `owned()` 상한 `limit(10)` → `limit(30)` |
| `BroomPhysics.caught()` | 공중에서 다시 잡으면 `flightPitch`를 초기화(그 순간 시선에서 시작) |
| `BroomServiceSafetyTest` | 보유 수 기대값 10 → 30 |
| 테스트 | `BroomResponseTest`에 `pitchFollowsLookAtTurnRate`, `noClimbKeyAndBalanceCapFollowsStabilityLevel` 추가 |

## 3. 궤적 팩 `BroomTrails` (새 버전)
- 원본: `tools/vfx-src/BroomTrails/`
  - `rare_layers.js`, `unique_layers.js`, `legend_layers.js`, `pack_footer.js`
  - `BroomTrails.js`, `BroomTrailsWood.js`, `BroomTrailsUnique.js`, `BroomTrailsLegend.js`는 `build_pack.py`가 위 네 파일을 이어 붙인 자동 생성본이다.
  - 텍스처 생성기: `gen_trail_tex_rare.py`, `gen_trail_tex_unique.py`, `gen_trail_tex_legend.py`
  - 미리보기 생성: `make_previews.py`(빗자루별 비행 미리보기), `gallery.py`(탭 갤러리)
- 팩 4개, 효과 ID `claude:<팩 폴더 소문자>/<키>`:
  - `broomtrails`: basic, rare_plus, rare_gold, wave1, wave2
  - `broomtrailswood`: leaf_oak, leaf_cherry, leaf_birch, leaf_mahogany, leaf_bamboo, galaxy
  - `broomtrailsunique`: u_dark, u_classic, u_emerald, u_crimson, u_sonic, u_holo, u_twin
  - `broomtrailslegend`: l_heart, l_darkheart, l_dragon, l_butterfly, l_foxfire, l_angel, l_devil
  - 빌드는 `tools/vfx-src/BroomTrails/build_pack.py`. 팩마다 쓰는 재질과 텍스처만 들어간다.
- 시스템: 궤적 키마다 `<키>`와 `<키>_lite` 한 쌍, 전부 `role static`, `loop`. 원점 = 솔 끝, +Z = 빗자루 코. 예전 `legend`(별빛 오로라)는 빼고, 전설 7종은 전용 궤적으로 바꿨다.
- 4개 모두 `client_compat check` OK.
- 새로 쓰는 기능: 메시 입자(사각 메시 x축 90° = 에너지 고리), 플립북(잎·깃털·컨페티 뒤집힘, 나비 날갯짓, 불꽃), 알파 블렌드 색 텍스처(잎·깃털·검은 하트·연기), 로컬 레이어 + orbitalY + trail(아홉꼬리 여우불 구슬). 모두 클라이언트 지원 목록 안에 있다.
- 입자 비용(이동 1블록당): 희귀 3.5~12.8, 유일 6.5~12.8, 전설 9.3~19.5 (+정지 4.2, 여우불 7.3). `TrailPlanner` 예산(2000)과 lite 전환 규칙은 그대로다.

## 4. 실게임에서 확인할 것 (우선순위)
1. 상하 선회: 위를 보고 W를 눌렀을 때 기수가 선회 수치 속도로 올라가는지. 바이올렛 트윈테일(선회 1)은 아주 둔해야 정상이다. 스페이스만 눌러도 오르고, 위를 보고 W와 스페이스를 같이 누르면 더 빨리 올라야 한다.
2. 에너지 고리(에메랄드·크림슨·쏜살같은·트윈테일)
   - 고리가 빗자루 축에 수직으로 서는지.
   - 월드 입자라도 메시 방향은 현재 basis를 따른다(스펙 §4.7). 그래서 급선회 때 남은 고리가 같이 기울 수 있다. 수명이 0.3초라 허용했다.
3. 아홉꼬리 여우불 구슬: 로컬 레이어 + rateOverTime이라 **정지·저속에서도 보인다**(등급 표시 역할). 숨기려면 모드 쪽에서 속도 조건이 필요하다(코드 작업 따로).
4. 잎·깃털·컨페티는 알파 블렌드다. 밤이나 물속에서의 밝기, 어두운 연기(차큐스 500)가 밝은 하늘에서 보이는지.
5. 장비창: 30종 6페이지, 게이지 칸 수 = 기획 단계.
6. `brooms.yml` 자동 교체 로그와 `brooms.yml.v1.bak` 생성.

## 5. 한계·남은 일
- 내 궤적 / 남의 궤적 투명도 분리 불가(이전 문서와 같음).
- 획득권 아이템(설명 사용)은 아직 없다.
- `levels` 키는 참고용이다. 플러그인은 수치 키만 읽는다.
