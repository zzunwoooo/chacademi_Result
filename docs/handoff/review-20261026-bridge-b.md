# 코드 검수 2026-10-26 — bridge-b (MagicCodexBridge 운영 기록·청크 점유·인기도·확성기·편의 명령·아티팩트 등)

범위: `magic-codex-paper/.../paper/` 의 AdminCommandBridge, AffinityStore, AppraisalBridge, Artifact*(Actions·Bridge·Broom·Comfort·Companions·Effects·Life·MagicRules·Motions·Shadows·Shapeshift), Audit*(Commands·Log·Queue·Stats·Store·Text), BookBridge, ChacaApi, ChunkClaim*(Bridge·Rules·Store·WorldGuard), ClimateService, CmdKit, CombatLockRules, CombatSpec, CombatTag, CommandHub, Convenience*(Commands·Rules), DailyLimits, DialogueBridge, DiscoveryLink, DiscoveryStats, DisplayNames, Enhancement*(Bridge·Rules), EquipmentBridge, HouseBoardBridge, LessonBridge, LifeSpells, MagicCodexBridge, ManaAccount, ManaBridge, ManaService, Megaphone*(Bridge·Rules), NpcSocial*(Facade·Service), OperatorCommands, Popularity*(Bridge·Rules·Store), ReconfigurationBridge, SchoolBridge, ServerHeartbeat, SparkProbe, SpellHints, SpellRules, SpellRuntime, SpellUtility, StatsBridge, TitleBridge + 이들이 읽는 `resources/*.yml` 기본값.
기준: 70044a79..c8e27c44 의 변경분을 처음 읽는 사람 눈으로. 오너 규칙 (1) 서버 과부하 금지 (2) 치명 버그(복제·유실·동시성·권한·패킷).

## 결과 요약

- 치명: 없음.
- 높음 1건 → **고침** (청크 점유 표가 야생1·야생2 를 구분하지 않아 같은 좌표가 서로 섞임).
- 중간 2건, 낮음 다수 — 보고만 (아래 표).
- 특히 확인하라고 한 항목: 운영 기록·통계 쓰기(시전마다·NPC 대화마다 DB 안 침, 메모리 대기열만), 발견 통계 전달 비용, 블록 이벤트의 청크 판정(메모리 표만, DB 없음), 인기도 일일 제한(DB 트랜잭션 + FOR UPDATE, 해제권 선차감·실패 반환), 확성기 서버 간 전달, 하트비트 타이머, 힌트권 진행도(비동기·메인 스레드 콜백·3초 상한), CombatTag(틱 작업 없음), DisplayNames 캐시, 편의 명령 OP 제한(/겜모·/gm·/텔포·/소환·/공지·/뒤로·/back·/스폰·/spawn·/메모리·/도움기록 전부 `requires(OP)` 이고 CommandHub 복사가 requires 를 보존) — 모두 이상 없음.

## 찾은 것

| 심각도 | 위치 | 무엇이 문제인가 (상황) | 처리 |
|---|---|---|---|
| 높음 | `ChunkClaimStore.java` (표 키 PRIMARY KEY(world,cx,cz)), `ChunkClaimBridge.loadIndex`·`sell`·`adminRelease` | 야생1(server-id wild)·야생2(wild2)는 **다른 호스팅의 다른 서버**인데 둘 다 세계 이름이 `world` 이고 MariaDB 를 공유한다. 키에 server 가 없어 (a) 야생1에서 (world, 5, 5) 를 점유하면 야생2 의 같은 좌표는 `TAKEN` 으로 거절되고 (b) 야생2 가 시작할 때 `load(worlds)` 로 야생1 점유까지 다 읽어 와 야생2 의 그 청크를 남의 땅으로 보호해 버린다 (판매·해제도 서버 구분 없이 지움). | **고침** (e0ed52e7): PK 를 (server, world, cx, cz) 로, `load(server, worlds)`·`release(server, …)` 로 서버를 가름. 예전 키로 만들어진 표는 열 때 한 번 키를 바꿈 (MariaDB `ALTER … DROP PRIMARY KEY, ADD PRIMARY KEY`, SQLite 는 표 재생성). `claimsOf`(관리 목록)·1인 최대 수는 예전처럼 모든 서버 합계. JUnit(ChunkClaimTest)에 다른 서버 같은 좌표 케이스 추가. |
| 중간 | `BookBridge.sendMarks` (5초 타이머, 모드 접속자마다) | 책 블록 표시: 5초마다 모든 모드 접속자에 대해 `links` 전부를 문자열 split·parse 하고, 면 정보가 없는 옛 연결은 블록 6면 `isPassable` 조회, 지능 필요 책이 있으면 `plugin.magicPower(p)` → `EquipmentBridge.apply` (PDC 읽기 + 체력 속성 수정자 교체) 까지 돈다. 연결 수십 개·접속자 수십 명이면 5초마다 수천 번 블록 조회 + 속성 수정. 지금 규모(연결 적음)에서는 괜찮지만 책 블록이 많아지면 부담. | 보고만. 제안: 세계별 결과를 한 번 만들어 캐시(HouseBoard 처럼 `cache` 맵), `openFace` 결과를 연결 시점에 저장, `magicPower` 대신 `equipmentPower(id, base)` 같은 읽기 전용 값 사용. |
| 중간 | `ChunkClaimBridge` 생성자(`io.submit(...).get(15·20초)`), `PopularityBridge` 생성자(`.get(15초)`) | onEnable 중 메인 스레드가 DB 연결·표 읽기를 최대 35초까지 기다린다. 시작 때 한 번이라 게임 중 렉은 아니지만, 원격 MariaDB(야생 → 기존 호스팅)가 느리면 서버 켜기가 그만큼 늦어진다. 다른 저장소(Audit·Heartbeat)는 기록 스레드에서 열어 메인을 안 막는다. | 보고만. 제안: AuditLog 처럼 io 스레드에서 열고 준비 전에는 "준비 중" 으로 거절. |
| 낮음 | `ChunkClaimBridge.claim` (`p.saveData()`), `PopularityBridge`·`NpcSocialService`·`Appraisal`·`Enhancement`·`Reconfiguration` 등 | 아이템을 떼어 낸 직후 `p.saveData()` 로 플레이어 파일을 메인 스레드에서 동기로 쓴다(복제 방지 목적, 기존 코드 패턴). 한 번에 수십 ms 는 아니지만 "메인 스레드 파일 IO" 범주. | 보고만 (프로젝트 전반의 기존 방식). |
| 낮음 | `AuditLog.accountNames` (ConcurrentHashMap) | 접속한 적 있는 모든 UUID→계정 이름을 서버가 켜져 있는 동안 계속 보관(지우지 않음). 유저 1명당 ~100바이트라 실질 문제 없음. | 보고만. 제안: 퇴장 때 지우고 `nameOf` 는 닉네임 저장소·Bukkit 캐시로 충분. |
| 낮음 | `LessonBridge.lastOpen`, `StatsBridge.likesLoading` | `lastOpen` 은 퇴장 때 안 지움(UUID+Long, 매우 작음). `likesLoading` 은 ChacaHousing 콜백이 한 번이라도 안 오면 그 UUID 의 좋아요 갱신이 영구 중단(현재 HousingStore.async 는 항상 콜백을 주므로 실제로는 안 생김). | 보고만. |
| 낮음 | `DisplayNames.name(OfflinePlayer)` → `NicknameBridge.requestStored` | 닉네임이 **없는** 오프라인 유저 이름을 화면에 계속 그리면(친구 목록 등) 5초마다 그 UUID 의 DB 읽기를 반복한다(저장된 닉이 있으면 20줄에서 바로 반환되어 안 읽음). 5초·UUID 당 1회·`displayReads` 4096 상한이라 폭주는 아님. | 보고만. 제안: 읽어서 "없음" 으로 확인된 UUID 는 45초(새로고침 주기)만큼 더 기다리기. |
| 낮음 | `MegaphoneBridge.isMegaphone` | 모든 우클릭(어떤 아이템이든)마다 `ItemsAdder idOf`(NBT 읽기)를 부른다. ChunkClaim 쪽은 재료(Material)를 먼저 비교해 거른다. 클릭당 한 번이라 미미. | 보고만. 제안: ChunkClaim 처럼 재료 캐시로 먼저 거르기. |
| 낮음 | `ConvenienceCommands`·`MegaphoneBridge` BungeeCord 채널 수신 (`Forward` 하위 채널 ChacaConvenience·ChacaMegaphone) | 서버 간 공지·확성기·`balloon:` ANNOUNCE 후속(기념 상자 지급)이 프록시가 넘겨 준 메시지인지 검증하지 않는다. Velocity 는 클라이언트가 보낸 `bungeecord:main` 을 백엔드로 넘기지 않으므로(프록시가 소비) 위조는 안 되지만, 프록시 없이 직접 접속되는 서버가 있으면 모드 클라가 전 서버 공지·기념 상자 지급을 위조할 수 있다. 디코더 자체는 잘못된 바이트에 예외를 내지 않음(확인). | 보고만. 지금 구성(모든 서버 Velocity 뒤)에서는 영향 없음. |
| 낮음 | `ChunkClaimBridge.claim`·`sell` 의 `main(...)` | 플러그인이 꺼지는 순간(`plugin.isEnabled()==false`) DB 결과 반영 Runnable 이 버려져 `busy` 가 남고, 실패 반환(땅문서 돌려주기)이 안 될 수 있다. 서버 종료 중 수 초 창에서만. DB 가 성공했으면 다음 시작 때 표에서 다시 읽히므로 땅은 안 잃음. | 보고만. |
| 낮음 | `ChunkClaimStore.claim` LIMIT 판정 | COUNT 뒤 INSERT 라 두 서버에서 같은 사람이 동시에 점유하면 최대 수를 1칸 넘길 수 있음(max-claims 가 0=무제한이면 해당 없음). | 보고만. |
| 낮음 | `ArtifactShadows` (MythicMobs 그림자) | 그림자가 CA_Wild 같은 미몹 종류로 다시 소환되므로 야생 스폰 루프의 청크 상한·몹 수 계산에 10초 동안 포함될 수 있고, 그림자 스킬이 소환하는 추가 몹은 태그가 없어 남을 수 있음. 서버 전체 40마리 상한·10초 수명이라 부담은 없음. | 보고만 (설계 확인 필요). |
| 낮음 | `ArtifactShapeshift.overrides()` | 변신 가면 사용마다 `shapeshift.yml` 을 메인 스레드에서 읽는다(작은 파일, 쿨타임 있음). | 보고만. 제안: 한 번 읽고 새로고침 때만 다시. |
| 낮음 | `pass()`(ChunkClaim)·`sendMarks`(Book)·`send`(HouseBoard) 의 `p.getListeningPluginChannels()` | CraftPlayer 는 호출마다 불변 Set 복사본을 만든다. 0.5~5초 주기·접속자 수만큼 작은 GC 부담. | 보고만. |

## 확인했지만 문제 없던 것 (요청 항목 중심)

- **운영 기록/통계 쓰기 경로**: `AuditLog.record/log` 는 `AuditQueue.offer`(짧은 synchronized, 메모리) 만. 기록 스레드가 5초마다 500줄씩 한 트랜잭션. 상한 2만 줄, 넘치면 사소한 종류부터 버림. 통계(`AuditStats.add`)는 ConcurrentHashMap merge 만, 5분·퇴장·종료 때 upsert. 시전마다·NPC 대화마다 DB 쓰기 없음. `read()`(명령) 도 기록 스레드에서 읽고 결과만 메인으로.
- **DiscoveryStats 전달**: 매핑된 14개 키만 `DiscoveryLink.signal` (리플렉션 호출 1회), 비동기 스레드에서 오면 메인으로 넘김. 시전·블록 이벤트에는 안 붙음.
- **청크 점유 블록 이벤트**: `ownerAt` → HashMap 조회 2번. 거절될 때만 닉네임·권한 조회. 물·용암 흐름은 청크가 바뀔 때만 판정. 폭발은 blockList 필터. DB 는 점유·판매·허용 변경·1분마다 허용 명단 읽기만, 전부 io 스레드.
- **땅문서 복제/유실**: 1장을 먼저 떼고(저장) DB 가 거절하면 공통 지급(RewardDelivery)·우편으로 돌려줌. `busy` 로 같은 사람 동시 요청 차단. 판매는 서 있는 청크·주인 확인 후 DB 삭제 줄 수로 판정(두 번 판매 불가). 패킷(`decodeAction`) 은 크기·버전·꼬리 바이트 검사, 실패는 IllegalArgumentException 으로 버림(netty 로 안 올라감).
- **인기도**: 트랜잭션 안에서 대상 점수 FOR UPDATE → 오늘 기록 COUNT FOR UPDATE → INSERT(seq 로 PK) → UPDATE. 해제권은 메인 스레드에서 먼저 떼고 DB 실패·불필요하면 돌려줌(메일 포함). 버튼은 세션당 1초 제한 + `busy`.
- **확성기**: 입력 창 토큰·만료·손 칸 확인 → 금칙어 → 공지 모양 검증 뒤 1개 소모 → 전 서버 전달(Forward ALL, 30000바이트 상한, 최근 256개 id 중복 무시). 아이템 직렬화는 PDC 제거·크기 초과 시 단계적으로 줄임.
- **ServerHeartbeat**: 10초 타이머, 메인에서는 TPS·접속자·월드 수치만, spark·CPU·DB 는 전용 스레드, 앞 쓰기가 안 끝나면 건너뜀(쌓이지 않음). 종료 때 2초 상한.
- **SpellHints 3성 진행도**: `DiscoveryLink.hintProgress` → MagicDiscovery 가 접속 중이면 즉시, 아니면 DB 비동기 후 메인 콜백(`later`). LifeSpells 는 60틱 뒤 진행도 없이 보여 주는 안전장치 + `sent` 플래그로 정확히 한 번. `conditions()` 는 파일 수정 시각이 바뀔 때만 다시 읽음.
- **CombatTag**: 피해 이벤트에서 시각 한 번 기록, 이동 시도 때만 비교. 맵 512 넘으면 정리. 틱 작업 없음.
- **편의 명령 권한**: `adminLiteral(...).requires(admin)` (플레이어면 `isOp()`), `CommandHub.copy` 가 `getRequirement()` 를 그대로 옮김. 유저용 `/도움`·`/쓰레기통` 만 permission default true. `/겜모 0|1`·`/gm` 도 같은 OP 제한. HuskHomes 가 있으면 back/spawn 은 `performCommand` 로 위임(HuskHomes 권한 그대로).
- **Reconfiguration/Enhancement 패킷**: Request 레코드가 token·action·mode 범위를 생성 시 검증, 알 수 없는 토큰은 200ms 제한 안에서 새 화면만 보냄, 처리된 토큰의 재요청은 같은 결과 재전송(두 번 적용 없음). 주문서 차감→결과 교체→저장이 같은 틱.
- **ArtifactShadows/Companions/Motions/MagicRules/Comfort/Broom**: 0.5~1초 타이머는 장착자·그림자(≤40)만 훑고, 나머지는 이벤트에서 맵 조회. 상한(주인 10·서버 40, 초당 echo 6) 있음. 종료 시 그림자 제거·변신 복구.

## 빌드 없이 확인 못 한 것

- 전체 컴파일·JUnit(ChunkClaimTest 수정분 포함) — 여기서는 구문 검사(`javasyntax.sh`)만 통과. 호스팅 `Build-Server.ps1 -Verify` 로 확인 필요.
- MariaDB 에서 `ALTER TABLE codex_chunk_claim DROP PRIMARY KEY, ADD PRIMARY KEY(server,world,cx,cz)` 가 실제 운영 DB(이미 표가 있다면)에서 잠금 없이 끝나는지 — 표가 작아 순간이지만, 야생 서버가 둘 다 켜져 있을 때 한쪽이 먼저 바꾸면 다른 쪽은 `information_schema` 확인에서 이미 바뀐 것을 보고 건너뜀.
- Velocity 가 클라이언트의 `bungeecord:main` 메시지를 백엔드로 넘기지 않는다는 전제(ConvenienceCommands·Megaphone 수신 경로) — 소스로는 확인 불가, 프록시 동작 확인 권장.
