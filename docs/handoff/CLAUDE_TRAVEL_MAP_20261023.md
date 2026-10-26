# Claude 2026-10-23 — 지도 이동 메뉴 (공개 지점 해금·개인 지점·서버 간 이동) + 공간 도약 연결 (미빌드)

브랜치 `claude/travel-map-20261023`, 기준 `claude/book-ui-20261023` @ 346c0fb (= review/integrated-release-20261023-task7 @ af1ed3e + 책 UI 3커밋). 기존 최신 패치는 건드리지 않고 새 파일 위주로 더했습니다.

**함께 배포해야 하는 것**: MagicCodexBridge (학교·야생 모든 서버) + MagicDiscovery (학교·야생) + Velocity 파티 플러그인 + 클라이언트 MagicCodex 모드. 새 채널: `magiccodex:travel_request/response` (클라↔서버), `chacademi:travel_link` (서버↔Velocity).

## 0. GPT 빌드 전 주의

- 이 세션은 Maven 저장소에 접근할 수 없어 **Paper / Fabric / Velocity 컴파일을 못 했습니다.** 순수 Java 부분(프로토콜·설정 파서·판정·지도 계산)은 javac 로 컴파일하고 아래 테스트 17개를 돌려 통과했습니다. 호스팅에서 처음 빌드할 때 컴파일 오류가 나면 이 문서의 파일 목록 안에서만 고치면 됩니다.
- `TravelStoreTest`(SQLite 상태 기계 5개)는 sqlite-jdbc 가 없어 여기서 못 돌렸습니다 — 호스팅 `:magic-codex-paper:test` 에서 확인해 주세요.
- Yarn 1.21.4 API 가정: `DrawContext.enableScissor` 가 현재 행렬을 적용함(다른 화면들과 같은 방식), `TextRenderer.draw(String, …, TextLayerType, bg, light)`, `Camera.getRotation()`.

## 0-3. 2026-10-24 밤 수정 (미니맵 안 보임 · J 1초 지연)

- **미니맵이 비어 있던 원인**: 접속 순간 서버가 보낸 MARKERS(월드 포탈 + 미니맵 키)가, 클라이언트가 채널을 등록하기 전이라 `modded()` 에서 버려짐 → 키가 없어 지형 샘플링이 시작되지 않았음. `PlayerRegisterChannelEvent` 에서 다시 보내도록 수정(ShinyBridge 와 같은 방식), `/지도`·J 로 열 때도 다시 보냄. 클라도 지도 상태를 받으면 지금 서 있는 탭의 키로 샘플링을 시작(이중 안전).
- 샘플링 속도: 틱당 3청크 → 틱당 최대 48청크·2.5 ms 예산 (시야 거리 안이 1~2초에 채워짐). 채워지는 타일은 0.25초마다만 다시 올림.
- 지형이 보일 때 격자는 더 흐리게.
- **J 지연**: 창 상태를 만들 때마다 개인 지점을 DB 에서 읽고, DB 연결 확인도 매 쿼리마다 왕복했음 → 개인 지점을 접속 시 캐시(지정·삭제 때 갱신), 연결 확인은 5초 쉬었을 때만. 클라는 J·상단 메뉴 "이동" 을 누르는 즉시 창을 열고(잠깐 "불러오는 중…"), 서버 응답으로 채움. 서버가 5초 안에 답하지 않으면(모드 없음·공유 DB 없음 등) 창을 닫음. OPEN 재요청 제한 1초 → 0.2초(빨리 다시 열어도 응답).

## 0-2. 2026-10-24 오너 추가 결정 반영 (세 번째 커밋)

| 결정 | 반영 |
|---|---|
| 웨이포인트 VFX = 오너 PC `차카데미 vfx_Claude\WaypointGlobe` | 팩을 `portable-vfx-runtime/assets/WaypointGlobe/` 에 넣음 (vfx.json + textures + NOTES). 클라가 PortableVFX **클라이언트 API**(빗자루 궤적과 같은 방식, 리플렉션)로 `claude:waypointglobe/idle` 을 48블록 안 웨이포인트에만 로컬 재생(56블록 밖 끔), 등록 순간 `claude:waypointglobe/activate` 1회. 서버는 재생하지 않음. **클라이언트 PortableVFX 효과 폴더에 WaypointGlobe 팩 배포 필요** |
| 미등록 유저에게 "웨이포인트" 글씨 | 퀘스트 목표 지점 글씨처럼(MagicCodex 폰트, 검은 외곽선, 은은한 빛) 지구본 위 2.95 m, 32블록 안. 등록한 지점에는 안 뜸. 이전 링 표시는 VFX 로 교체 |
| 가까이 가면 자동 등록 + 이름 알림, 지도 열려 있어도 | 알림 카드 "웨이포인트 등록 · <설정한 이름>", 지도 창이 열려 있으면 창 안 알림 줄에도 같은 문구 |
| 학교 타운맵 대신 미니맵, 지도 모드 없음 | 새 `TravelTerrain`: 청크가 로드될 때 틱당 3청크씩 지형 색(바닐라 지도 색 + 높이 음영 + 물 깊이)을 512×512 타일에 기록, 백그라운드로 `config/magiccodex/travel-map/<서버주소>/<서버/세계/세계UID>/r.X.Z.png` 저장. 학교·야생 탭 모두 `map: terrain`. 가 본 곳만 그려지고 나머지는 격자. 월드 스캔·큰 이미지 생성 없음 |
| 개인 지점은 야생·학교에서만 | 지도 탭이 있는 세계(학교·야생)만 허용 — 아레나·던전 세계는 거절 (기존 규칙 그대로) |
| 지도 단축키 기본 J | `UiKeys.Action.MAP`(키 설정에서 변경 가능, UI 단축키 칸 10개로 간격 조정), 누르면 서버에 OPEN 요청 → `/지도` 와 같은 창 |
| 야생2 는 아직 없음 → 준비 중 | `servers.wild2: enabled: false, closed-text: 준비 중입니다` → 탭 화면·지점에 "야생2 · 준비 중입니다" |
| 공간 도약 조건 = 공개 웨이포인트 **1곳** | discoveries.yml `state.travel_waypoints: 1`, 도감 파일(135) 문구, condition-inventory 갱신 |
| 위치 지정은 마나·쿨 없음, 이동시에만 | mana-spells.yml(소스·운영본) spatial_leap `mana-cost: 0, cooldown-seconds: 0` (PortableVFX spell-catalog 135 도 0/0, 도감 파일 cast 0/0). 개인 지점 이동만 `travel.yml rules.personal-travel-mana: 100`, `personal-travel-cooldown-seconds: 60` — 별도 쿨 키 `spatial_leap_travel` 이라 마법 칸은 안 잠김. 이동 실패 시 환불 |
| 야생 기본 스폰 1곳: 쿨 없음, 학교에서만, 야생에서는 불가, Multiverse setspawn | travel.yml `points.wild_spawn: {server: wild, world: world, spawn: true, from: [school]}` — 발견 필요 없음, 월드 포탈·공간 도약 개수에 안 들어감, 이동 쿨 없음, 도착 위치 = 도착 순간의 그 세계 스폰(`World#getSpawnLocation`, MV setspawn 이 바꾸는 값). 지도 위 위치는 각 서버가 15초마다 공유 DB 에 올리는 세계 스폰. **야생 월드 이름이 world 가 아니면 travel.yml 의 world 를 바꿔야 함** |

추가 테스트: 야생 스폰(학교에서만·발견 불필요·포탈 없음), 야생2 준비 중 문구, 세계 스폰 공유 포맷, OPEN 요청·닫힌 탭 패킷 → 순수 테스트 21개 통과.

## 0-1. 2차 검토 반영 (같은 브랜치 두 번째 커밋)

별도 검토에서 나온 것 반영: 창 컴파일 오류 1곳(double→float), 월드 포탈을 AFTER_ENTITIES 로(공용 버퍼가 그 뒤에 그려지도록), 개인 지점 이동 과금은 마나 트랜잭션만(발견 시전 카운트·아티팩트 시전 효과 안 탐), 환불 시 저장·dirty 표시, 만료된 개인 지점 이동 표는 도착 서버에서 환불, 도착 서버가 90초 기다려도 캐릭터 정보가 안 오면 표 FAILED, 덮어쓰는 만료 표도 실패 로그, 출발 서버 응답 대기 20→45초, 지도 클릭 판정 위치, 저장소 거절 콜백은 항상 다음 틱, 프록시 '이동 중' 표시는 60초 지나면 무효, 발견 관리 자동완성에 state.travel_waypoints.
남은 한계(문서화): 과금 직후~이동 전 사이에 접속이 끊기면 그 1회 마나·쿨은 돌려주지 않음 (온라인일 때만 환불 가능).

## 1. 바뀐 파일

| 영역 | 파일 | 내용 |
|---|---|---|
| 프로토콜 | `magic-codex-protocol/.../TravelProtocol.java` (새) | 창 상태(탭·지점·현재 위치)·이동 요청·결과·월드 포탈 표시 |
| 프로토콜 | `magic-codex-protocol/.../TravelLinkProtocol.java` (새) | 서버→프록시 TRANSFER, 프록시→서버 FAILED |
| Bridge | `TravelBridge.java` (새) | /지도, 해금 판정, 이동(같은 서버/다른 서버), 도착 처리, 개인 지점 API, 관리 명령 |
| Bridge | `TravelStore.java` (새) | 공유 MariaDB 저장소 (SQLite 로 몰래 안 바뀜) |
| Bridge | `TravelModel.java` (새) | travel.yml 파서·판정 (Bukkit 없음, 테스트됨) |
| Bridge | `MagicCodexBridge.java` | TravelBridge 생성·종료, `inCombat(p)`, `travel()` 접근자, 공간 도약 연결 |
| Bridge | `ManaBridge.java` | `spatialLeapCast` 디스패치, `chargeSpatialLeapTravel`, `refundSpatialLeap` |
| Bridge | `resources/travel.yml` (새), `resources/mana-spells.yml` + `deployment/operating-config/MagicCodexBridge/mana-spells.yml` | spatial_leap `enabled: true` (ID·이름·권한·마나 100·쿨 60초 그대로) |
| Discovery | `MagicDiscovery.java` | 상태값 허용 목록에 `state.travel_waypoints` 추가 |
| Discovery | `resources/discoveries.yml` | spatial_leap 항목 추가: `state.travel_waypoints: 3` |
| Velocity | `TravelRelay.java` (새), `PartyPlugin.java`, `KyudichiQueue.java` (`travelBlocked`) | 서버 옮기기만 담당 |
| 클라 | `TravelClient.java`, `TravelScreen.java`, `TravelMapMath.java`, `TravelWorldMarks.java` (새), `MagicCodexClient.java` (초기화 1줄) | 지도 창, 월드 포탈 표시 |
| 클라 | `textures/gui/travel/*.png` (승인 PNG 13장, 투명 여백만 잘라냄 — 픽셀 그대로, main_frame 은 원본 그대로), `textures/gui/travel/world/*.png` (스토리 포탈 그림 6장 복사) | |
| 클라 | `default-spells/135_spatial_leap.yml` 설명·발견 문구, `known-hashes.tsv` 새 해시, `DefaultSpellMigration.VERSION` | 클라에 저장된 135 파일이 새 문구로 갱신됨 (직접 고친 파일은 안 건드림) |
| 테스트 | `TravelModelTest`, `TravelProtocolTest`, `TravelStoreTest` (paper), `TravelMapMathTest` (fabric) | |

## 2. 동작

### 창 (클라)
- `/지도` 로 엽니다 (서버가 연다 — 아공간 수조처럼). 단축키는 아직 없음(질문 4).
- 승인 PNG 그대로: 정사각 메인 창(main_frame, 가운데 투명 → 지도는 뒤에 그림), 위 띠 아래쪽에 탭 **학교 / 야생1 / 야생2** (글자는 코드, travel.yml 순서·이름), 위 띠 위쪽에 범례(현재 위치·공개 지점·개인 지점·미발견)와 닫기, 아래 띠에 축소·확대·전체 지도 / 고른 장소 이름·상태 / **이동하기**(button_blank).
- 목록은 기본 접힘. 창 오른쪽 가장자리 탭(drawer_expand/collapse)을 누르면 drawer_panel 이 **창 바깥 오른쪽**으로 열림. 배율은 "펼친 상태가 화면에 들어가는 크기" 하나로 고정 → 펼쳐도 지도 크기·비율 그대로, 창이 왼쪽으로 미끄러져 전체가 화면 안에 남음.
- 목록: 작은 아이콘 + 이름 + 얇은 구분선, 탭별 묶음 머리글, 개인 지점 맨 위, 긴 이름은 "…" 로 자름, 사용 불가 사유는 마우스 올리면 표시.
- 지도·목록 어느 쪽에서 골라도 둘 다 선택 표시 + 그 지점으로 부드럽게 이동·확대(다른 탭 지점이면 탭 전환). 선택만으로는 안 움직이고 **이동하기**(또는 Enter)를 눌러야 이동. 휠 확대·축소(커서 기준), 드래그 이동, 집 버튼 = 전체 지도.
- 표시: 현재 위치(화살표, 바라보는 방향으로 회전), 공개 지점(빛나는 링), 개인 지점(핀), 미발견(자물쇠), 쓸 수 없는 지점(회색 + 사유).
- 이동 중에는 버튼이 "이동 준비 중…/서버 이동 중…" 으로 잠김 (중복 클릭 방지, 서버도 따로 막음).

### 지도 그림
- **학교**: 고정 타운맵 PNG. 그림 경로·크기·모서리 3곳의 월드 좌표를 travel.yml 에만 둠 → 그림을 바꿔도 코드 수정 없음 (회전·뒤집힘도 세 점으로 처리). 지금은 `map: none` (예시 지도를 운영 지도로 넣지 않음) → 좌표 격자 + 지점만 보임. 그림이 리소스팩에 없으면 자동으로 격자.
- **야생1·2**: `map: terrain` 이지만 **지형 그리기는 미구현** (§5 보고). 지금은 좌표 격자 위에 지점·현재 위치만. 지형 캐시 키는 이미 `서버/세계/세계UID` 로 나눠 보냄 (서버·세계·초기화가 섞이지 않게).
- 열 때 월드 스캔·큰 이미지 생성 없음.

### 공개 지점 (서버 판정)
- travel.yml `points` 에 ID·이름·서버·세계·좌표·반경. 0.5초마다 **각 서버가 자기 지점만** 같은 세계의 플레이어와 거리 비교(가로 반경 + 높이 −2.5~반경). 처음 들어오면 `codex_travel_unlock` 에 INSERT IGNORE → 그 행을 만든 쪽만 "새 이동 지점 발견" 알림. 클라는 해금을 말할 수 없음.
- 이동 요청 때 해금 여부를 DB 에서 **다시** 읽어 확인 (캐시·클라 값 안 믿음).
- 월드 포탈 표시: 서버가 지금 세계의 지점 목록만 보냄, 클라가 **64블록 안**만 그림(이름은 24블록 안). 스토리 목표 포탈 그림 재사용, 발견 = 푸른 금빛, 미발견 = 흐린 회색 링, 해금 순간 한 번 번쩍. 서버 틱 부담 없음, PortableVFX 이펙트 재생 없음.

### 개인 지점 + 공간 도약 (오너 확정 2026-10-23)
- 플레이어당 전 서버 1개 (`codex_travel_personal` uuid PK). **공간 도약(135, spatial_leap) 시전 = 지금 위치를 개인 지점으로 새김**, 새로 새기면 교체.
- UI 패킷으로는 등록 불가. 등록 경로는 서버 API `TravelBridge.setPersonal(player, location, source, done)` 하나 (공간 도약 시전, 테스트용 관리자 명령).
- 등록·지도에서 개인 지점으로 이동 **둘 다 마나 100 · 공간 도약과 같은 재사용 대기 60초** (기존 시전 트랜잭션 그대로: 권한·쿨·마나 확인 → 차감). 등록 저장 실패·이동 실패 시 마나·쿨 되돌림. 마법을 안 배웠으면 개인 지점 이동 불가.
- 지도에 없는 세계(탭이 없는 세계: 아레나·네더 등)에서는 새길 수 없음. 마법 금지 구역(`chacademi-magic`)이면 기존 시전 검사로 막힘.
- 아이콘·마법 ID·권한·마나·쿨 그대로. 바뀐 것: mana-spells.yml `enabled: true`, 도감 설명 문구 2줄.

### 공간 도약 획득 조건 (오너 확정 2026-10-23)
- "지도 이동의 서로 다른 공개 지점 3곳 발견하기". 개수 = 공유 DB `codex_travel_unlock` 의 (uuid, point) 행 수 → 같은 곳 재방문·재접속·서버 이동은 더 세지 않음, 개인 지점은 다른 테이블이라 제외, 모든 서버 누적.
- 해금 저장이 성공(CLAIMED)했을 때, 그리고 **접속해서 해금 목록을 불러올 때마다** 기존 발견 시스템에 `state.travel_waypoints = 개수` 를 보냄 (수호자의 서약 `state.friends` 와 같은 방식, 멱등). 3 이상이면 기존 발견 흐름이 1번만 배우게 하고 기존 "새로운 마법 발견" 알림. 발견 세션이 아직 로딩 중이면 2초 간격 10번 재시도.
- 관리자 `해금` 으로 넣은 행도 개수에 들어감.

### 서버 간 이동
1. 출발 서버: 지점·해금·제한 확인 → `codex_travel_ticket` 에 표(목적지 서버·세계·좌표·방향, 90초 유효, PENDING) → 프록시에 TRANSFER.
2. 프록시: 보낼 수 있는 서버인지(travel.properties), 큐디치 경기·매칭·경기장 이동 중인지, 이미 옮기는 중인지 → 목적 서버 **핑**(4초, 닫힘·정원 초과 즉시 실패) → 접속 요청. 실패하면 출발 서버로 FAILED(사유).
3. 목적 서버: 캐릭터 정보(PlayerStateBridge 리스)가 로드될 때까지 기다린 뒤 표를 ARRIVING 으로 가져감 → 세계 존재·UID(초기화) 확인 → teleportAsync(PLUGIN) → 성공했을 때만 DONE + 로그, 실패면 FAILED.
4. 출발 서버는 20초 동안 답이 없으면 FAILED (단, 이미 도착 처리된 표는 절대 덮어쓰지 않음 — 상태 조건부 UPDATE).
- 이동 중 로그아웃 → 표가 만료되어 다음 접속 때 FAILED 로 기록. 중복 클릭 → 서버 메모리 + DB(살아 있는 표 1개) 두 겹으로 거절. 같은 서버 안 이동도 같은 표·로그를 씀.
- 서버를 travel.yml 에서 빼거나 끄면 그 지점·개인 지점은 지우지 않고 "지도에서 빠진 서버예요 (id)" / "… 서버는 지금 이동할 수 없어요" 로 표시. 세계가 초기화되면(UID 다름) 개인 지점은 "세계가 초기화되어 그 지점은 쓸 수 없어요".
- 서버 상태: 각 서버가 15초마다 `codex_travel_server` 에 접속자·최대·세계 UID·travel.yml 해시를 씀 (45초 넘게 조용하면 닫힘으로 봄). travel.yml 이 서버끼리 다르면 상태·목록 명령에 표시.

### 기존 제한 (우회 안 함)
사망·관전 상태, 캐릭터 정보 로딩 전, 큐디치 경기 중(서버 `generalPresetLocked` + 프록시 `travelBlocked`), 큐디치 경기 중 순간이동 취소 리스너(cause PLUGIN 이라 그대로 적용), 빗자루는 이동 전 내림(귀환 마법과 같은 방식). 전투 중 제한·추가 쿨타임은 **설정만 있고 기본 꺼짐** (질문 1).

## 3. 관리자 설정·명령

`plugins/MagicCodexBridge/travel.yml` (모든 서버 같은 파일, `server-id` 한 줄만 다름):
```yaml
server-id: school            # 야생1 서버는 wild, 야생2 서버는 wild2
servers:
  school: { proxy: school, label: 학교 }
  wild:   { proxy: wild,   label: 야생1 }
  wild2:  { proxy: wild2,  label: 야생2, enabled: false }   # 서버가 생기면 true
tabs:
  school: { label: 학교, server: school, world: world, map: none }   # 타운맵이 오면 map: image + image: {...}
  wild1:  { label: 야생1, server: wild,  world: world, map: terrain }
  wild2:  { label: 야생2, server: wild2, world: world, map: terrain }
points:
  harbor_town: { name: 항구 마을, server: school, world: world, x: 120.5, y: 64, z: -340.5, yaw: 90, radius: 4 }
```
학교 타운맵을 넣을 때 (리소스팩 + travel.yml 만):
```yaml
  school:
    label: 학교
    server: school
    world: world
    map: image
    image:
      texture: magiccodex:textures/gui/travel/maps/school.png
      size: [2048, 2048]
      top-left: [-512, -512]      # 그림 왼쪽 위 모서리 = 월드 x, z
      top-right: [512, -512]
      bottom-left: [-512, 512]
```
Velocity `plugins/chacademi-party/travel.properties` (처음 실행 때 생성): `travel-servers=school,wild,wild2`. **party.properties `trusted-servers` 에도 wild2 를 넣어야** 야생2 에서 출발할 수 있습니다.

명령 (모두 한글, Brigadier 안내):
- `/지도` — 유저: 지도 창 열기
- `/서버관리 새로고침 이동` — travel.yml 다시 읽기 (서버마다)
- `/컨텐츠관리 이동지점 목록 | 서버상태 | 열기 [닉네임]`
- `/컨텐츠관리 이동지점 추가 <ID> <반경> <이름…>` (지금 위치·이 서버 파일), `위치 <ID>`, `반경 <ID> <값>`, `이름 <ID> <이름…>`, `켜기|끄기 <ID>`, `삭제 <ID> 확인` — 이 서버의 travel.yml 만 바뀜 → 다른 서버에 복사 후 새로고침
- `/유저관리 <닉네임> 이동지점 보기 | 기록 | 해금 <ID> | 잠금 <ID> 확인 | 개인지점 여기 | 개인지점 삭제 확인`
- `/서버관리 상태` 에 "지도 이동" 줄 (공유 DB 여부, 다른 travel.yml)

## 4. DB (새 테이블만, 기존 데이터 이관 없음)

player-state-database.properties (없으면 database.properties) 의 **MariaDB 필수**. MariaDB 가 아니면 지도 이동이 꺼지고 SEVERE 로그 (로컬 SQLite 로 몰래 저장 안 함). 서버 한 대 테스트만 `storage.allow-local-test-db: true`.

| 테이블 | 키 | 내용 |
|---|---|---|
| `codex_travel_unlock` | (uuid, point) | at, server — 발견한 공개 지점 (공간 도약 개수도 이것) |
| `codex_travel_personal` | uuid | server, world, world_uid, x y z yaw pitch, at, source |
| `codex_travel_ticket` | uuid | 진행 중/마지막 이동 1건 (PENDING→ARRIVING→DONE/FAILED) |
| `codex_travel_log` | id | 끝난 이동 전부 (성공/실패·사유) |
| `codex_travel_server` | server | heartbeat, 접속자/최대, 세계 UID, travel.yml 해시 |

CREATE TABLE IF NOT EXISTS 로 자동 생성. 서버·지점이 설정에서 빠져도 행은 지우지 않음.

## 5. 야생 실제 지형 지도 — 보고 (구현 전)

- 저장소 전체(코드·deployment·client-mods·dependencies)에 **지도 모드가 없습니다** (Xaero's/JourneyMap/VoxelMap/FTB Chunks/BlueMap/Dynmap/squaremap 검색 0건). 런처에 따로 넣은 지도 모드가 있는지는 오너 PC 연결이 안 돼 확인 못 함 → 질문 2.
- 재사용 가능한 모드가 없다면 직접 구현 범위·비용:
  - **A. 클라이언트 탐험 캐시** (권장): 청크가 로드될 때 맨 위 블록 색(MapColor)을 틱당 몇 청크씩 뽑아 512×512 지역 타일로 저장, 비동기로 `config/magiccodex/travel-cache/<서버>/<세계>/<UID>/` 에 PNG. 가 본 곳만 보임(안 가 본 곳은 격자). 서버 부담 0. 작업 약 1~1.5일 (샘플러·타일 캐시·창 렌더·메모리 상한·테스트).
  - **B. 서버 사전 렌더 타일**: 서버가 월드 파일을 읽어 타일 생성 후 리소스/HTTP 로 배포. 전체 지형이 보이지만 서버 디스크·CPU·배포 경로 필요, 월드 초기화마다 재생성. 약 2~3일 + 운영 작업.
  - **C. 기존 지도 모드 연동**: 런처에 Xaero's World Map 등이 있다면 그 캐시를 읽는 방식은 라이선스(Xaero 는 재배포·API 사용 조건 있음) 확인이 먼저.
- 지금 코드는 탭마다 `map: terrain` + 캐시 키(서버/세계/UID)까지 준비돼 있어, 어느 방식이든 창 쪽은 그림 소스만 붙이면 됩니다.

## 6. 기존 패치와 겹칠 수 있는 곳

- `MagicCodexBridge.java` onEnable/onDisable (책 UI 바로 아래 1~2줄), `ManaBridge.java` (fish_trap 디스패치 바로 아래 1줄 + 메서드 3개), `MagicCodexClient.java` (BookClient 다음 1줄), `PartyPlugin.java` (kyudichi 초기화 뒤 블록 + disconnect 1줄), `KyudichiQueue.java` (메서드 1개), `MagicDiscovery.java` (허용 상태 키 1개), `discoveries.yml` (항목 1개 추가), `mana-spells.yml` 운영본(enabled 1줄).
- 운영 서버의 discoveries.yml·mana-spells.yml 은 saveResource 로 덮이지 않으니 **운영 파일에 spatial_leap 부분을 직접 반영**해야 합니다 (discoveries.yml 항목 추가, mana-spells.yml enabled: true).
- PortableVFX `spell-catalog.yml` 의 135 는 `enabled: false` 그대로 — 시전 VFX 는 없음 (기능만). 카탈로그 모드 검사는 이 디스패치 앞에서 걸리지 않게 fish_trap 과 같은 위치에 둠.
- `/지도` 이름을 쓰는 다른 명령은 없음(검색 확인).

## 7. 테스트

통과 (여기서 실행): TravelModelTest 6 · TravelProtocolTest 4 · TravelMapMathTest 7 = 17
- 창 크기 11종(854×480 ~ 3840×2160, 4:3·16:9·21:9)에서 접힘/펼침 0~100% 모두 화면 안, 펼쳐도 배율·지도 크기 동일, 목록은 창 바깥 오른쪽
- 확대 5번 뒤 클릭 → 같은 월드 좌표 (회전·뒤집힌 그림 포함), 커서 기준 확대, 부드러운 확대 수렴, 선택은 확대만(축소 안 함)
- 긴 한글 이름 자르기, 바라보는 방향 화살표
- 설정 오류(없는 서버, 한 줄 위 기준점, 반경 범위), travel.yml 해시(키 순서·server-id 무관)
- 판정: 미발견·닫힌 서버·조용한 서버·정원 초과·꺼진 서버·빠진 서버·세계 초기화
- 패킷 왕복·잘못된 프레임 거절

호스팅에서 돌릴 것: TravelStoreTest 5 (최초 1회 해금·다른 서버 같은 해금, 개인 지점 전역 1개 교체, 중복 요청·늦은 실패가 성공을 덮지 않음·실패한 표는 도착 처리 안 됨, 만료 표 실패 기록, MariaDB 없으면 꺼짐).

인게임 확인 필요: 지점 진입 해금 알림 1회 / 재접속 유지 / 다른 서버에서 같은 해금 / 3곳째 해금 → 공간 도약 발견 알림 / 이미 3곳 이상인 유저 접속 시 해금 / 공간 도약 시전 → 개인 지점 교체 + 마나·쿨 / 개인 지점 이동 마나·쿨 공유 / 학교→야생 이동 성공 / 목적 서버 끔·정원 초과·이동 중 로그아웃·연타 / 창 펼침·확대 후 클릭.

## 8. 오너 질문 (미정 — 임의로 정하지 않음)

1. 지도 이동(공개 지점) 비용·별도 쿨타임, 전투 중 이동 제한? (지금: 없음, `rules` 설정으로 켤 수 있게만 해 둠)
2. 런처에 넣은 지도 모드가 있나요? 없다면 야생 지형은 §5 A/B/C 중 어느 쪽?
3. 던전·PvP(아레나) 세계에서 개인 지점 새기기 허용? (지금: 지도 탭에 없는 세계는 막힘 — 아레나·던전 세계는 자동으로 막힘)
4. 지도 단축키를 키 설정에 넣을까요? (지금 `/지도` 만)
5. 야생1·야생2 가 서로 다른 서버인지(지금 가정: wild / wild2 서버), 같은 서버의 다른 세계인지?
6. drawer_collapse.png 도 화살표가 ">" 방향입니다 — 펼친 상태에서 그대로 쓸지, "<" 그림을 받을지?
7. 첨부했다는 승인 시안(배치 그림)은 이번 zip 에 PNG 리소스만 있었습니다. 탭·범례·버튼 위치는 프레임 PNG 의 띠 구획에 맞춰 잡았으니, 시안과 다르면 시안을 보내 주세요.
