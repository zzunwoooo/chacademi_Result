# 명령어 전면 정리 — 한글 대단원·Brigadier·실패 알림 (Claude, 2026-10-20)

브랜치: `claude/command-cleanup-20261020` (기준: `codex/operating-final-20261019` @ `ef55623`)
운영자용 안내: [docs/ADMIN_COMMANDS_KO.md](../ADMIN_COMMANDS_KO.md) (새 명령 전체)

## 0. 먼저 읽을 주의사항 (GPT)

1. **빌드 안 했음.** 이 환경은 Gradle·Maven 저장소에 못 붙습니다.
   - Paper API 1.21.4·Adventure·Brigadier·LuckPerms 소스로 `javac -sourcepath` 타입 검사만 했습니다. 바꾼 Paper 모듈 전부 **우리 코드 오류 0** (남은 오류는 MythicMobs·ModelEngine·WorldGuard·Citizens·OpenCV·PlaceholderAPI·JOML 등 외부 jar 없음, 그리고 기존 `DiscoveryLink`의 생성 소스 `BiomeTagKeys`).
   - **Velocity·Fabric(클라이언트 모드 3개)은 타입 검사 못 함.** Velocity는 손으로 만든 최소 API 대용품으로만 검사, Fabric은 손 검사. 첫 빌드에서 컴파일 오류부터 확인해 주세요.
   - JUnit 테스트도 못 돌렸습니다. `Build-Server.ps1 -Verify`로 전체 빌드·테스트 부탁합니다. 지운 명령을 쓰던 테스트는 고치거나 지웠습니다(아래 각 절).
2. **전부 같이 배포해야 합니다**: Velocity 파티 플러그인, 모든 Paper 서버의 MagicCodexBridge·ChacaKyudichi·ChacaHousing·ChacademyMotion·ChacademyStory·ChacaNPC·ChacaPortrait·MagicMythic·ChacaGimmick·Wildlife·Tornado·PortableVFX·MagicDiscovery, 그리고 클라이언트 모드(MagicCodex, chacademi-chat-layout, ChacademyCutscene, PortableVFX client).
   - 모드가 서버로 보내는 명령 문자열이 바뀜(`채팅 전체|기숙사`, `파티 채팅`, `스탯창`, `의뢰`).
   - 큐디치 프록시↔경기장 서버 프로토콜에 관리 요청이 추가됨(`KyudichiQueueProtocol` AdminRequest 5 / AdminReply 4), 관전 나가기 패킷 추가(`KyudichiProtocol.SpectateLeave`, C=5), 미리보기 채널 추가(`magiccodex:preview`).
3. **명령어 허브 구조** (`magic-codex-paper`: `ChacaApi`·`CommandHub`·`ChacaArguments`·`ChacaFeedback`·`ChacaPermissions`)
   - 대단원 15개(`/관리` `/유저관리` `/아이템관리` `/상점관리` `/기숙사관리` `/퀘스트관리` `/대화관리` `/NPC관리` `/큐디치관리` `/하우징관리` `/월드관리` `/던전관리` `/컨텐츠관리` `/서버관리` `/테스트` `/개발`)는 MagicCodexBridge만 Paper Brigadier(`LifecycleEvents.COMMANDS`)로 등록. 다른 플러그인은 onEnable에서 갈래를 붙임 — 같은 빌드면 `ChacaApi` 직접, 아니면 각 플러그인의 `ChacaLink`(리플렉션).
   - **모든 플러그인 plugin.yml에 `softdepend: MagicCodexBridge` 필요** (넣어 둠). 늦게 켜지면 콘솔에 "명령어 등록이 끝난 뒤에 … 붙였습니다" 경고.
   - 관리 대단원은 OP(또는 콘솔)만. 기존 명령 권한 노드(`xxx.admin`)는 지움. 보유 판정 권한(빗자루·가구·펫·레시피·칭호 등)은 그대로.
   - 갈래 실행은 허브가 감쌈: 예외는 콘솔 `[명령실패] …` + 유저 알림 "명령어 사용에 실패했습니다"(error 카드·삐빅). 유저가 고칠 실패는 짧은 문구만.
   - 한글 칸: 서버는 "공백 전까지 아무 글자" 칸(`ChacaArguments.Word`, 클라에는 word())로 받고, 클라 모드 믹스인 `BrigadierUnicodeWordMixin`이 word()에 한글·괄호 등을 허용(공백·따옴표·`, : = { } [ ] ;`만 막음). **믹스인이 brigadier에 적용되는지 첫 실행 로그 확인** (defaultRequire 1 → 실패 시 시작 크래시).
   - Paper가 `CommandMap`에 Brigadier 명령을 노출하는지에 기대는 곳: `ManaBridge`(mana-spells `command:` 시전 경로), `QuestBridge`(보상 명령 존재 경고). 실게임에서 `/마법 <id>` 시전 확인.
4. **알림**: 서버는 일지 채널 kind 4로 `system|warning|error|shop` 알림 카드를 보냄(`ChacaApi.notify`). 액션바는 실시간 표시(큐디치 F키, 아티팩트 방향·거리, 음표 n/10, 길잡이 별, OP 기믹 디버그)만 남김. AI NPC "생각 중" 액션바 삭제.

## 1. 운영 설정 변경 필요 (jar 교체로 안 바뀜)

| 파일 | 바꿀 것 |
|---|---|
| `ChacademyStory/dialogues/server_command.yml` (1장 추가분 포함) | `storydialogue {player} <id>` → `대화관리 재생 {player} 대화 <id>` · `컷신 재생 {player} <id>` / `cutscene play {player} <id>` → `대화관리 재생 {player} 컷신 <id>` · 1-14 `기숙사점수 소속 {player} {ending}` → `유저관리 {player} 기숙사 {ending}` (arkeon/lumina/bestiaz/noxer 값 그대로 받음) |
| `ChacademyStory/config.yml` `on-finish` | 같은 규칙 (예 `storydialogue {player} ch1-20` → `대화관리 재생 {player} 대화 ch1-20`). `affinity-default/min/max` 키·`affinity.yml`은 이제 안 씀 |
| `MagicCodexBridge/config.yml` `first-nickname.on-complete` | `대화관리 재생 {player} 대화 ch1-2` 권장 (예전 `storydialogue …` 문자열도 설정 값으로는 계속 인식) |
| 스토리 1-1 `endings.wake → /최초닉네임설정` | 그대로 둬도 됨 (`/최초닉네임설정` 본인용 유지) |
| LuckPerms | 지운 명령 권한 노드 정리 가능: `magiccodex.*.admin`, `chacademy.broom.admin`, `chacademy.motion.admin`, `chacademi.kyudichi.admin/.play`, `chacademi.housing.admin/.anywhere/.visit`, `chacanpc.admin/.talk`, `chacaportrait.admin`, `magicdiscovery.admin` 등. `chacademi.housing.visit=false`로 막던 그룹이 있었다면 이제 무효 |
| 실서버 폴더 전체 검색 | MythicMobs 스킬·Citizens NPC 명령·ItemsAdder·퍼즐 commands-on/off·의뢰/대화 보상 명령 안에 예전 명령(`/cnpc`, `/portrait`, `/t `, `/party`, `/chacachat`, `/큐디치 …`, `/kyudichi`, `/qa`, `/큐디치관리 경기장`, `/마법미몹`, `/기믹`, `/야생관리`, `/chatornado`, `/pvfxserverdebug`, `/motion play`, `/빗자루 선택|타기|숙련도`, `/마법봉 give`, `/마력코어 give`, `/재구성관리`, `/아티팩트관리`, `/연금술관리`, `/낚시관리`, `/물고기상점관리`, `/칭호추가`, `/칭호관리`, `/의뢰관리`, `/기숙사점수`, `/점수관리`, `/대화 `)이 있으면 새 명령으로 (`docs/ADMIN_COMMANDS_KO.md`) |
| `creature-spawns-paper/tools/Test-CropHarvest.ps1`, `deployment/Apply-WildlifeConfig.ps1`·`README.txt` | `wildlifeadmin reload/status/check` → `서버관리 새로고침 야생` / `월드관리 야생몹 상태` / `월드관리 야생몹 확인 <몹>` (PowerShell 5면 UTF-8 BOM) |
| ItemsAdder | 초상화 재생성권 지급은 플러그인 명령이 없음 (ItemsAdder `item:reroll` 지급 명령 사용) |

## 2. 실게임에서 확인할 것 (우선순위)

1. 클라이언트 시작 시 믹스인 오류 없음, 한글·괄호 이름 칸이 빨갛지 않음, NBT·셀렉터 명령 정상
2. `/관리` 메뉴·대단원 도움말 클릭 입력, OP 아닌 사람에게 관리 명령 안 보임
3. 실패 알림 카드 + 삐빅, 콘솔 `[명령실패]` 로그
4. `/마법 <한글이름|id>`·마법 모드 시전, `/서버관리 새로고침 마법`이 spell-runtime.yml까지 다시 읽음
5. 콘솔에서 `대화관리 재생 <계정명> 대화 ch1-2` → 끝 명령까지
6. Velocity 3.x `BrigadierCommand` 등록(`/파티`, `/채팅`, `/큐디치`, `/결투`), `/파티 초대 <한글닉>`
7. 큐디치 `편집` 흐름·`검사` 클릭, `경기 시작` 팀 칸(`닉+닉+닉`), 관전 나가기 버튼, `/유저관리 <닉> 결투 …`, `/유저관리 <닉> 파티 보기`(해당 서버 접속자 1명 이상 필요)
8. LuckPerms 보유 주기·회수(빗자루·가구·펫·서클 승급), 오프라인 유저 포함
9. `/테스트 화면 …` 미리보기 각각, `/테스트 스토리 대화`(이미 본 대화도, 보상 없음)
10. Vault `돈`, `우편 보내기`(손에 든 아이템 첨부·실패 시 반환)

## 3. 모듈별 구현 기록

## 하우징 (housing-paper)
- /하우징관리 문 등록 [기숙사|모두] · 문 삭제 · 문 목록 · 가구목록
- /유저관리 <닉> 방 크기 <1~32> · 방 구경 · 방 배치초기화 [슬롯] 확인 · 방 방명록 보기 · 방 방명록 삭제 <번호> · 방 좋아요초기화 확인
- /유저관리 <닉> 가구 보기 · 가구 주기 <가구|전체> · 가구 회수 <가구|전체> (LuckPerms chacademi.housing.furniture.<ID>; 주기 전체는 목록의 가구를 하나씩, 회수 전체는 *까지 전부)
- /테스트 하우징 내방 · /방구경 <닉네임> (누구나, visit-command:false면 OP만)
- 새로고침: 하우징 · 요약: 방 크기(접속자만)
- 지움: /하우징관리 크기·열기·리로드·도움말, /내방, 별칭 housing-*, 권한 chacademi.housing.admin/.anywhere/.visit (→ OP 판정)
- 운영: LuckPerms에서 chacademi.housing.visit=false로 막은 그룹이 있었다면 이제 무효. config.yml/furniture.yml 주석의 옛 명령은 기능 영향 없음
- 실게임 확인: 한글 기숙사 칸 자동완성, 오프라인 가구 주기·회수, 방명록·배치 초기화 후 열린 창 갱신, OP의 다른 기숙사 문 통과

## 빗자루·모션 (chacademy-motion-plugin)
- /빗자루 [정보|목록|장착 <빗자루>] (누구나)
- /유저관리 <닉> 빗자루 보기 · 지정 <빗자루> · 해제 · 숙련도 <0~100>(오프라인 가능) · 주기 <빗자루|전체> · 회수 <빗자루|전체> (LuckPerms chacademy.broom.own.<ID>, 주기 전체 = own.*)
- /테스트 빗자루 타기 [닉네임] · /테스트 모션 <모션> [닉네임]
- 새로고침: 빗자루(brooms.yml), 모션(config.yml) · 요약: 장착 빗자루·숙련도
- 빗자루 칸: 한글 이름(띄어쓰기→_) 또는 ID
- 지움: /broom, 영어 서브명령, /빗자루 선택·해제·숙련도·타기·새로고침, /motion play·reload (인자 순서 바뀜: /테스트 모션 <모션> <닉네임>)
- 액션바 2곳 → 경고 알림 (broom-blocked-world, broom-practice-timeout)
- 운영: LuckPerms의 chacademy.broom.admin / chacademy.motion.admin 정리 가능, 외부 설정에서 /motion play·/빗자루 관리 명령을 부르던 곳 교체

## 클라이언트 모드 (magic-codex-fabric, chacademi-chat-layout)
- 알림 "error" 종류: 경고 카드 모양·spell_unavailable 아이콘, 노트블록 비트 두 번(피치 1.8, 약 175ms 간격)
- 한글 칸 믹스인 BrigadierUnicodeWordMixin: StringReader.isAllowedInUnquotedString이 공백·제어문자·" ' \ 와 구조 문자 , : = { } [ ] ; 를 뺀 모든 문자 허용 (NBT·셀렉터·블록 상태 파싱 유지). defaultRequire:1이라 적용 실패 시 시작 때 크래시 → 첫 실행 로그 확인
- 미리보기 수신 PreviewClient (magiccodex:preview): kyudichi·targethud·season·temperature·notice·sound·discovery·ascension
- 관전 "나가기" 버튼: 포기 버튼 그림 재사용, 관전 중(myTeam==2)에만, SpectateLeave 전송
- 클라 명령: /화면 [켜기|끄기|새로고침], /화면표시 <테두리 n|켜기|끄기|파티 …|온도효과 …>, /펫도감 [새로고침], /장비, /우편함, /닉네임, /조준, /시점, /아티팩트도감, /내일러스트
- 삭제: /UI /ui /hud /큐디치미리보기 /대상체력바미리보기 /ascensionpreview /discoverypreview /마법효과음테스트 /petcodex /equipment /메일함 /닉네임설정, 클라 /친구 /친구창 /의뢰게시판 /학교기증
- 모드가 보내는 명령: 스탯창 <id>, 의뢰(상단 메뉴), 파티 채팅 <내용>, 채팅 전체|기숙사 <내용>
- 빌드 확인 불가(마인크래프트 매핑 없음): 첫 빌드에서 컴파일 오류 확인 필요. visualTest의 PetVisualCheck는 원래부터 깨져 있었음

## 스토리 (chacademy-story plugin + mod)
- /대화관리 재생 <모두|닉네임> 대화 <ID> · 재생 <모두|닉네임> 컷신 <ID> [건너뛰기허용|건너뛰기금지|자동] · 중지 <모두|닉네임> · 대기열비우기 <모두|닉네임> · 목록 [닉네임] · 클라이언트새로고침 [닉네임]
- /유저관리 <닉> 스토리기록 보기 [대화] · 초기화 <전체|대화> 확인
- /테스트 스토리 대화 <ID> [닉네임] (이미 본 대화도 다시, 보상·끝 명령 없음) · 컷신 <ID> [닉네임]
- 새로고침: 스토리 · 상태: 스토리
- 클라: /스토리도구 재생 <ID>|대화|컷신|목록|새로고침|본기록초기화 (/story /컷신도구 /ccs 삭제)
- 지움: /cutscene /컷신 /storydialogue /스토리대화 /스토리 /affinity /호감도, 예비 호감도(AffinityStore·affinity.yml·config affinity-default/min/max)
- 운영 설정 변경 필요 (오너 PC):
  - plugins/ChacademyStory/dialogues/server_command.yml: `storydialogue {player} <id>` → `대화관리 재생 {player} 대화 <id>`, `컷신 재생 {player} <id>`/`cutscene play {player} <id>` → `대화관리 재생 {player} 컷신 <id>`, 1-14 `기숙사점수 소속 {player} {ending}` → `유저관리 {player} 기숙사 {ending}`
  - plugins/ChacademyStory/config.yml on-finish: 같은 규칙 (예 `storydialogue {player} ch1-20` → `대화관리 재생 {player} 대화 ch1-20`)
  - plugins/MagicCodexBridge/config.yml first-nickname.on-complete: `대화관리 재생 {player} 대화 ch1-2` (예전 storydialogue 문자열도 설정 값으로는 계속 인식하도록 파서 호환 유지)

## AI NPC·초상화 (chaca-npc-paper, chaca-portrait-paper)
- /NPC관리 소환 <캐릭터> · 연결 <캐릭터> · 연결해제 · 목록 · 장소 <이름> [반경] [표시이름] · 경로 <추가|비우기> <장소> · 분위기노트 <보기|승인|삭제|비우기|지금생성>
- /유저관리 <닉> AI대화 보기 [NPC] · 호칭 <NPC|전체> <설정 <이름>|초기화> · 기억 <보기|삭제 <번호>|초기화 [NPC] 확인> · 대화종료 · 사용량
- /테스트 NPC 대화 <캐릭터> <문장…> · 대화종료
- /서버관리 AI <켜기|끄기|예산|캐시> · /서버관리 초상화 <예산보기|예산조정 <달러>|모델 [모델]>
- /유저관리 <닉> 초상화 보기 · 다시그리기 [모델] · 초기화 확인 · 재생성권 보기 · 환불
- 유저 명령: /말 [내용] · /말 그만 · /호칭 <이름|초기화> · /대화선택 (채팅 버튼 내부용)
- 새로고침: NPC, 초상화 · 상태: AI NPC, 초상화
- 지움: /cnpc, /t, /talk, /nickname, /chacanpc-ui, /portrait, /초상화, 권한 chacanpc.admin·chacanpc.talk·chacaportrait.admin
- AI NPC "생각 중" 액션바 삭제
- 미구현: 초상화 재생성권 주기 (재생성권은 ItemsAdder 아이템 item:reroll → ItemsAdder 명령으로 지급)
- 운영: 외부 설정에서 cnpc·portrait·t 명령을 부르던 곳 교체. EssentialsX /nickname 충돌 해소

## 큐디치·결투·파티·채팅 (kyudichi-paper, chacademi-party-velocity)
- /큐디치관리 목록 · 생성 <이름> [3대3|1대1] · 편집 <경기장> · 편집끝 · 정보
  선택도구 · 지점1 · 지점2 · 선택 <확장 <위|아래|사방> <칸>|해제>
  경계 · 공영역 · 스니치영역 · 골대 <파랑|빨강> · 금지영역
  시작위치 <파랑|빨강> · 부활위치 <파랑|빨강> (빈 칸 1→2→3 자동) · 준비위치 <파랑|빨강> · 퀘이플위치 · 블러저위치(1→2 자동) · 스니치위치
  성소 <체력|마나|안정성|가속|전부> · 삭제 <항목> [번호] · 검사(줄 클릭 시 고치는 명령 입력) · 테두리보기 · 이동 · 켜기 · 끄기 · 연습장 <켜기|끄기> · 경기장삭제 <이름> 확인
  경기 목록 · 경기 시작 <경기장> <파랑팀 닉+닉+닉> <빨강팀 닉+닉+닉> (레이팅 미반영) · 경기 종료 [경기장]
  시즌 시작 <큐디치|결투> [일수] · 시즌 초기화 <큐디치|결투> 확인
- /유저관리 <닉> <큐디치|결투> 보기 · 레이팅설정 <값> · 레이팅추가 <값> · 레이팅초기화 · 전적초기화 확인 · 경기에서빼기 · 대기열에서빼기 · 매칭상태초기화 · 금지 <기간|영구> · 금지해제 (결투 = DB DUEL 모드, 새로 관리 가능)
- /유저관리 <닉> 파티 보기 · 빼기 · 해산 확인 (프록시에 요청 — 경기장/해당 서버에 접속자가 1명 이상 있어야 보낼 수 있음)
- /테스트 큐디치 시작 <경기장> [준비:초 시간:분 스니치:초 무적 블러저끔] · 공 <경기장> · 참가 <경기장> <파랑|빨강> [닉네임] · 종료 · /테스트 결투 시작 <경기장>
- Velocity: /큐디치 (로비 창) · /결투 (결투 로비 창) · /파티 [초대|수락|거절|탈퇴|위임|강퇴|해산|목록|채팅] · /채팅 <전체|기숙사> <내용>
- 관전 나가기: SpectateLeave 패킷 처리
- 프로토콜: KyudichiQueueProtocol AdminRequest(종류 5)·AdminReply(종류 4) 추가 → 프록시·경기장 서버·클라이언트 동시 배포 필요
- 지움: /큐디치관리 막대·pos1·pos2·경기장 …·테스트·공테스트·유저·시즌시작·시즌초기화·리로드·대기열, Paper /큐디치, 프록시 /큐디치 서브명령·kyudichi 별칭, /party 영어 동작, /chacachat·/네트워크채팅, 권한 chacademi.kyudichi.admin·.play
- 운영: 서버 설정 안의 /큐디치 …, /kyudichi, /qa, /kyudichi-admin, /party, /chacachat 문자열 검색·교체
- 실게임 확인: Velocity 3.x BrigadierCommand 등록, /파티 초대 한글닉 자동완성, 경기 시작 팀 칸(+ 구분), 관전 나가기 버튼, /유저관리 파티·대기열 요청

## 몹마법·던전 기믹·야생·토네이도·VFX·마법발견
- /유저관리 <닉> 마법슬롯 [비우기|<마법…>] (접속 중인 유저만, 최대 6) · 마법조건 진행 <보기 [키]|설정 <키> <값>> · 마법발견보상 <보기|다시주기 <번호>|완료처리 <번호>>
- /아이템관리 칭호보상 등록 <마법>
- /월드관리 야생몹 <상태|진단 [닉네임]|확인 <몹>|구조물> · 토네이도 <보기|멈추기>
- /던전관리 소환 · 아이템 <모델> [개수] · 씌우기 · 벗기기 · 도구 · 선택 · 정보 · 근처 · 목록 · 모델 · 설정 <방향|크기|높이|위치|상태> · 애니메이션 · 노드 · 제거 · 퍼즐 · 퍼즐신호 · 퍼즐초기화
- /테스트 마법 시전 <마법> · 마법 이펙트 <마법> [단계] (300~323 몹 습득 마법은 시전으로 안내) · 몹 예고 · 몹 예고효과 · 몹 상태이상 · 몹 작물몹 <작물> [이로치] · 마법발견 <이벤트|상태>
- /개발 이펙트 재생 · 따라가기 · 마무리 · 정지 · 전부정지 · 준비상태 · 상태
- 클라: /이펙트도구 (서브명령 한글)
- 새로고침: 몹마법, 던전, 야생, 이펙트, 마법발견 · 상태: 몹마법, 야생몹, 이펙트
- 지움: /마법미몹, /기믹, /야생관리, /chatornado, /pvfxserverdebug, /pvfxdebug, /마법발견관리, /칭호수령(→ /칭호 수령), 죽은 SpellCommand
- 액션바 유지: 길잡이 별 방향·거리, OP 기믹 디버그 도구. 나머지는 알림
- 운영: creature-spawns-paper/tools/Test-CropHarvest.ps1, deployment/Apply-WildlifeConfig.ps1·README.txt 의 `wildlifeadmin reload/status/check` → `서버관리 새로고침 야생`, `월드관리 야생몹 상태`, `월드관리 야생몹 확인 <몹>` (PowerShell 5에서 쓰면 UTF-8 BOM으로 저장)

## MagicCodexBridge 2 (마나·학교·친구·닉네임·대화·의뢰·계절·온도·펫·교화·이로치·승급·캐릭터 상태)
- /유저관리 <닉> 서클 보기|설정 <1~9> (PDC·codex_player_state·스탯창 + LuckPerms magiccodex.ascend.2~N) · 마법쿨타임 보기|초기화 · 기숙사 <기숙사>(arkeon 등 ID 값도) · 기숙사 기증보기 · 의뢰기록 보기|처리 <퀘스트> <주기> <완료처리|다시지급|되돌리기> · 대화기록 보기|처리 <기록> [다시실행|완료처리] · 닉네임 창열기|다시정하기|설정 <새닉네임> · 온도 보기|설정|초기화 · 친구 보기|끊기|요청비우기 · 펫 보기|주기|회수 · 캐릭터잠금 보기|풀기 확인
- /기숙사관리 점수 [<기숙사> 보기|설정|추가|빼기] · 점수초기화 확인 · 기증마법 보기|금지|해제 · 기증마법초기화 <마법|전체> 확인
- /퀘스트관리 편집 · 목록 · 만들기 · 설정 · 목표 · 제출아이템 · 보상아이템(MATERIAL:수량만 저장 — 이름·인챈트는 빠짐) · 보상명령 · 열기 · 닫기 · 주기설정 · 삭제 확인 · 파일로내보내기 · 파일반영
- /대화관리 파일반영 <대화> · 파일로내보내기 <대화> · 열기 <닉네임> <대화>
- /월드관리 계절 보기|변경|자동 · 이로치 지정|해제
- /테스트 큐디치 화면 · 몹 체력바 · 몹 교화몹 · 마법 효과음|발견화면|승급화면 · 화면 계절|온도|알림 · 의뢰 이벤트
- 유저 명령: /마법 <마법>(한글 이름 자동완성), /스탯창 [닉네임], /친구, /의뢰, /기숙사, /메인퀘스트, /클래스승급, /교화, /최초닉네임설정(본인용 — 스토리 1-1 endings.wake가 부르므로 유지)
- 새로고침: 마법(mana-spells.yml + spell-runtime.yml, 예전 버그 수정), 마나, 기증, 교화, 이로치, 펫, 온도, 계절, 대화, 퀘스트 · 상태: MythicMobs 실행 경로, 마법 목록, MCPets, 교화 대상, 캐릭터 공유 저장 · 요약: 기숙사, 서클
- 지움: /마나관리 /마법관리 /스텟창 /기숙사점수 /학교점수 /학교기증 /기증기록 /의뢰관리 /의뢰게시판 /questboard /대화 /대화관리(구) /계절관리 /온도관리 /펫관리 /교화관리 /이로치관리, /최초닉네임설정 <플레이어> [강제]

## MagicCodexBridge 1 (스탯·마법·호감도·퀘스트·칭호·아티팩트·장신구·연금술·낚시·상점·우편·돈·아이템)
- /유저관리: 스탯, 마법, 마법조건(보기·주기·회수·초기화 확인), 퀘스트(예전 `퀘스트기록편집` 앞붙임 문법 삭제), 호감도(+하트·호칭 — `AffinityStore.adminHeart`, 하트를 내리면 점수가 새 상한으로 깎임), 칭호(+장착, 보유한 것만), 아티팩트(보기·주기·회수·초기화·장착해제·쿨타임초기화·보관품돌려주기(튼튼한 끈 PDC)·뽑기기록(중복 조각 우편 codex_artifact_delivery만)), 장신구, 연금술, 낚시(도감·수조), 재구성권, 우편(보내기·목록·삭제·기록·처리 — 삭제는 직전에 목록을 본 사람만), 거래기록, 돈(Vault)
- 접속 중이어야 하는 것: 장착해제·쿨타임초기화·보관품돌려주기·장신구·스탯·퀘스트·재구성권
- /아이템관리 전부, /상점관리 전부(물고기판매소 포함, 예전 /상점관리 물고기 위임 경로 삭제 → 물고기판매소 권한 누락 버그 해소), /컨텐츠관리, /테스트 낚시·연금술·아티팩트(전체보유는 메모리만, 새로고침 시 yml 값)
- 유저 명령: /칭호 [수령](MagicDiscovery.claimTitles 리플렉션), /아티팩트뽑기, /연금술, /낚시도감, /강화, /코어감정, /마력재구성
- 새로고침: 칭호, 상점, 물고기판매소, 낚시, 연금술, 아티팩트, 재구성, 마법봉, 마력코어
- 삭제한 테스트: ShopCommandCompletionTest, AdminCommandRulesTest의 plugin.yml commands 검사. BootstrapTest는 plugin.yml permissions(magiccodex.mailbox·shop)를 계속 확인하므로 permissions 블록은 유지
