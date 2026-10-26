# Claude 수정 종합 인계 (2026-10-21) — GPT 빌드·배포용

근거 문서: 프로젝트 `claude/system-audit-20261021.md`(Claude 점검), `claude/claude-reply-to-gpt-20261021.md`(GPT 점검 A01~A16 판정 + 오너 결정). 오너(준우)가 "전부 바로 수정"을 승인함.

**빌드 미검증.** 이 환경은 PaperMC·Fabric·Maven 저장소가 막혀 Gradle을 못 돌림. 모든 Java는 문법 검사(javac parse)만 통과함. 컴파일·단위 테스트·실게임 확인은 GPT 쪽에서 해야 함. 컴파일이 깨지면 아래 "컴파일 위험" 목록부터 확인.

## 1. 브랜치 (zzunwoooo/chacademi-magiccodex-source)

| 브랜치 | 기준 | 내용 | 묶음 인계 문서 |
|---|---|---|---|
| `claude/fixes-integrated-20261021` | `codex/followups-integrated-20261020-task12` (0cd1454) | **운영용 통합본.** 아래 6개 묶음 병합 + 충돌 정리 + overlay 재생성 | 이 문서 |
| `claude/fixes-integrated-story-20261021` | 위 통합본 + 대기 중인 Story | 통합본 + `claude/fix-story-20261021`. **새 Story를 켤 때는 이 브랜치로 빌드** | 이 문서 |
| `claude/fix-economy-20261021` | 0cd1454 | A01 연금술·낚시 보상 outbox, A02, A03, A08, 멈춘 판매 정리, C09 낚시 서버 검증 | `CLAUDE_FIX_ECONOMY_20261021.md` |
| `claude/fix-progress-20261021` | 0cd1454 | A09 큐디치 outbox·상태 머신·부하, A06, A05 방구경, A15 ChacaNPC 60회, C02, C12 | `CLAUDE_FIX_PROGRESS_20261021.md` |
| `claude/fix-combat-20261021` | 0cd1454 | C03 아군 판정, A04, A16 chameteor, HIT 계약, 조준 검증, 공중 바닥 마법, HUD 부하 | `CLAUDE_FIX_COMBAT_20261021.md` |
| `claude/fix-client-20261021` | 0cd1454 | VFX 30초 끊김, 블룸, A12, A14, A11 문구, 모션 R키·안전 디코더, HUD 배율, 실패 알림, 낚시 체감, 수조 크기, 잠금 저장 | `CLAUDE_FIX_CLIENT_20261021.md` |
| `claude/fix-config-20261021` | 0cd1454 | overlay 도구·재생성, 기증 목록·공지, 아티팩트 29개 끄기, 작물 손실 방지, 토큰 가림, 아티팩트 조회 묶음 | `CLAUDE_FIX_CONFIG_20261021.md` |
| `claude/fix-dbtool-20261021` | 0cd1454 | C01 SQLite→MariaDB 이관 도구, DB 모드 경고, `use-for-all-domains`, utf8mb4, 큐디치 ALTER | `CLAUDE_FIX_DBTOOL_20261021.md` |
| `claude/fix-story-20261021` | 7e6ac03 (`claude/story-steps-20261020`) | A07, A13, C10, C11, D-12/13, `/기숙사분류`, 로컬 호감도 제거, 퀘스트 패킷 안전 디코더 | `CLAUDE_FIX_STORY_20261021.md` |

다른 저장소:
- `zzunwoooo/chacademi-server` `claude/story-ch1-fix-20261021`: 1장 `steps.yml`에 `시작명령-한번` 설명 추가, ch1-14에 `false` 명시
- `zzunwoooo/chacademi-launcher` `claude/launcher-assets-20261021`: 설치 스탬프·부분 복구·옛 파일 격리·비동기 검증, P0 검사 스크립트(`docs/CLAUDE_LAUNCHER_ASSETS_20261021.md`)

통합 때 Claude가 처리한 것:
- FishingBridge 관리 명령 충돌 해결(economy의 멈춘처리·우편대기 + client의 "아공간 수조" 문구)
- overlay 3개 파일 충돌은 config의 재생성본을 받은 뒤 combat 원본(chameteor, `ground: true`)을 반영해 `tools/operating_overlay.py regenerate`로 다시 만듦. `check` 결과는 차이 0.
- 기증 목록 fish_trap 이름과 스토리 steps.yml 주석을 "아공간 수조"로 맞춤.

## 2. 빌드 순서 (GPT)

1. `claude/fixes-integrated-20261021`로 `Build-Server.ps1 -Verify`(전체 빌드 + 테스트)를 돌림.
2. 깨지면 해당 묶음 인계 문서의 "컴파일 위험" 항목부터 봄(아래 4장에 요약).
3. Story를 켤 때는 `claude/fixes-integrated-story-20261021`로 같은 검증을 돌림.
4. 런처는 `node tools/clientassets.test.js && node tools/clientassets-stamp.test.js`를 돌리고 버전을 0.1.23으로 올림.

## 3. 배포 시 같이 올려야 하는 것·호스트 작업

**같이 배포**
- MagicCodexBridge + MagicMythic + PortableVFX(플러그인·클라) + 운영 overlay(spell-runtime·spell-bindings·chacademi_spells.yml) + MagicCodex 클라 모드를 함께 올려야 함. 일부만 올리면 메테오·조준·낚시가 깨짐.
- 낚시 프로토콜이 v3로 올라감. 모드와 플러그인 버전이 다르면 낚시 패킷이 무시됨.
- ChacaNPC와 Bridge(선물 영수증)도 함께. ChacaKyudichi와 Velocity 파티 플러그인도 함께 올림.

**배포 전 검사**
- `python3 tools/operating_overlay.py check`가 차이 0이어야 함.

**호스트 설정 (코드로는 못 바꿈)**
- Velocity `party.properties`에서 `trusted-servers=school,wild,arena`로 바꾸고 `chat-servers`를 확인.
- 프록시 `kyudichi-database.properties`가 MariaDB여야 함. 아니면 이제 랭크 신청이 막힘(의도한 동작).
- ChacaNPC `start-date` 2026-10-26, `budget.limit-enabled: false`. 하루 60회를 서버 간 합산하려면 학교·아레나 ChacaNPC DB가 같은 MariaDB여야 함.
- 야생 CropAwakening `cancel-drops: false`.
- MythicMobs `SpawningLimit`, `MaxLocalMobs` 패치(10/20 배포에서 빠짐)를 적용.
- 하우징 `server` 이름은 학교·야생이 서로 달라야 함. 하우징 DB는 Bridge와 같은 MariaDB.

**C01 이관**
- `CLAUDE_FIX_DBTOOL_20261021.md`의 런북대로 진행: 중지 → 백업 → dry-run → apply → `use-for-all-domains=true` → 시작 → 검증 → `require-mariadb` 켜기.
- 스테이징 리허설을 먼저 할 것. 도구의 실제 DB 경로는 실행된 적이 없음.

**배포 직후 수작업**
- 미리 기숙사를 정해 둔 유저는 `/유저관리 <닉> 기숙사 <기숙사>`를 한 번씩 다시 실행해야 함(창시자 표식 생성).
- 스토리 기록이 없는 기존 유저는 `/유저관리 <닉> 스토리진행 단계 ch1-6`으로 수동 이동.

**C13 토큰**
- Polymart 토큰은 파일에서 가렸지만 git 이력에 남아 있음 → 토큰 재발급 필요.

## 4. 컴파일 위험 요약 (빌드에서 먼저 볼 곳)

- **client**: `SafeCodec.failSafe(StreamCodec.of/composite…)` 제네릭 추론이 실패하면 타입 인자를 명시. `SafeCodecTest`의 `RegistryAccess.EMPTY`. `FabricLoader.isDevelopmentEnvironment()`.
- **combat**: Paper `Bukkit.getEntity(UUID)`, `World.getNearbyLivingEntities(Location,double)`. Fabric 쪽 `record Payload implements CustomPayload` 등록, `Box.minX`, `MathHelper.clamp(double…)`. `AllyResolverTest`는 Mockito inline 전제.
- **economy**: `HousingStore.async`의 `if … try/catch` 형태, `AffinityStore` 빈 try-with-resources, `FishingBridge.deliver`의 `all[0] &= ok`, `MagicCodexLink.takeGift`의 리플렉션 int 인자, housing 테스트용 H2 추가(`ExpandLedgerTest`).
- **progress**: `YamlConfiguration.saveToString/loadFromString`(ResultOutbox), Velocity `buildTask(...).delay`, `isLoaded("luckperms")`, `RatingStore.record` 람다 반환 타입 추론, `DialogueService.tryUseCall`이 `CompletableFuture<Boolean>`으로 바뀜.
- **config**: `CropAwakeningTest`의 `MythicMob.spawn(…, double, …)` stub(레벨 인자가 int면 `anyInt()`), `SchoolBridge`의 `createSection(String, Map)`, `getResource`.
- **dbtool**: H2 MODE=MySQL이 `ENGINE/DEFAULT CHARSET/COLLATE`를 받아들이는지(`ArtifactOwnershipStoreTest`).
- **story**: `QuestEngine`에 새로 넣은 인벤토리·드롭·사망 이벤트 핸들러 9개, `SchoolBridge`의 `초기화` 리터럴과 `<기숙사>` 인자가 겹치는 Brigadier 순서.

## 5. 동작 변화 (유저가 체감하는 것)

- 파티 밖 유저에게는 힐·버프가 안 들어감. 파티원끼리는 피해·CC·표식이 안 들어감. 큐디치·결투는 경기 팀 기준.
- falling_star 피해 시점이 25틱에서 120틱으로 바뀜. 목표점에 착탄하고 지붕은 관통함.
- 일부 볼트가 설정 속도대로 느려짐(예: mountain_back 0.8 → 0.5 블록/틱). 반 블록·울타리 위로 넘겨 쏘기가 가능해짐.
- 연금술은 결과물이 들어갈 자리가 없으면 제작이 거절됨. 아티팩트 29개가 뽑기에서 빠짐.
- HUD "크기" 설정은 이제 핫바 크기만 바꿈. 16:9 화면에서 100%면 핫바가 초상화 위로 올라감.
- 블룸 0.40, 어깨 옆 거리 1.0(기존 1.5 설정은 1회 이전).
- "이로치" → "변색종", "통발" → "아공간 수조". 관리자 명령 이름도 바뀜.
- 낚시: 흔들림이 줄고, 물고기 "지침" 신호가 생기고, 입질 뒤 놀래키기에도 미끼가 1개 듦(**오너 확인 대기**).

## 6. 이번에 안 한 것

- VFX·판정 투사체 단일화(L 규모)
- 인기도 기능(기획 미정)
- 채팅창·알림 화면 개편(UI)
- 지하 던전 시스템
- 낚시 게이지 값에 잡음 섞기(변조 클라의 등급 추정 차단, 체감이 바뀌므로 오너 결정)
