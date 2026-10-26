# [BUILD-READY] 오픈 최종 2차 — GPT 빌드·배포 지시 (Claude 2026-10-26)


브랜치 `claude/opening-final-20261025` 의 ****가장 최근 `[BUILD-READY]` 커밋****을 빌드한다 (커밋 메시지에 `[BUILD-READY]`).
기준: `70044a79` (2026-10-25 밤 운영 배포) 이후 모든 패치. 자세한 내용·파일 위치는 [CLAUDE_OPENING_FINAL_PATCHES_20261026](CLAUDE_OPENING_FINAL_PATCHES_20261026.md) (§번호), 오너 결정은 [CLAUDE_OPENING_PREP_REVIEW_20261025](CLAUDE_OPENING_PREP_REVIEW_20261025.md).

이 환경에는 Gradle·Paper·Fabric 이 없어 **컴파일·테스트를 못 했다** (Java 문법 검사·생성기 실행·운영 overlay `check` = "차이 없음" 만 함). 첫 빌드에서 컴파일 오류가 나면 해당 § 의 "확인 필요" 줄부터 볼 것. 처음 쓰는 Paper/Fabric API 는 각 § 에 적어 둠.

## 서버 구성 (오너 2026-10-26 15:28)
| 호스팅 | 서버 | 메모리 |
|---|---|---|
| 기존 32GB / i7-12700K | Velocity 1GB · 학교 12GB · PvP1 7GB · PvP2 7GB · 공용 MariaDB·Redis 유지 | |
| 신규 24GB / i9-14900K | 야생1 8GB · 야생2 8GB | |
- 튜토리얼 서버 삭제 — 튜토리얼 장소는 학교 서버 안.
- 야생1·2 는 다른 호스팅 → 공용 MariaDB·Redis(HuskSync)에 원격 접속: 기존 호스팅 방화벽에서 신규 호스팅 IP만 3306/6379 허용, 각 플러그인 database.properties·HuskSync config 의 호스트를 기존 호스팅 공인/사설 IP 로. Velocity `velocity.toml` 의 wild·wild2 주소도 신규 호스팅 IP. 지연이 생기는지(배낭 쪽 넘기기·서버 이동) 확인.
- 서버 id (오너 2026-10-26 16:34): 학교 school, **야생 wild1·wild2** (예전 wild → wild1). Velocity [servers] 이름도 wild1·wild2. travel.yml·chunk-claims·inventory-save·combat-lock·travel-items·donation.yml 의 servers 와 Velocity party.properties(trusted-servers·chat-servers·wild-server)·travel.properties(travel-servers) 전부 wild1 로. 야생은 새로 미니 옛 wild 저장 데이터 없음. (travel.yml·chunk-claims·inventory-save·donation 의 servers 목록과 같아야 함). PvP1·PvP2 의 server-id 는 아레나용(큐디치·결투) — 그 목록들에 넣지 않음.

## 0. 순서
1. `git fetch && git checkout <이 커밋>` → `python3 tools/operating_overlay.py check` 가 "차이 없음"인지.
2. 아래 §1 모듈 **전부** 빌드 (오늘 #94 한글 닉네임 패치가 거의 모든 플러그인의 `ChacaLink` 를 고쳐서, 70044a79 와 같은 모듈이 사실상 없다).
3. §2 서버·클라 짝을 **같이** 올린다 (프로토콜 바뀜 — 한쪽만 올리면 그 기능만 안 되거나 상점처럼 창이 안 열림).
4. §3 서버 설정·데이터 파일 교체, §4 손으로 할 것.
5. 서버 재시작은 오너 승인 규칙대로.

## 1. 빌드할 모듈 (전부)
| 모듈 | 결과물 | 놓을 곳 |
|---|---|---|
| `magic-codex-paper` | MagicCodexBridge | 학교·야생·아레나(·튜토리얼) `plugins/` |
| `magic-codex-fabric` (+`magic-codex-protocol`) | MagicCodex 클라 모드 — **전체 빌드** | 런처 `mods/` |
| `magic-mythic-paper` | ChAcademiaMagicMythic | 학교·야생·아레나 |
| `magic-discovery-paper` | MagicDiscovery | 학교·야생·아레나 |
| `chaca-npc-paper` | ChacaNPC | 학교 (NPC 있는 서버) |
| `housing-paper` | ChacaHousing | 학교·야생 |
| `chaca-portrait-paper` (+`chaca-portrait-protocol`) | ChacaPortrait | 깔린 서버 |
| `kyudichi-paper` | Kyudichi | 아레나(깔린 서버) |
| `chacademy-story/plugin` · `/mod` | ChacademyStory 플러그인 · 클라 모드 | 학교·야생 / 런처 `mods/` |
| `chacademy-motion/…-plugin` · `…-mod` | ChacademyMotion · 모션 모드 | 서버 / 런처 |
| `creature-spawns-paper` | ChAcademiaWildlife | 야생 |
| `tornado-event-paper` | ChAcademiaTornado | 깔린 서버 |
| `gimmick-paper` | 기믹 | 깔린 서버 |
| `chacademi-party-velocity` | 파티 (Velocity) | 프록시 `plugins/` |
| `portable-vfx-runtime` `:paper` · `:client` | PortableVFX | 서버 / 런처 |
| `ops-dashboard` (새 모듈) | 운영 대시보드 (`java -jar`) | 호스팅 — [CLAUDE_OPS_DASHBOARD](CLAUDE_OPS_DASHBOARD.md) |

## 2. 서버·클라 같이 올려야 하는 것
- 상점 응답 SHP4 (#98 교환) — Bridge + 모드. **한쪽만이면 상점이 안 열림.**
- 정보창 v3 (#87) · 인기도 · 상대 일러스트(ChacaPortrait + portrait-protocol + 모드) · 하우징 SHOWCASE (#108, ChacaHousing + Bridge + 모드).
- 청크 점유 #88 · 기숙사 점수 홀로그램 #106 · 확성기 #102 · 허수아비 표시 #107 (MagicMythic + 모드) · 동행 펫 #81 · 길잡이 별 #93 (스토리 플러그인+모드+Bridge).
- 전투 스펙·장신구 강화 (§34) — Bridge·MagicMythic·MythicMobs 스킬·클라 모드(EnhancementScreen·도감). 데이터팩 필요 없음.
- 뽑기 상자 API 상자 (§36) — Bridge + protocol + 클라 모드 (새 채널, 상자 PNG 5장 리소스 포함). 공통 지급 함수 RewardDelivery 로 우편 수령·낚시·퀘스트·연금술·환불 경로가 바뀜.
- NPC 통계 (§19 추가) — ChacaNPC + Bridge.
- 마력 재구성 (§37·§37 추가) — 재구성 프로토콜에 kind 가 붙음(장신구 재구성), 재구성·강화 창 아이템 미리보기. **Bridge + protocol + 클라 모드 같이.**
- 뽑기권 상자 3개 (§38) — 가구 보상 종류 추가 (Bridge + ChacaHousing).
- 아이템 5종 (§39) — Bridge·MagicMythic·ChacaPortrait. 클라 변경 없음.
- 리턴권·텔포권·인벤세이브 + 사망 시 배낭 다른 쪽 떨어뜨리기 (§40) — 텔포권 고르기 창 새 채널 `magiccodex:tp_pick_request/response`. **Bridge + protocol + 클라 모드 같이.** 공유 DB 새 표 `codex_travel_presence`·`codex_travel_death` 자동 생성. `travel-items.yml`·`inventory-save.yml` 자동 생성. 아레나 server-id 가 travel.yml `use-servers`·`servers` 에 없어야 함.
- 마법 힌트권 운명의 타로 (§41) — 새 채널 `magiccodex:tarot_hint` (서버→클라), Bridge + protocol + 클라 모드 (타로 PNG 35장 약 68MB 포함). `utility-items.yml spell-hint.tarot-ui`.
- 별풍선 후원 보상 (§43) — **서버 플러그인 `deployment/donation/donation-bukkit-2.0.2-allcounts.1.jar` → 학교·야생·아레나 `plugins/`**, **클라 모드 `donation-fabric-1.21.4-2.0.2-allcounts.1.jar` → 런처 `mods/`** (README 에 SHA-256). Bridge 는 donation 이 없어도 켜짐(/후원관리 만). 서버 간 공지에 새 종류 ANNOUNCE — 모든 서버 Bridge 같은 빌드.
- 운영자 행운의 뽑기판 100칸 (§44) — 새 채널 `magiccodex:draw_board`·`magiccodex:draw_board_action`. **Bridge + protocol + 클라 모드 같이** (판 PNG 5장 리소스 포함). 공유 DB 새 표 `codex_draw_board`·`codex_draw_item` 자동 생성 (database.properties 따름, 없으면 `draw-board.db`). 설정 파일 없음.

## 3. 서버 파일 (jar 가 안 덮어씀)
- `deployment/operating-config/**` 전부 overlay 대로 (MagicCodexBridge·MythicMobs·PortableVFX·ChacademyStory).
- 새 설정은 처음 켤 때 자동 생성: chunk-claims · popularity · megaphone · house-board · convenience-commands · audit · server-heartbeat · combat-lock · pet-companions(overlay) 등 — 각 § 의 기본값 확인.
- **ItemsAdder 실제 id 맞추기**: FINAL_PATCHES 부록 A (확성기·인기도 해제권·확정 뽑기권 3종·땅문서·가방 확장권 — 아이템은 운영에 이미 있음).
- NPC 일과표 85명: `docs/plans/npc-schedules/` → 학교 `plugins/ChacaNPC/npcs/` (먼저 places.yml 에 34곳 등록 — docs/plans/npc-places.md), `/서버관리 새로고침 NPC`.
- 뽑기 상자: `plugins/MagicCodexBridge/loot-boxes.yml` (overlay 는 꺼진 예시 상자만 — 실제 상자는 운영자가 `/상자관리 생성`·`관리` 로 만듦). 서버 `audit.yml` 에 새 종류 "뽑기상자" 반영(§36).
- 책 (#105): `MagicCodexBridge/books.yml` overlay 교체 → `/서버관리 새로고침 책` (§35, 블록 연결은 오너가 docs/plans/library-books.md 대로).
- 아이템 기능 (§37~§39): `MagicCodexBridge/loot-boxes.yml` 은 가구·낚시도구·몬스터 전리품 상자 3개가 들어간 overlay 로 교체(§38). `utility-items.yml` 은 자동 생성 → `/서버관리 새로고침 사용아이템`. `ChAcademiaMagicMythic/config.yml` 에 `mana-potion`·`health-potion`·`potion-cooldown-overlay` 키, `ChacaPortrait/config.yml` 의 `reroll.item-id` = `item:cutscene_ticket` 손으로 반영(§39). 바뀐 id: 귀환권 `item:home_ticket`, 인기도 해제권 `item:popularity_reset`, 체력 포션 `item:health_potion` (부록 A-2).
- API 상자 2종 + 호감도 선물 뽑기권 상자 (§42): loot-boxes.yml overlay 교체 → `/상자관리 새로고침`. 새 ItemsAdder 아이템 `item:gift_ticket`(오너가 만듦). 묶음(×5·×3) 아이템 최대 스택 확인.
- 별풍선 후원 (§43): `plugins/MagicCodexBridge/donation.yml` 자동 생성 (학교·야생·아레나 같은 내용) → 고치면 `/서버관리 새로고침 후원`. 서버 `audit.yml` 에 새 종류 "후원" (kinds 에 `후원: true`). `plugins/donation/config.yml` 의 `acceptAllDonations: true` 확인. 예전 Skript `api` 스크립트는 지워도 됨(남아 있어도 안 돎). 아레나(큐디치)는 `servers` 에 넣지 않음 = 후원 안 받음.
- 야생2 연결 (오너 2026-10-26): 서버의 `MagicCodexBridge/travel.yml` 은 jar 가 안 덮으므로 학교·야생1·야생2 모두 손으로 — `servers.wild2.enabled: true`, `points.wild2_spawn` (server wild2, spawn: true, from: [school]) 추가. 야생1 server-id: wild1 · 야생2 server-id: wild2, Velocity 이름도 같게. 야생2 세계 스폰은 `/mv setspawn`.
- **튜토리얼 서버 없음 (오너 15:23)**: 튜토리얼 맵은 school 안. 스토리 steps.yml·대화 전부 school 서버에만. Velocity `tutorial.properties` 의 tutorial-servers 는 비움 (FINAL_PATCHES §48·§49 추가). 위 §48 의 tutorial 서버 등록 안내는 무시.
- 스토리 장 공개 (오너 2026-10-26 "첫날엔 1장에서 막자"): 서버 `ChacademyStory/config.yml` 은 jar 가 안 덮음 → `chapter-release.open-through: 1`, `chapters.2.open: false` 로 손으로 (2장 여는 날 `/대화관리 장공개 열기 2`).
- 스토리 장 이름 (#104): ChacademyStory `steps.yml`.
- 마법 발견 조건 v2 (§45): **학교·야생·아레나 `plugins/MagicDiscovery/discoveries.yml`·`manual-conditions.yml` 을 저장소 파일로 교체** (안 바꾸면 옛 조건 그대로) → `/서버관리 새로고침 마법발견`. books.yml overlay(대기 책 16권 켬) · ChacademyStory `steps.yml` ch1-13 시작명령(길잡이 별) 같이. MagicDiscovery·Bridge·MagicMythic·모션 플러그인·클라 모드(도감 문구) 같은 빌드로.
- TAB 한글 닉 (#110): `deployment/tab/`.
- **메인 스토리 1장 다시 짜기 (§49, 튜토리얼 서버 없음)**: `deployment/operating-config/ChacademyStory/steps.yml` → **학교** `plugins/ChacademyStory/steps.yml`. 대화 `deployment/story-dialogues/` 전부 → 학교 `plugins/ChacademyStory/dialogues/` (ch1-1~ch1-6 은 학교에 이미 있는 실서버본 유지). `quest-server-id: school` 그대로. 넣은 뒤 `/서버관리 새로고침 스토리` → `/대화관리 단계 점검`. 스토리 플러그인 새 jar. 셀마 초상화 `vl_selma-neutral.png` 리소스팩 확인.
- **컷신 (런처)**: `deployment/cutscenes/ch1_harbor_arrival/` → 런처 `config/chaca_cutscene/ch1_harbor_arrival/` **새로 배포**. `deployment/cutscenes/ch1_chronicle/` → 런처 `config/chaca_cutscene/ch1_chronicle/` **새로 배포** (1장 끝 ch1-23 연대기). `ch1_ashen_night`(튜토리얼 맨 처음)·`magic_tutorial`(ch1-4)은 런처에 있는 그대로 — 건드리지 않음.
- 마법별 힌트 파일 (§46): `plugins/MagicCodexBridge/spell-hints.yml` 은 처음 켤 때 jar 에서 자동 복사 (이미 있으면 안 덮어씀 → 내용이 바뀌면 손으로 교체) → `/서버관리 새로고침 힌트`. MagicCodexBridge·MagicDiscovery(`hintProgress`)·ChacaNPC(`spellHint`) 같은 빌드로 (옛 jar 와 섞여도 예전 자동 문장으로 동작).
- 몹 스펙 #109 (§47): MythicMobs `Skills/chacademi_mob_skills.yml`·`Mobs/chacademi_mob_minions.yml` 을 overlay 로 **교체**, 야생 `Mobs/chacademia_custom_wild.yml` 은 교체 말고 `python3 tools/mob_spec.py apply-mobs <서버 파일>` (Health·Damage 줄만) → `/mm reload`. `ChAcademiaMagicMythic/config.yml` `pvp.mob-magic-armor: true` (몹 스킬에 마법 방어, 없어도 기본 true).

## 4. 손으로 할 것
- MCPets 커스텀 펫 276개 파일 삭제 (#81, `deployment/mcpets-companion-removal.json`).
- ModelEngine: `training_dummy.bbmodel` → `plugins/ModelEngine/blueprints/` → `/meg reload` → 리소스팩 병합 (#107).
- PortableVFX 새 팩 (`portable-vfx-runtime/assets`) → 런처 `config/portablevfx/effects/` (FINAL_PATCHES §5, VFX 문서).
- Velocity `bungee-plugin-message-channel = true` 확인 (#95 공지·#102 확성기 전 서버 전달).
- 튜토리얼 서버 하나 `tutorial` (오너 2026-10-26, FINAL_PATCHES §48): velocity.toml `[servers]` 에 `tutorial` 넣고 첫 접속 try 로 (예전 두 대 줄 지우기), 프록시 `tutorial.properties` 가 이미 있으면 `tutorial-servers=tutorial` 로 고치기, ChacaPortrait `auto.server-ids: [school, tutorial]` (튜토리얼 서버는 `server-id: tutorial`).
- HuskHomes `/home`·`/sethome` 권한 끄기 권장 (#95).
- DB: 학교·야생·아레나 `database.properties` 가 같은 MariaDB (공유 표 자동 생성: codex_audit_log · codex_player_stats · codex_popularity* · codex_chunk_claim/trust · codex_housing_showcase · codex_server_status …). 안 쓰는 `codex_artifact_pity` 는 지워도 됨.

## 5. 전투 스펙 배포 주의 (§34)
- 서버의 `MagicCodexBridge/config.yml`·`enhancement.yml` 은 jar 가 안 덮음 → 새 값으로 **교체** (enhancement.yml 이 옛 것이면 장신구 강화가 꺼져 있음). MagicMythic `config.yml` 도 새 키(마법 방어 상한 등, `limits.novas: 4096`, pvp.mob-magic-armor, 포션) 반영.
- 이미 접속했던 계정은 최대 마나 100·회복 5 가 저장돼 있음 → 접속자 전체 `/유저관리 <닉> 스탯 최대마나 설정 300`, `스탯 마나회복 설정 8` (또는 GPT 가 일괄 처리 방법 판단, 오너에게 보고).
- 운영 jar 에 저장소에 없는 장신구 강화가 이미 있으면 오너에게 알리고 이 구현과 합칠 것 (오너: "이미 있다").

## 6. 오너 검수 대기 (빌드에는 포함, 수치는 바뀔 수 있음)
- 전투 스펙: 재계산 결과 적당 vs 한계 1.90배 (강화 상승량 = 단계값 × 코어 품질 배율 "섞기", §34 추가) (목표 1.6배 초과 — 펫 도감 20·마나 회복 8 유지 때문). 대충끼리 95초. 조정안은 docs/plans/combat-spec.md.
- 책 67권 (#105), 아공간 배낭 잠긴 쪽 (§33), 뽑기 상자 판단 6가지(§36: 10개 대표 등급 = 최고 등급, 열쇠 10개 미만이면 10연 거절, 안 보이는 잠금 해제 쪽엔 안 넣고 우편, 쪽 넘김 중 퀘스트·연금술 잠깐 거절, 환불 넘치면 바닥 대신 우편, 기록 종류 이름).

- 뽑기권 상자 확률 (§38): 낚시 일반 60.12/고급 28.06/유일 9.82/전설 2.00 %, 몹 전리품 1·2·3성 70/24/6 % (내용물은 임시 바닐라), 가구 97종 같은 확률·가진 것 다시 뽑기.
- 재화 주머니 금액표 (§39, 대박 15,000 G 오너 결정, 평균 약 1,945 G). 마법 힌트권은 타로 UI 가 저장소에 없어 깨달음의 빛 연출로 임시.

- 사망 시 배낭 처리 (§40): 인벤세이브 없이 죽으면 잠금 해제된 다른 쪽까지 전부 죽은 자리에 떨어짐(오너 확정). 첫 운영 때 사망 1번으로 확인 — 동기화 중이면 8초까지 재시도 후 그대로 둠, DB 오류면 배낭 잠김(운영자 처리, `bag-death-drops/` 증거 파일).

## 6-2. 코드 점검 (2026-10-26 16:15~, 새 검토자 5명, 치명 0)
- 고침: 상점 판매·교환이 배낭 쪽 넘기는 중 복제(ShopBridge) · 청크 점유 표 키에 server 추가(야생1·2 같은 세계 이름 충돌, 열 때 ALTER PK 1회) · 상대 일러스트 상자 계산 IO 스레드로 · MagicMythic `limits.novas 4096`.
- 보고만(문서 `docs/handoff/review-20261026-*.md` 5개): 상대 일러스트 초당 4회 요청 시 DB 읽기 누적, 대시보드 기록 검색 풀스캔, "한 방 처치" 판정 느슨, 책 표시 5초 타이머 규모 커지면 부담, 시작 때 DB 연결 메인 대기 15~20초(원격 DB 느리면 켜기 지연).
- 권장 테스트: 10,000 후원 즉사 1번(복제 없는지), 야생1·야생2 같은 좌표 청크 점유.

## 7. 컴파일 확인 상태
- 70044a79 이후 바뀐 Java 352개 전부 문법 검사 통과. 타입 검사는 못 함 → 첫 빌드 컴파일 오류는 각 § "확인 필요" 참고, 고친 뒤 오너에게 보고.
