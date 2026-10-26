# 스토리 진행 단계 모듈 — Skript 없이 (조건 → 호출 → 다음 대화) + 퀘스트 HUD · 목표 포탈 · NPC 징표

작성: Claude, 2026-10-20~21 · **빌드 안 함** (아래 "빌드" 참고)
- 코드: `chacademi-magiccodex-source` 브랜치 `claude/story-steps-20261020`
  - 1761766 단계 모듈 1차 (← `codex/followups-integrated-20261020-task12` @ 0cd1454)
  - 2차 (이 문서의 "2차" 항목): Skript 제거 · 기숙사 분류 Java 연결 · 성공 신호 4종 · 점검 명령
- 1장 콘텐츠: `chacademi-server` 브랜치 `claude/story-ch1-20261020` (`story/ch1/`)

## 1. 빌드 (GPT, 호스팅)

이 환경은 Paper·Fabric·Citizens 저장소에 접속할 수 없고, AGENTS.md 상 노트북에서 Gradle 을 돌리지 않는다. 그래서:
- **확인함**: 스토리 플러그인의 새·바뀐 클래스(`QuestDefs` `QuestProgress` `QuestCodec` `QuestEngine` `StorySafety` …)를 API 스텁으로 javac 컴파일, 단위 테스트 7개 통과 (`QuestDefsTest`, `QuestSkriptFreeTest`).
- **확인 못 함**: `StoryPlugin.java` 실제 컴파일, 스토리 모드(`story/quest/*`) 전체, MagicCodexBridge·ChacaHousing·ChacaNPC 수정분.

함께 빌드할 것 (호스팅 `Build-Server.ps1 -Verify` 또는 모듈별 `test jar` / 모드 `test remapJar`):

| 모듈 | 바뀐 곳 | 컴파일 위험 |
|---|---|---|
| ChacademyStory 플러그인 (`chacademy-story/plugin`) | QuestEngine·QuestDefs·QuestCodec·QuestProgress(새), StoryPlugin, StorySafety, resources(steps.yml·config.yml·plugin.yml), 테스트 2 | `StoryPlugin` 의 `endingAllowed`(StoryGraph.Scene 레코드 `choices()`/`next()`), 세션 신호 `case` 안의 `break` |
| 스토리 모드 (`chacademy-story/mod`) | `story/quest/*`(새), `ChacademyCutsceneClient` 한 줄, 텍스처 9개 | **가장 높음** — `StreamCodec.of`, `RenderType.entityTranslucentEmissive`, `VertexConsumer` 체인, `Camera.rotation()`, `ScreenMouseEvents.allowMouseClick`, `HudRenderCallback(GuiGraphics, DeltaTracker)`. `QuestArt` 은 chacademy-motion `HudArt` 복사 |
| MagicCodexBridge (`magic-codex-paper`) | `StoryProgressEvent`(새), TamingBridge·FishingBridge·AlchemyBridge 각 1~3줄 | `TamingBridge.finish` 반환형 void → boolean |
| ChacaHousing (`housing-paper`) | `StoryProgressEvent`(새), `Rooms.load` 1줄 | 낮음 |
| ChacaNPC (`chaca-npc-paper`) | `NpcManager.onRightClick` `ignoreCancelled = true` | 낮음 |

MagicCodex 클라이언트 본체(`magic-codex-fabric`)는 건드리지 않았다.

## 2. Skript 의존 제거

- 진행·조건·대화·컷신·HUD·포탈·징표: ChacademyStory + 스토리 모드만으로 동작.
- `chacademi-server/story/ch1/서버/story_ch1.sk` **삭제** (배포하지 않음). 운영 `plugins/Skript/scripts/story_ch1.sk` 가 있으면 지운다.
- 클라 대화 `ch1-14/dialogue.yml`: 끝 장면 `p_00~p_33` 의 `event: e_pTD` 와 `commands.events`(`분류응답 p_TD` 플레이어 명령) **삭제**. 다른 대화 파일의 명령은 `ch1-2`의 `chaca_reward` 하나뿐 (Skript 아님).
- 남은 예전 명령 안전망: `StorySafety.legacyCommand` — `스토리완료·스토리이어·분류시작·분류결정·분류응답·기숙사지정·스토리초기화·분류초기화·storydialogue·cutscene·스토리대화·컷신` 으로 시작하는 명령은 server_command·on-finish·시작명령 어디에 남아 있어도 **실행하지 않고** 콘솔에 한 번 경고한다. 그래서 운영 `config.yml` on-finish 에 `스토리이어 {player} ch1-19 ch1-20` 이 남아 있어도 진행이 꼬이지 않는다 (그래도 지우는 것 권장, 다른 on-finish 항목은 그대로 실행).
- 명령 정리로 없어진 명령도 고침: `낚시관리 장비지급` → `아이템관리 낚시장비 bamboo_common 1 {player}`, `칭호관리 지급` → `유저관리 {player} 칭호 주기 freshman`, `연금술 {player}`(본인 전용) → `테스트 연금술 열기 {player}`.

## 3. 기숙사 분류 (SchoolBridge 기존 Java 그대로)

```
ch1-13 대화 정상 종료 → 단계 ch1-14 시작
  └ 시작명령 "기숙사분류 시작 {player}"  (steps.yml)
       소속 없음 → 대화 ch1-14 (6문항)      소속 있음 → 대화 ch1-14f-<기숙사>
  └ ch1-14 정상 종료 (서버 세션·토큰·ready 확인 + 결말이 서버 흐름의 끝 장면인지 확인)
       → server_command ch1-14: "기숙사분류 결정 {player} {ending}"   ({ending} = p_TD)
       → SchoolBridge: 적합도·인원 균형·중복 방지·codex_houses 저장 → dormitory.assigned (새로 배정됐을 때만)
       → 결과 대화 ch1-14r-/m-<기숙사> (이미 소속이면 r-)
  └ ch1-14r-*/m-*/f-* 정상 종료 → 조건 "대화완료 ch1-14r-*|ch1-14m-*|ch1-14f-*" 충족 → 자동 → ch1-15
```

- 새 배정 저장소·변수 없음. 미리 정할 유저는 기존 `/유저관리 <닉네임> 기숙사 <기숙사>`.
- **결말 검증 추가** (`StoryPlugin.endingAllowed`): 서버에 `dialogues/<id>/dialogue.yml` 흐름이 있으면, 클라가 보낸 결말이 그 흐름에서 선택지·다음이 없는 끝 장면이어야 한다. 아니면 명령({ending} 포함)을 실행하지 않고 세션을 닫는다. 흐름이 없으면 예전처럼 형식만 확인. → 학교 서버 `plugins/ChacademyStory/dialogues/ch1-14/dialogue.yml` 을 꼭 넣을 것. (로컬 대화라 클라가 16개 끝 장면 중 하나를 고를 수는 있다 — 기존 구조의 한계.)
- 중복: 세션은 finish 에서 한 번만 소비 → 결정 명령 1회. 결정이 두 번 와도 SchoolBridge `created()` 가 false 면 저장·신호 없이 기존 소속 결과 대화.
- 재접속·중간 종료: 시작명령은 단계 진입 때, 조건을 못 채운 채 다시 접속했을 때(6초 뒤), 대화를 중간에 닫았을 때(5초 뒤, 30초에 한 번) 다시 실행. 이미 배정된 뒤면 `시작` 이 확정 대화(f-)를 띄워 조건이 찬다. 수동: `/유저관리 <닉네임> 스토리진행 다시`.
- 단계 건너뛰기 방지: 6문항 대화(ch1-14) 완료는 조건 대상이 아니다 (결과·확정 대화만).

## 4. 성공 신호 (신호 조건)

각 기능 소유 플러그인에 작은 Bukkit 이벤트 `StoryProgressEvent`(getPlayer / getKey / getAmount)를 추가하고, ChacademyStory 가 리플렉션으로 듣는다 (컴파일 의존 없음, softdepend 에 ChacaHousing 추가). 메인 스레드, 접속 중인 유저에게만.

| 신호 키 | 소유 | 보내는 시점 | 제외 |
|---|---|---|---|
| `교화성공` | MagicCodexBridge `TamingBridge` | LuckPerms 펫 권한 저장 성공 → `finish(c,true,…)` 가 실제로 결과를 냈을 때 | 실패·저장 실패·이미 끝난 교화 |
| `낚시성공` | `FishingBridge` | 물고기가 통발 수조 DB 에 `Put.OK` 로 저장된 콜백 | 보물상자·미리보기·수조 가득/보류 저장·탈출·끊김, 같은 RESULT 재전송(기존 토큰·상태 검사) |
| `연금술성공` | `AlchemyBridge` | 재료·경험치를 걷고 결과물 지급(인벤토리/우편) 뒤 | 경험치 부족·틀린 조합·결과 아이템 오류·쿨다운 거절 |
| `내방열기` | ChacaHousing `Rooms.load` | 창을 여는 load(open=true)에서 **내 방** 데이터를 읽어 보낸 뒤 | 남의 방 구경·방 못 찾음·갱신 재전송(open=false) |

다른 모듈을 고친 이유: 위 네 기능에는 재사용할 성공 이벤트/API 가 없었다 (DiscoveryLink 신호는 교화는 불사조만, 나머지는 없음).
`/대화관리 신호 <닉네임> <키>` 는 관리자 수동 보정용으로 남겼다 (콘텐츠의 어떤 명령도 이걸 부르지 않는다).

## 5. 1장 콘텐츠 (`chacademi-server` `story/ch1/`)

| 파일 | 배포 | 할 일 |
|---|---|---|
| `서버/steps.yml` | 학교 서버 `plugins/ChacademyStory/steps.yml` | 없으면 플러그인 기본값이 같은 내용으로 생김 |
| `서버/server_command_1장_추가분.yml` | 운영 `dialogues/server_command.yml` 의 `commands:` 에 **병합** | 예전 `스토리완료`·`storydialogue`·`cutscene play`·`분류시작`·`분류결정`·ch1-14r/m/f 의 `대화관리 신호` 줄 제거 → `/서버관리 새로고침 스토리` |
| `chaca_dialogue/*` | 클라 `config/chaca_dialogue/` + 학교 서버 `plugins/ChacademyStory/dialogues/` | ch1-14 는 새 버전(분류응답 제거)으로 교체 |
| 운영 `config.yml` | — | `on-finish.ch1_ashen_night` 의 `스토리이어 …` 삭제 (다른 항목 유지) |
| 운영 Skript `story_ch1.sk` | 배포 안 함 | 있으면 삭제 |

## 6. 운영자가 게임 안에서 정할 것 (임의 값 없음)

미지정이면 그 단계에서 멈춘다 (호출 없음 → 다음으로 가지 않음, 블록 없는 상호작용 → 조건이 안 참). `/대화관리 단계 점검` 이 남은 곳을 모두 보여 준다. 점검·정보에 ⚠ 로 표시.

| 단계 | 정할 것 | 명령 |
|---|---|---|
| ch1-6 | 선착장 포탈 | 그 자리에 서서 `/대화관리 단계 ch1-6 호출 위치 2` |
| ch1-8 · ch1-11 | 피피 NPC | `/대화관리 단계 ch1-8 호출 NPC클릭` → 30초 안에 피피 우클릭 (ch1-11 도 같게) |
| ch1-10 | 올레 NPC | `… ch1-10 호출 NPC클릭` |
| ch1-12 · ch1-13 | 광장 동상 · 정문 포탈 | `… 호출 위치 [반경]` |
| ch1-15~18 | 로웰 · 베르나 · 엘레나 · 이솔데 | `… 호출 NPC클릭` |
| ch1-19 | 이론서 블록 3개 | `/대화관리 단계 ch1-19 조건 블록지정 1` → 블록 우클릭 ×3 → `/대화관리 단계 지정끝` |
| ch1-6 / ch1-13 끝 | 항구 · 분류의 거울 앞 순간이동 | server_command 의 주석 `# - "tp …"` 에 좌표 넣고 `#` 제거 (그 전엔 이동 없이 진행) |
| ch1-20 | 칭호 `freshman` | 칭호를 먼저 만들 것 |

NPC 는 Citizens 로 소환한 NPC 를 클릭해 지정 (서버 ID `quest-server-id: school` + Citizens 번호로 저장). 지정하면 운영자 화면에 미리보기가 뜬다 (`/대화관리 단계 미리보기끝`).

## 7. 동작 요약 (1차와 같음)

- 조건: 아이템 보유(버리면 미충족) · 제출(호출 때 걷음, 이후 완료 유지) · 처치 · 블록 상호작용 · 대화완료(`|` 여러 개, 끝 `*` 앞부분 일치) · 신호.
- 호출: 자동(지연) · Citizens NPC 클릭 · 위치 포탈 · 컷신 시청 완료 · 관리자 강제 진행. 단계 `시작명령` (2차 추가).
- NPC 클릭 우선순위: 현재 단계의 호출 NPC 이고 준비 완료면 LOWEST 에서 클릭을 취소하고 스토리 → ChacaNPC(선물·고정·AI)·Bridge 상점·의뢰 클릭 모두 건너뜀 (오너 결정: 퀘스트 우선). 준비 전이면 막지 않고 할 일 알림만.
- 중복 방지: 호출 후 PLAYING 동안 재호출 없음, 완료 신호는 기다리던 대화·컷신과 같을 때만, 대화 못 열면 3초 뒤 재호출 가능, 재접속 시 대화 대기 → ACTIVE (제출 기록 유지), 30분 넘으면 되돌림.
- 패킷: `chacademy:quest_step`(단계·접속·월드 이동) + `chacademy:quest_update`(진행도·준비 상태가 바뀔 때만, 같은 틱 합침). 애니메이션은 클라 시계.
- 진행 기준 서버 = 학교 서버 하나 (`quest-progress.yml` 로컬).

## 8. 명령어 (MagicCodexBridge 허브, OP)

```
/대화관리 단계 목록 | 점검 | 시작설정 <단계> [대화완료 <대화>] | 지정끝 | 미리보기끝
/대화관리 단계 <ID> 만들기 | 삭제 확인 | 정보 | 이동 | 미리보기
/대화관리 단계 <ID> 장|설명 <문구> · 대화|컷신 <ID|없음> · 다음 <단계|없음>
/대화관리 단계 <ID> 시작명령 추가 <명령> | 비우기
/대화관리 단계 <ID> 조건 추가 <아이템|제출|처치|상호작용|대화완료|신호> <대상> <개수> [표시글] · 삭제|숨김|블록지정 <번호> · 목록
/대화관리 단계 <ID> 호출 없음 | 자동 [지연초] | NPC클릭 | NPC <번호> | 위치 [반경] | 컷신 <ID>
/대화관리 신호 <닉네임> <키> [개수]                 (관리자 수동 보정)
/유저관리 <닉네임> 스토리진행 보기 | 다음 | 다시 | 단계 <ID> | 초기화 확인
```

## 9. 검증

**확인한 것 (이 환경)**
- 스텁 javac: 스토리 플러그인 새·바뀐 클래스 (StoryPlugin 제외).
- 단위 테스트 7개: 단계 정의·진행 왕복, 대화완료 패턴(결과·확정 12개 일치 / 6문항 ch1-14 불일치), 시작명령 왕복, 잘못된 패턴 경고, 예전 Skript 명령 차단 + 새 Java 명령 통과.
- 콘텐츠 정적 검사: steps.yml 모든 단계의 대화 파일 존재, 다음 단계 연결, server_command·시작명령·대화 파일 명령에 예전 Skript 명령 없음, ch1-14 끝 장면이 정확히 `p_00~p_33` 16개, 결과·확정 대화 12개 존재.

**확인 못 한 것 (빌드·실서버 필요)**
- 모든 모듈의 실제 컴파일·테스트 (특히 클라 `story/quest/*`).
- Skript 없는 학교 서버에서: ch1-5 → ch1-6 시작, 단계 진행, 6문항 → 결정 → 결과 대화 → ch1-15, 이미 소속인 유저(f-), 조건 충족/해제(아이템 버리기), NPC 클릭 우선순위(ChacaNPC AI·Bridge 상점·의뢰), 재접속(6문항 중·결과 대화 중·포탈 대기 중), 완료 신호 중복(같은 세션 finish 두 번), 성공 신호 4종 실제 발생, HUD·포탈·징표 렌더링.
- `{player}`(계정 이름)를 `/기숙사분류`·`/유저관리`·`/아이템관리 낚시장비`·`/테스트 연금술 열기` 가 받는지 (기존 SchoolBridge `sortingDialogue` 가 `player.getName()` 으로 `/대화관리 재생` 을 부르는 것과 같은 방식).

## 10. 배포할 것 (이번 작업에서는 배포·재시작·런처 공개 안 함)

- JAR: ChacademyStory · 스토리 모드 · MagicCodexBridge (학교·야생) · ChacaHousing · ChacaNPC
- 설정: 위 5절 표 + 6절은 운영자가 게임 안에서.

## 11. 추가 수정 (2026-10-21) — 위치 포탈 대화를 닫았을 때

오너 결정: 위치 포탈로 연 대화를 중간에 닫으면, 포탈 밖으로 **한 번 나갔다가 다시 들어와야** 다시 열린다.
그 전에는 포탈 안에 서 있기만 해도 3초 뒤 저절로 다시 열렸다.

- 파일: `chacademy-story/plugin/.../QuestEngine.java` 하나
- `mustLeave`(메모리 전용 Set): `dialogueAborted` → `backToActive` 뒤, 호출이 위치이고 유저가 포탈 안이면 추가
- `tick` 과 `checkLocation`: `mustLeave` 이면 위치 호출 안 함. `tick` 에서 포탈 밖이거나 호출 방식이 위치가 아니면 지움
- 지우는 곳: `enter`(단계 변경), `onQuit`, `/유저관리 … 스토리진행 다시`·`초기화`. 관리자 `다음`(강제 호출)은 막지 않음
- NPC 클릭·자동·컷신·시작명령 동작은 그대로. 30분 시간 초과·컷신 취소로 되돌릴 때는 표시를 붙이지 않음
- 빌드 확인 못 함 (기존 클래스와 같은 import·타입만 사용, 새 의존 없음)
- 실서버 확인: 포탈 대화 ESC → 그 자리 대기(안 열려야 함) → 포탈 밖 → 다시 들어감(열려야 함)

## 12. 포탈 표시 개선 (2026-10-22) — 스토리 모드만

오너 요청: 포탈 깜빡임, 일렁이는 빛 띠 더 밝게, 블룸("우웅" 느낌), 포탈 위 "퀘스트 목표 지점" 글자.

- 파일: `chacademy-story/mod/.../story/quest/QuestWorld.java`, `QuestArt.java`, 텍스처 3개 추가 (`textures/quest/portal_ring_glow.png`, `portal_glow.png`, `halo.png`)
- **깜빡임 원인**: 바닐라 `RenderType.entityTranslucentEmissive` 는 그림을 최근접 필터·밉맵 없이 묶어서, 링의 1~2px 금색 선이 비스듬히 보이며 돌 때 반짝반짝 깜빡였다.
  → 포탈·징표 그림을 `QuestArt.image()`(밉맵) 로 올리고 새 `QuestArt.worldLayer(tex, additive)` 로 그린다 (POSITION_TEX_COLOR, 선형+밉맵, 깊이 검사만·쓰기 없음, 컬링 끔). 바닥에서 0.04 띄움.
- **블룸**: 후처리 없이 흐린 빛 그림을 빛 더하기(SRC_ALPHA, ONE)로 겹침 — 바닥 빛, 링 번짐, 빛 띠 더하기 2겹, 별 뒤 빛, 글자 뒤 빛. 1.25 rad/s 로 천천히 숨 쉼.
- **별**: 예전엔 완전히 꺼졌다 켜져서 깜빡여 보였음 → 최소 밝기를 남기고 부드럽게 커짐.
- **글자**: 포탈 중심 위 약 2.1블록에 "퀘스트 목표 지점" (Pretendard, 밉맵, 검은 외곽선 + 채운 글자, 나를 바라봄, 살짝 둥실, 36~48블록에서 서서히 사라짐). MagicCodex 폰트가 없으면 바닐라 글꼴.
- 모드 전용 버퍼 `MultiBufferSource.immediate(new ByteBufferBuilder(1<<16))` 에 그리고 끝에 `endBatch()` — 겹치는 순서가 매번 같다.
- 확인 못 함 (빌드 필요): 컴파일 (`ByteBufferBuilder`, `MultiBufferSource.immediate`, `CoreShaders.POSITION_TEX_COLOR`, `Font.drawInBatch(Component,…)`), **글자가 좌우로 뒤집혀 보이지 않는지** (카메라 회전 기준 +x = 화면 오른쪽으로 가정, 뒤집히면 `billboardTex` 의 `mirror` 를 true 로), 셰이더 팩·Fabulous 그래픽에서 보이는지.
- 2026-10-22 추가: 포탈·글자·징표를 `WorldRenderEvents.AFTER_TRANSLUCENT` 에서 그린다 (`QuestClient`). 예전 `AFTER_ENTITIES` 는 물·유리·반투명 엔티티가 나중에 그려져, 그 앞에 있는 글자·포탈이 뒤에 있는 것처럼 덮여 보였다. 확인할 것: Fabulous 그래픽에서도 보이는지.

## 13. 1장 번호 밀기 (2026-10-22)

오너 결정: 1-3 을 인사(→ `하수구 {player}` 이동)와 구덩이 테스트로 나누면서, 뒤 번호를 하나씩 밀어 1-21 까지로 함.
- **MagicCodexBridge `SchoolBridge.java` 2줄**: 분류 대화 ID `ch1-14` / `ch1-14f-` / `ch1-14r-·m-` → `ch1-15…` (학교·야생 Bridge JAR 다시 빌드·배포 필요).
  운영 대화 파일과 steps.yml 도 같은 시점에 바꿔야 한다 (새 Bridge + 예전 파일이면 분류 대화를 못 연다).
- `chacademy-story/plugin/src/main/resources/steps.yml` 기본값을 1장 최신본과 맞춤 (tutorial → ch1-1 … ch1-21, ch1-4 끝 컷신 `magic_tutorial`).
- 고정 보상 키 `ch1-2.e_s11`, `ch1-3.end` 는 그대로 (ch1-3 = 인사 대화 끝).
- 테스트의 ch1-14 문자열은 패턴 로직 검사라 그대로 둠.


### 73dd9db 패치 통합 검증 (task7)
Ctrl HudCursorScreen에서도 퀘스트 HUD와 접기 버튼을 유지하는 부분만 최신 통합에 반영했다. 기존 AFTER_TRANSLUCENT 포털 렌더 및 cdca568 단계 ID는 그대로다. 정책 단위 테스트를 위해 화면 판정을 작은 순수 헬퍼로 분리했으며 MagicCodex 강제 의존은 추가하지 않았다. 배포 전 검토용이며 운영 교체는 하지 않는다.

## 15. 대사는 서버, 그림은 리소스팩 하나 (2026-10-23)

오너 결정: 스토리·퀘스트·AI 대화 내용은 서버에서만 고치고, 런처는 리소스만 가진다. 초상화는 런처 `resourcepacks/chacademia_npc` 하나에 이름별 한 장, 스토리·AI 둘 다 거기서 읽는다.

**대사 (서버 → 클라)**
- 플러그인: 새 채널 `chacademy:dialogue_content` (String id + String dialogue.yml 글, 최대 30만 자·1MB). `loadDefs` 가 `dialogues/<id>/dialogue.yml` 글을 같이 읽어 `dialogueTexts` 에 두고(새로고침 때 갱신), 대화를 여는 세 곳(`requestNicknameDialogue` 의 SESSION/VIEW, 예전 `DIALOGUE_OPEN`, `/테스트 스토리 대화`) 바로 앞에서 `sendContent`.
- 모드: `CutscenePackets.DialogueContentS2C` → `ServerDialogues`(메모리, 접속 끊기면 지움). `DialogueLoader.load/warnings/listIds/exists`, `StoryJournal.content` 가 서버 내용을 먼저 쓰고, 없으면 예전 `config/chaca_dialogue` 파일 (개발용 `/스토리도구`, 예전 서버).
- 예전 모드는 이 채널을 안 들어서 서버가 보내지 않음 → 자기 파일 사용 (섞여도 안전).
- 대화 안의 `commands:`(플레이어 명령·고정 보상)는 그대로 클라가 실행 (서버가 보낸 글 안에 있음).

**그림 (리소스팩)**
- 위치: `magiccodex:textures/gui/dialogue/<이름>.png` — ChacaNPC AI 대화·상점·물고기 판매소가 이미 쓰던 자리라 MagicCodex·ChacaNPC 코드 변경 없음. 리소스팩 이름 `chacademia_npc` 안에 `assets/magiccodex/...` 로 넣는다 (chacademi-server `resourcepacks/chacademia_npc/`, pack_format 46).
- 모드 `DialogueRunner`: 대화 파일의 그림 이름(`bram-neutral.png`) → 리소스팩에 같은 이름이 있으면 프레임 `portrait`(이름), 없으면 예전 `portraitFile`(대화 폴더). 미리 읽기는 파일 그림만. 판정은 `DialogueRunner.packPortrait`(클라 초기화에서 ResourceManager 로 정함, 테스트 기본 false → 기존 테스트 그대로).
- 런처: `options.txt` `resourcePacks` 에 `"file/chacademia_npc"` 켜 두기.

확인 못 함 (빌드 필요): 컴파일, 실서버에서 런처 `config/chaca_dialogue` 를 지운 상태로 1장 진행, 리소스팩 초상화 표시(1024×1536 엘레나 포함), `/서버관리 새로고침 스토리` 뒤 대사 바뀜.

## 16. 장 공개 제한 · 길잡이 별 재연결 (2026-10-25, 오픈 준비)

**장 공개 (메모 #5)** — `ChapterGate`(새, 순수) + `QuestEngine`/`StoryPlugin` 연결, config.yml `chapter-release` (기본: 2장까지 공개, 3장 잠금, 공개 시각 비움).
- 장 번호 = 단계 ID `ch<번호>-…` (없으면 장 이름 앞 `<번호>장`). 장 표시 이름을 바꿔도 공개 상태는 그대로.
- 잠긴 장의 단계: `fire()`(포탈·NPC·자동·컷신·관리자 강제 진행 공통 입구)와 시작명령이 막힘, HUD 에는 장 이름·목표 위치 없이 "아직 열리지 않은 이야기입니다", 포탈·징표 없음. `/대화관리 재생 … 대화|컷신 ch3…` 도 막힘(`/테스트 스토리` 미리보기는 그대로). NPC 클릭은 막지 않고 안내만(평소 AI 대화 유지).
- 운영자: `/대화관리 장공개 목록|열기 <장>|잠그기 <장>` — config.yml 에 저장. 열면 0.5초 tick 이 기다리던 접속자에게 HUD·새 목표 알림·시작명령을 이어 준다. 운영자 미리보기(`단계 … 미리보기`)는 잠금과 무관하게 위치를 보여 준다.
- 문구: `chapter-release.locked-title` · `locked-body`.

**길잡이 별 (메모 #72)** — 2026-10-23 커밋 63459697(origin/claude/story-steps-20261020)이 통합 브랜치에 빠져 있었다(StoryLink·quest_guide 채널·화살표 그림 전부 없음 → 길잡이 별이 퀘스트와 전혀 연결 안 됨). 다시 넣고 오너 10-23 결정 반영:
- `UtilMech.star`: 웅크림 위치 저장 삭제, 퀘스트 목표만 가리킴, **다시 시전 = 끄기**(`GUIDE_OFF`=4, 패킷 session 0), 목표 없음/근처/다른 곳 → 경고 알림·시전 실패.
- `mana-spells.yml` `guiding_star.cooldown-seconds: 10 → 0`(운영 설정 사본 포함), 도감 설명 문구 변경, known-hashes 에 예전 132 해시 추가(배포된 기본 파일만 교체).
- 클라: `QuestClient`/`QuestWorld` 수동 이식(현재 코드에 맞춤, 거리 글자는 `drawInBatch` 그림자). 프록시 서버 이동(JOIN) 때 예전 서버 목표 좌표를 지워 다른 서버 세계에 포탈·화살표가 잘못 그려지지 않게 함.
- 서버: 켠 사람만 0.5초 tick 에서 10블록 판정 (매 틱 전체 순회 없음).
- 2026-10-22 추가 (셰이더팩): Iris 셰이더팩이 켜져 있으면(`IrisApi.isShaderPackInUse()`, 리플렉션 — 의존 없음) 포탈·글자·징표를 셰이더팩이 아는 바닐라 `RenderType.entityTranslucentEmissive` 로 그리고, 빛 더하기(블룸) 패스는 건너뛴다 (셰이더팩 자체 블룸). 직접 만든 POSITION_TEX_COLOR 레이어는 셰이더팩이 섞기를 바꿔 빛 띠가 하얀 조각으로 깨져 보였다. 정점은 두 레이어 모두에 맞게 색·UV·오버레이·빛·법선을 다 쓴다 (없는 속성은 BufferBuilder 가 무시). 확인할 것: 셰이더 켬/끔 둘 다 포탈 모양, 게임 중 셰이더 전환.
