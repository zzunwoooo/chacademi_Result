# 오픈 전 메모 1차 개선 (Claude, 2026-10-25) — 빌드 안 됨, 커밋 안 됨

기준: 로컬 브랜치 `work/opening-prep-20261025` = `claude/travel-map-20261023` + gimmick-simplify / shop-admin-inventory / galaxy-bag / mileage-shop / notification-skin / npc-model 브랜치 + `review/affinity-gifts-20261023-task7` 을 합친 것(충돌은 등록 줄 합치기, BookReadStore 는 review 판). 그 위에 오늘 변경은 **커밋하지 않은 상태**.
`claude/story-steps-20261020`, `npc-buttons`, `wildlife-spawner`, `hires-item-world` 는 codex 통합본과 충돌해 합치지 않음(통합본이 들어 있다고 봄). 단 story-steps 의 길잡이 별 연결(63459697)은 빠져 있어 수동 이식함(§07).

## 빌드·검증 상태
- 이 환경은 Gradle/Paper/Fabric 의존성이 없어 **빌드·단위 테스트 미실행**. 변경 Java 117개 문법 검사 통과, 독립 검토 3건(존재하지 않는 API·시그니처·등록 순서·프로토콜 switch·리소스) 통과. magic-codex-protocol 은 단독 컴파일 성공.
- 새 테스트(실행 필요): SpellReviewRunnerTest, AppraisalRollTest(추가분), BookIntelligenceTest, DefaultSpellMigrationTest(추가분), AdminCommandRulesTest(수정), TitleAwardsTest, AffinityStoreTest(추가분), CoreTest(liked), ArtifactPityTest, TutorialShopTest, OperatorCommandsTest, PortraitSettingsTest, TrainingDummyTest, ChapterGateTest, TutorialRouterTest, QuestHudPlacementTest, FollowupFixesTest(추가분), housing SettingsTest(수정), KyudichiQueueProtocolTest(추가분), BroomCastGuard 테스트(추가분).

## 같이 배포해야 하는 묶음
| 대상 | 이유 |
|---|---|
| MagicCodexBridge (학교·야생·아레나·튜토리얼) | 대부분 항목 |
| magic-codex-fabric 클라 모드 | 스탯명, 설정창, 하우징 화면, 펫 aliases, 도감 132/157/176 |
| magic-codex-protocol 변경 → Bridge·클라·Velocity 같이 | CoreAffinity 표시, KyudichiQueueProtocol Custom |
| MagicMythic + chacademi_spells.yml | 파티 아군 피해, 허수아비, 길잡이 별, 잔류 VFX, 빗자루 투사체 |
| PortableVFX paper jar + client jar | #77 대상 전달(3인자), #29 반응·기믹·성소 투명도 제외 |
| MagicDiscovery | 발견 칭호 연결(리플렉션, 없으면 건너뜀) |
| ChacademyStory 플러그인+모드 | 장 잠금, 길잡이, HUD |
| chacademi-party (Velocity) | 커스텀 경기, 재접속 버그, 튜토리얼 분산 |
| ChacaKyudichi (아레나) | 커스텀 경기·관전, 재접속, 사망 우선순위, #33 경기 종류 답(MagicMatchEvent) |
| ChacaHousing | 좋아요 1회, 방문순위 |
| ChacaNPC | AI 대화 liked 보상 |
| ChacaPortrait | auto.server-ids |

## 자동 생성 DB 표
codex_title_awards, codex_artifact_pity, codex_shop_tutorial(SQLite: shop_tutorial), codex_housing_like. 그 외 스키마 변경 없음.

## 운영 설정에 손으로 넣어야 하는 것 (기존 서버 파일은 자동으로 안 바뀜)
- titles.yml: suffix_affinity_friend/bestie/lover 3개 (없으면 콘솔 경고만, 지급 안 됨)
- housing config.yml: like.limit, visits.count-once-per
- deployment/operating-config/MagicCodexBridge 의 artifacts.yml / mana-spells.yml(길잡이 별 쿨 0) / spell-runtime.yml(range-multiplier) 갱신본 반영
- ChacaPortrait: auto.first-join 은 기본 false — 첫 입장 일러스트를 쓰려면 true
- Velocity tutorial.properties: tutorial-servers 가 비면 기능 꺼짐(기존과 동일)
- 새 yml(자동 생성): title-awards.yml, player-presets.yml, tutorial-shop.yml, return-ticket.yml, training-dummies.yml
- ItemsAdder: item:bag_expansion, item:return_ticket 정의 필요
- TAB / WorldGuard: deployment/tab/, deployment/worldguard/ 문서대로

## 주의
- 아티팩트 천장은 키가 없어도 **기본 유일 이상 50회로 켜짐**(오너 미확정 자리표시). 끄려면 summon.pity.count: 0.
- 감정 코어 성향이 이제 1:1:1로 뽑힘(전엔 사실상 전부 지능 중심). 기존 코어는 그대로.
- 스토리 3장은 기본 잠김(chapter-release). 열기: /대화관리 장공개 열기 3
- 하우징 좋아요는 이제 취소 불가·평생 1회.
- 칭호 최대 96개(TitleProtocol.MAX_TITLES) — 마법별 칭호를 다 넣으면 넘침.
- 튜토리얼이 별도 서버가 되면 스토리 진행(학교 서버 로컬 파일) 공유가 따로 필요.

## 오너 검수 중 추가 변경 (2026-10-25 오전, 검수 기록: CLAUDE_OPENING_PREP_REVIEW_20261025.md)
- 일러스트(ChacaPortrait): `queue.workers` 기본 2→12, 상한 8→16 (60명 10분 안). `auto.server-ids` 기본 [school, tutorial] (2026-10-26 오너: 튜토리얼 서버 하나, 처음엔 두 대였음). **운영 config.yml 의 queue.workers 를 12로 직접 바꿔야 함.**
- 칭호(#21): titles.yml 에 마법 237·업적 38·티어 4 칭호 추가 (총 290 + NPC 자동), title-awards.yml discovery 237·fishing 대물/괴물같은·`affinity.npc-prefix` (ChacaNPC npcs/*.yml 의 id·name 으로 `prefix_npc_<id>` = "<이름>의" 자동 생성, 호감도 30). **운영 서버의 MagicCodexBridge/titles.yml·title-awards.yml 은 자동으로 안 바뀌므로 학교·야생·던전 모두 새 파일로 교체(직접 고친 칭호가 있으면 합치기) 후 `/서버관리 새로고침 칭호`.** TitleProtocol VERSION 02 (개수 1→2바이트, 상한 96 제거) → **Bridge 플러그인·클라 모드 같이 배포**, 옛 모드는 칭호창이 안 열림.
- Velocity TutorialRouter: tutorial.properties 기본 `tutorial-servers=tutorial` (2026-10-26 오너: 하나 → 분산 꺼짐. 처음엔 두 대).
- ChacaNPC 대화 버튼 `action: "shop:<상점ID>"` (또는 `shop:물고기판매소`) → 대화창 닫고 Bridge `NpcSocialFacade.openShop` 으로 상점 열기 (튜토리얼 재료 상점 분기 그대로). Bridge·ChacaNPC 같이 배포. 피피 버튼 예: `{ id: shop, label: "물건 좀 볼 수 있어요?", action: "shop:pipi" }`.
- 스토리(ChacademyStory 플러그인 + 모드 같이 배포):
  - 대기대화: 단계 키 `대기대화: [{NPC, 대화}]` + `/대화관리 단계 <ID> 대기대화 추가|삭제|비우기|목록`, config `waiting-dialogue-cooldown-seconds: 60`.
  - 목표 지점 여러 개: 새 채널 `chacademy:quest_points` (아직 안 누른 상호작용 블록 위 징표, 길잡이 별은 가장 가까운 곳). 모드가 옛 버전이어도 서버는 채널을 듣는 클라에게만 보냄.
  - `SORTING_DIALOGUE` ch1-14 → ch1-15 (실서버·SchoolBridge 와 맞춤).
  - 기본 steps.yml 을 실서버 기준 새 1장(25단계)으로 교체.
- Bridge 스토리 신호 추가: `물고기판매`(FishShopBridge), `지도열기`·`웨이포인트등록`(TravelBridge).
- tutorial-shop.yml 기본값: shop `pipi_tutorial`, required 교복·견습 지팡이·빈 마법서 각 1. **운영 서버에 상점 pipi_tutorial 을 만들고 같은 이름으로 상품 등록 필요.**
- 아레나 큐디치 사망 처리 우선순위 HIGHEST→HIGH (경기 중 /kill 류 사망 시 장신구 소실 방지).
- 펫 도감: `config/magiccodex/pets/models/aliases.json` 으로 펫ID ↔ bbmodel 파일 이름 연결.
  - **GPT 할 일 (#30, 오너 지시 2026-10-25)**: 펫 도감에서 알로 보이는 바닐라 몹 펫마다 이미 만든 bbmodel 을 찾아 `aliases.json` 에 `{"<펫ID>": "<bbmodel 파일 이름(확장자 없이)>"}` 로 적고 런처 리소스에 넣기. 새 모델 제작 아님, 이름 연결만. 파일이 펫ID와 같은 이름이면 연결 없이 바로 뜸. 확인: 도감에서 해당 펫이 알 대신 모델로 보이면 끝.
- 1장 서버 파일(steps.yml·server_command.yml·대화)은 chacademi-server 저장소 claude/ 브랜치에 따로 올림.
- **아직 안 된 것**: 스토리 진행 공유 DB(튜토리얼 서버 → 학교 서버 진행 이어짐) — 오너가 작업을 멈춤. 이게 없으면 학교 서버 도착 시 진행이 이어지지 않음.

## 묶음별 상세

### 01-magic
#28 /테스트 마법 자동시전 [시작 [번호|ID|이름]|정지|일시정지|재개|건너뛰기|간격 <1~60초>|상태]. 기본 5초, 도감번호 순. 실제 /마법 경로(ManaBridge.cast) 사용, 시전 직전 쿨·마나·권한 잠깐 채우고 복원. 몹 앞에서 돌리기 권장. config spell-review.skip 선택. 새 SpellReviewRunner, ManaBridge/ManaSpells 수정.
#26 활성 = 운영 mana-spells.yml enabled 226개(원본 224). 꺼진 11개.
#40 tools/spell_audit/audit_icons.py — 확실한 누락 12개(239~250 기믹), 169개는 런처 리소스팩에서만 확인 가능(--pack). 발견 알림 아이콘 경로 다른 26개(87,88, 300~323). → 26개는 도감(모드) 아이콘 경로로 맞춤 (discoveries.yml·PortableVFX spell-catalog.yml 원본+운영본). **운영 서버의 MagicDiscovery discoveries.yml·PortableVFX spell-catalog.yml 에도 같은 수정 반영 필요**. GPT: `python tools/spell_audit/audit_icons.py --pack <런처 리소스팩 폴더>` 로 169개 실제 파일 확인.
#47 마법 효과음 전면 교체 (오너 2026-10-25 "전체 다 바꿔야 해, 마크 기본 사운드로"): Bridge SpellSounds + resources/spell-sounds.yml — 237개 마법 전부 cast(237)·hit(164)·end(82), 층 1,272개, 1.21.4 기본 사운드 392종(이름 전부 공식 목록으로 검사). 서버가 재생(주변도 들림): 시전 성공 = ManaBridge, 적중·끝 = MagicMythic Links.vfx → Bridge.spellSound(리플렉션). 클라 예전 시전음(ScreenVfxClient.onCast) 끔. 미리듣기 /테스트 마법 효과음 <마법> [cast|hit|end], 새로고침 /서버관리 새로고침 마법소리. **Bridge·MagicMythic·클라 모드 같이 배포**, 운영 서버에 spell-sounds.yml 이 없으면 jar 기본 파일이 깔림. PortableVFX 만으로 도는 마법(MagicMythic 안 거침)은 시전음만 남.
#47 audit_sounds.py — 사운드 없음 52개(1 feather fall 이름 불일치, 79/83/86/87/88/92/93/95, 203,210~214, 239~250, 300~323, 324).
#45 docs/ELEMENT_REACTIONS_GUIDE_KO.md — 확정 14조합 코드와 일치. 바람+빛 미정(예전 효과), 물+빛 회복 1, 몹 마나번 아이콘만, 보스 CC 예외.
배포: paper jar만.

### 02-stats
#71 지능 표시명 변경(내부 키 유지, 옛 lore 호환, /마력재구성·마력 회로 강화 창 이름은 유지)
#19 버그: 감정 시 코어 성향을 안 뽑아 전부 지능 중심 → 감정 성공 시 affinity-weights(기본 1:1:1)로 뽑음. 기존 코어는 그대로.
#70/#105 books.<id>.required-intelligence (기본 0). 지능 = 장신구+손 마법봉뿐 → 기본 지능 없음 주의.
#16 미구현(장신구·아티팩트만 최대체력). #17/18/20 수치 위치표. 잡화점엔 강화 관련 상품 없음.
배포: paper+fabric+protocol, deployment/operating-config/MagicCodexBridge/artifacts.yml
결정: 성향 비율, 책별 요구 지능, 기본 지능 출처, 창 이름도 바꿀지, #16

### 03-titles
#21 마법→칭호 목록은 저장소·프로젝트 문서 어디에도 없음 → title-awards.yml discovery: {} 빈 자리. 연결: 마법 발견(MagicDiscovery 저장 직후·관리 지급·접속 시 예전 발견 포함), 호감도(처음 30/60/90 친구·단짝·연인 + NPC별 30), 낚시(규칙 비어있음). 지급 장부 codex_title_awards로 서버 간 1회, 회수 후 재지급 없음. titles.yml에 접미 친구·단짝·연인 3개 추가(기존 서버 titles.yml에 손으로 붙여 넣어야 함).
   주의: 칭호 최대 96개(TitleProtocol.MAX_TITLES) — 마법마다 하나면 넘침. 결정 필요.
#89 player-presets.yml players: {} (UUID/계정명 → house/titles/spells), 접속 3초 뒤 항목별 1회. 기숙사 미리 지정·거울 연출은 이미 있었음. 이미 분류된 유저 넣으면 바뀜.
#85 검수: 잠금 트랜잭션으로 경쟁 없음, 재접속 유지 OK.
배포: Bridge jar + MagicDiscovery jar, 새 yml 2개(자동 생성), titles.yml 3줄 수동.

### 04-affinity
#83 AI 응답 JSON에 liked 추가 → 마음에 들면 호감도 +1, NPC별 하루 3회(공유 DB codex_affinity_daily source chat_reward), 상한이면 안 오르고 횟수 안 씀. 판정 문구 config affinity.chat-reward-criteria(비우면 끔). 버튼 미리 생성·관리 테스트는 지급 안 함.
#90 검수: DB 기반, 버그 없음. database.properties 공유(mariadb) + timezone Asia/Seoul 확인 필요.
#87 인기도 서버 기능 자체가 없음(요청 이벤트 처리기 없음, 내리기 버튼 없음, 해제권 아이템 없음) → 사양 필요.
#100 검수 버그 없음.
#39 affinity.yml gate-quests: {} — NPC별 30/60/90 퀘스트 ID 넣으면 상한 도달 시 자동 지급, 완료 시 다음 상한 해제. QuestBridge 완료 훅 추가.
배포: Bridge jar + ChacaNPC jar; affinity.yml chat-reward.amount/daily-count, gate-quests; ChacaNPC config affinity.chat-reward-criteria; prompts/common.txt liked 줄(선택).

### 05-combat
#23 마법 쪽은 이미 AllyResolver로 막혀 있었음. 빠진 경로: 독 틱·화상 폭발 직접 피해 → 건 사람이 같은 편이면 건너뜀. 새로 막음: 근접·화살·TNT·늑대·불 인챈트·해로운 물약(PartyFriendlyFire.java). 무적 아님.
#77 원인: VFX가 따로 두께 0.3 레이로 대상 다시 찾다 빗나가면 시전 전체 실패(작은 플레이어에서 자주). → 서버가 고른 대상을 VFX에 넘김. 해당 마법 8개(윈드 프레스 등). 거부 문구 "몬스터"→"대상".
#79 정리 누락 3곳: 마인드컨트롤 종료 VFX(주석에 묻힘), 폭발 쐐기 30초 루프, 화염 함정 60초 루프.
#35 몹 기술 투사체 빗자루 출발점 보정, 사냥 도약·포효 도약 빗자루 위 금지.
#107 /컨텐츠관리 연습장 허수아비 <설치|제거|목록>, 바닐라 허스크, 0.5초 뒤 회복, 최근 5초 합계·초당 표시, training-dummies.yml.
#80 spell-runtime.yml range-multiplier: 1.0 (서버 판정만, 클라 미리보기·VFX 따로). 기존 불일치 목록.
배포: MagicMythic jar(+config training-dummy.window-seconds), Bridge jar + spell-runtime.yml, PortableVFX paper jar.

### 06-housing
#78 좋아요 토글→평생 1회(공용 DB codex_housing_like, INSERT IGNORE), like.limit room(기본)/global. 클라 HousingScreen 안내. 예전 취소자는 1회 더 가능.
#51 방문 수 집계는 이미 있음(하루 1회). visits.count-once-per day/ever, topVisited, /하우징관리 방문순위 [개수]. UI 없음. → **하우징 랭킹 구현 (오너 레퍼런스 "하우징_랭킹" ZIP, 2026-10-25)**: 새 채널 chacademi:housing_rank (HousingRankProtocol), 서버 HousingStore.ranking + HousingRankServer, 유저 명령 /하우징랭킹 (별칭 /방랭킹), 클라 HousingRankClient·HousingRankScreen, 리소스 textures/gui/housing_rank/*.png 11장(분리 PNG 그대로). 탭 일일 방문(KST 오늘 owner/visitor/day 고유, 본인 제외)·누적 방문(room.visits)·하트(room.likes) — 측정 정의 그대로, 값 0 인 방 제외, 같은 값 같은 순위(1,1,3), 6줄/쪽. 방 이동 = **친구가 아니어도 가능** (오너 2026-10-25, Rooms.openFromRanking — /방구경 은 그대로 친구만), 버튼은 내 방/방 이동. 제목은 기존 UI 굵은 글꼴, 제목 밑줄 가운데·위아래 여백 맞춤. 기존 하우징 창·패킷 변경 없음. **housing-paper·Bridge(프로토콜)·클라 모드 같이 배포.** 테스트 HousingRankProtocolTest.
#108 기숙사 점수판→SchoolScreen, 내 정보 액자→/스탯창 주인UUID(주인이 같은 서버 접속 중일 때만). 빗자루·아티팩트·트로피는 화면 없음(레퍼런스 필요). 랭크 티어 가구 없음.
#94 하우징은 이미 한글 닉네임. 다른 곳 계정명 노출: SocialBridge:83,309 / CmdKit:145 / QuestBridge:206(로그) / kyudichi Hooks:142, ChacaLink:115 / 클라 일부 초기 표시.
배포: chaca-housing jar + fabric 모드, config 키 손으로 추가(like.limit, visits.count-once-per)

### 07-story
#5 장 잠금: config chapter-release (기본 2장까지 공개, 3장 잠금, open-at 비움). 포탈·NPC·자동·컷신·관리자 강제·대화 재생 모두 차단, HUD에 "아직 열리지 않은 이야기입니다". /대화관리 장공개 목록|열기 <장>|잠그기 <장>. 장 번호는 단계 ID ch<N>- 기준.
#3 TutorialRouter(Velocity): tutorial.properties tutorial-servers 2개 넣으면 튜토리얼 서버로 가는 접속을 적은 쪽으로(sticky). 비면 기존과 동일. 파티 동반 미구현. 주의: 스토리 진행이 학교 서버 로컬 파일이라 튜토리얼이 별도 서버면 진행 공유 별도 필요.
#72 길잡이 별 버그: 10-23 story-steps 커밋 일부(StoryLink, quest_guide 채널, 화살표)가 통합 브랜치에 빠져 퀘스트와 연결 안 돼 있었음 → 이식. 웅크림 저장 삭제, 재시전=끄기, 쿨 10→0(mana-spells.yml 등). 서버 이동 시 예전 목표 그려지던 문제 수정.
#99 검수 버그 없음. #54 검수 버그 없음(주석만). #104 장 이름 위치: steps.yml 장: 키, /대화관리 단계 <ID> 장 <문구>.
배포: ChacademyStory 플러그인+모드, MagicMythic + chacademi_spells.yml, Bridge mana-spells.yml, MagicCodex 모드, party-velocity + tutorial.properties.

### 08-arena
#62 /큐디치관리 경기 커스텀 <큐디치|결투> <닉+닉+닉> <닉+닉+닉> — 어느 서버에서든, 10초 수락 창, 레이팅 없음, 끝나면 복귀. 커스텀 경기만 관전 20명 제한 없음. 목록 [커스텀] 표시.
#75 원래 잘 된 것(대기·수락 중 이탈, 60초 재참가). 고친 버그 7: 튕겨서 다른 서버 간 선수 경기장 복귀, 60초 지나 빠진 사람 경기중 취급, 늦은 사망 처리로 관전 갇힘, 늦게 온 선수 복귀 위치 없음, 경기장 재시작 시 유령 경기 재시작+75초 미보고 시 무효, 경기장 서버 이름 오류 시 2분 묶임, 무효 처리 실패 시 프록시 미통보. 던전 입장 시스템은 코드에 없음.
#33/34 작은 버그 3(결투 중 문구, 연습 기록 누수, 레이팅 캐시). 보고만: 상대 미도착 시 남은 쪽 승리, 1:1 시간 종료 오프라인 0%.
#33 큐디치 전투 보정 (오너 결정 2026-10-25, 큐디치 경기 중 선수만 — 평소·1:1 결투 그대로): ① 투사체(chabolt, 아르카나 175·192·213) 속도 ×1.5 ② 비행 중(빗자루·겉날개·비행) 선수 피격 상자 ×1.5 — Targets.closestDistance 를 쓰는 투사체·광역 즉발기 전부 ③ homing=true 유도 투사체는 대상을 끝까지 정확히 추적, 놓치는 건 순간이동(한 틱 4블록↑) 또는 맞기 3틱 전 안에서 빗자루 속도가 한 틱에 0.6↑ 바뀐 회피 기동뿐, 추적 중엔 사거리 대신 최대 5초. 새 MagicMatchEvent(MagicMythic) ← ChacaKyudichi Hooks.listenMatch 가 "quidditch"/"duel" 답함. 설정 MagicMythic config.yml quidditch-combat (없으면 기본값). **MagicMythic·ChacaKyudichi jar 같이 배포.** 주의: 빗자루 속도가 패킷 간격 때문에 튀면 회피로 잘못 볼 수 있음 → 실게임에서 너무 자주 놓치면 dodge-speed-change 를 올릴 것. 테스트 QuidditchCombatTest.
#43 (GPT 확인, 오너 2026-10-25): 야생 빗자루 고속 비행 청크 부하. 빗자루 최고 1.65칸/틱, 부스트 ~2.4칸/틱(초당 33~48칸). ① 야생 월드 경계 안을 오픈 전 Chunky 등으로 미리 생성 ② 월드 경계 설정 ③ simulation-distance 낮게. 테스트: 미리 생성 후 5명·10명 부스트 직선 비행 2분, /spark tps · MSPT 확인, 기준 MSPT < 50ms·TPS ≥ 19.5.
#41 서버 이름 설정표, 코드 하드코딩(housing-presence-velocity Set.of school/wild/arena 등) — wild2 생기면 수정.
#50 레이팅 표시는 로비·경기카드·랭킹·전적·신청·준비·결과 창에 이미 있음.
배포: Velocity chacademi-party + 경기장 ChacaKyudichi + 클라 모드(프로토콜) 동시.

### 09-client
#29 투명도: 오너 확인 결과 정상 → 1차의 셰이더 보정(opacity^1.2)은 **되돌림**. 대신 원소 반응·던전 기믹 12팩·큐디치 성소(shrine*)는 투명도 설정에서 제외(늘 100%, EffectRuntime.OPACITY_EXEMPT_PACKS). 큐디치 성소 VFX 이름을 config 에서 shrine* 아닌 이름으로 바꾸면 목록에 추가할 것.
   레이아웃: 빛 번짐 슬라이더 추가로 3줄인데 2줄 간격 그대로 → 3번째 줄이 금색 구분선과 겹침. SettingsSlider.rowY로 3줄 이상 168+76n. 테스트 추가.
   보고만: GUI(핫바 크기) 페이지가 홈에서 못 열림(HUD 잠금으로 교체됨, Shift+휠만), 저장 실패 문구 순서, 내것/남의것 분류(반응·기믹·큐디치=남의것, 남의 빗자루 궤적=내것).
#81 펫은 MCPets+ModelEngine 서버 엔티티 → 클라만으로 불가. A: 소환 시 1회 setScale(크기만). B(권장): 서버는 소환/해제만 알리고 클라가 bbmodel로 머리 옆에 그림. 결정 필요.
#82 MagicCodex 의뢰는 HUD 없음, 중복 수락은 서버에서 막힘. 스토리 HUD 긴 조건 줄바꿈 수정, 파티 HUD와 겹칠 때 자동 회피(QuestHudPlacement).
#86 VFX 클라 설정 목록(투명도·빛번짐·load-limits·JVM 옵션·하드코딩 상한).
배포: PortableVFX 클라 jar, MagicCodex fabric jar, chacademy-story 클라 jar.

### 10-items
#101 가방 확장권 연결은 이미 있었음. 중복 버그 2개 수정(DB 해금 후 서버 다운 시 확장권 남아 2번 열림 → 차감 직후 저장 / 응답 전 끊기면 확장권 증발 → 우편 반환). ItemsAdder 정의 item:bag_expansion 서버에 추가 필요.
#14 귀환권 구현(item:return_ticket 기본, return-ticket.yml): 우클릭 → 지도 이동 경로로 학교 스폰, 실패 시 1번 반환. 전투 제한 없음(미정). 없는 기능: 아티팩트 조각, 연금술 물약 효과.
#12 TutorialShop: tutorial-shop.yml (shop·required 비움=꺼짐). /유저관리 <닉> 튜토리얼상점 시작|다시|끝|보기 로 대상 지정(스토리 시작명령에 걸기) → 피피 열면 전용 상점, 필요 재료 전부 결제+창 닫기 → 스토리 신호 튜토리얼재료구매. 결제 중 닫으면 다시 열고 닫아야 함.
#11 판매 시스템 버그 없음. #74 위 2건 외 없음.
배포: Bridge jar 모든 서버, 새 yml 2개 자동, DB codex_shop_tutorial 자동, ItemsAdder 아이템 2개.

### 11-artifact
#98 천장 구현: summon.pity.grade(기본 unique)/count(기본 50, 0=끔), 공유 DB codex_artifact_pity 자동 생성, 같은 거래·잠금으로 원자적, 재시도 안전, 영수증 저장. 남은 횟수는 기존 확률 줄·결과 안내문에 문구로(클라 수정 없음, 줄 넘침 확인 필요). 관리 명령 표시 추가. 테스트 ArtifactPityTest(미실행).
중복: item:artifact_shard 우편 1개 — 쓰는 곳 없음(선택지 4개).
#64 켜진 121개 효과 코드 모두 있음, 꺼진 29개(전설 9) 미구현. 불일치: 낚시꾼의 찌(바닐라 낚시만), 역전의 메달(마법 피해만 2배).
#57 열쇠 item:random_artifact 얻는 곳 없음.
배포: paper jar(학교·야생·아레나), artifacts.yml(배포용 사본도 수정)

### 12-ops
#110 TAB: 플레이스홀더는 닉네임 없으면 계정명. deployment/tab/ (README, config-snippet, groups). 이름표는 TAB으로 꾸미지 말 것(invisible-nametags 끄기).
#95 /유저관리 <닉> 게임모드|찾아가기|불러오기|좌표이동|비행|회복 (다른 서버면 HuskHomes 위임). OperatorCommands.java.
#4 deployment/worldguard/ (protect-build.txt, README) — 학교·튜토리얼 월드만, 야생 제외. 월드 이름 결정 필요.
#38 docs/OPERATOR_GUIDE_KO.md 상황별 안내. + **운영자 AI 도우미 `/관리 질문 <궁금한 것>`** (오너 결정 2026-10-25): Bridge OperatorHelp 가 CommandHub.catalog()(켜질 때 실제 등록된 관리·유저 명령 끝 칸 전부)와 resources/operator-guide.md(= docs/OPERATOR_GUIDE_KO.md 사본, 안내서 고치면 같이 복사)를 지시문으로 넣어 ChacaNPC 의 OpenAI 연결(ChacaNpcApi.ask, 리플렉션)로 묻는다. 답 JSON {answer, commands[]} 의 명령은 실제 명령 모양과 맞춰 보고 안 맞으면 뺀다. 실행은 안 함(클릭하면 채팅창에 들어감). 예산 한도와 상관없이 보냄(오너), 사용량은 시스템 사용량으로 기록. **Bridge·ChacaNPC jar 같이 배포**, ChacaNPC config 에 OpenAI 키 필요. 테스트 OperatorHelpTest.
#88 청크 점유 코드가 어디에도 없음 → 외부 플러그인/새로 만들기 결정 필요.
#61 인벤세이브 플러그인 없음(바닐라 규칙). 부활 지점 코드 없음(바닐라). 큐디치 사망 시 장신구 소실 버그 → 내가 GameListener death 우선순위 HIGH로 수정.
#2 일러스트 대기열 정상. 자동 생성이 server-id school에서만 → auto.server-ids 설정 추가(기본 [school]). auto.first-join 기본 false라 켜야 함. 워커2 기준 180명 약 2시간.
#67 구조물은 데이터팩(Terralith 수정본)이 놓음, 파일이 저장소에 없어 개수 못 셈 → deployment/structures/diamond_filter.py (count/strip, 원본 보존).
배포: Bridge jar, ChacaPortrait jar + auto.server-ids, TAB/WorldGuard 설정 적용.
