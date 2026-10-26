# 코드 검수 2026-10-26 — bridge-a (아이템 지급·소모 경로)

범위: `70044a79..c8e27c44` 의 MagicCodexBridge 지급·소모 관련 파일 (BagDeathDrops, BagHuskCoordinator, BagPagesBridge, BalloonDonationBridge, BalloonRules,
DrawBoard*, InventorySaveBridge, LootBox*, MailboxBridge, MileageBridge, MoneyPouchRules, ReturnTicketBridge, RewardDelivery, RewardPlan, Travel*, UtilityItemsBridge,
WalletBridge, GenericDatabaseFile, Taming*, FishingBridge, FishShopBridge, AlchemyBridge, QuestBridge, PetBridge, PetCompanions, CompanionMath, Shop*, Artifact*)
+ 프로토콜 TeleportPickProtocol, DrawBoardProtocol, LootBoxProtocol, TarotProtocol, MegaphoneProtocol, ShopProtocol.

코드를 처음 보는 사람 시점으로 읽었고, 빌드(Gradle)는 이 환경에 없어 문법 검사(javac parse)만 했다.

## 결과 요약

- 치명: 없음
- 높음: 1건 → 고침 (상점 판매·교환이 배낭 쪽 넘기는 중에도 27칸에서 아이템을 뺌 → 복제 가능)
- 중간·낮음: 보고만 (아래 표)

## 찾은 것

| 심각도 | 파일:줄 | 무엇이 깨지나 | 조치 |
|---|---|---|---|
| 높음 | `ShopBridge.java:134~143` (trade) | 판매·교환(#98 새 기능)은 인벤토리 36칸 전부에서 아이템을 뺀다. 아공간 배낭 쪽 넘기기(BagHuskCoordinator.switchPage)는 시작할 때 지금 쪽(9~35칸)을 그대로 공유 DB 에 적고, CAS 뒤 9~35칸을 새 쪽으로 덮는다. 그 사이(1~2틱, 클라가 SWITCH 패킷 직후 BUY/SELL 패킷을 보내면 재현)에 판매·교환으로 9~35칸의 아이템을 빼고 저장하면, 뺀 아이템이 옛 쪽에 그대로 남아 다시 넘기면 돌아온다 = **복제**. 연금술·의뢰·우편 수령·뽑기 상자는 이번 패치에서 `RewardDelivery.mainUsable` 로 막았는데 상점만 빠짐 (판매는 예전부터 있던 구멍, 교환은 새로 추가됨). | **고침**: trade() 들어갈 때와 DB 접수 뒤 콜백에서 `RewardDelivery.mainUsable(plugin,p)` 가 아니면 거절 ("배낭 쪽을 넘기는 중이에요"). 배낭 없는 서버는 항상 true 라 동작 그대로. |
| 중간 | `RewardDelivery.java:112,126,142` · `LootBoxBridge.java:285` · `TravelItemsBridge.java:189` · `ShopBridge.java:160` · `BagHuskCoordinator.java:264,270~273` | 지급·개봉·사망 드롭 한 건마다 메인 스레드에서 `saveData()`(플레이어 .dat 쓰기) 또는 fsync 파일 쓰기(우편 대기 파일·영수증·증거 파일)를 한다. 틱마다가 아니라 행동 1회당 1~2번이고, 기존 우편 수령·귀환권·쪽 넘기기와 같은 "영수증을 같은 틱에 디스크에" 설계라 의도된 것. 다만 한 틱에 여러 명이 동시에 열면 그만큼 디스크 대기가 쌓인다. | 보고만. 디스크가 느린 호스팅이면 LootBox 10개 열기 연타 시 틱 지연을 보고 판단. |
| 중간 | `LootBoxBridge.java:435~450` · `RewardDelivery.java:196~209` | 시작 때 `recover()` 가 메인 스레드에서 영수증 폴더와 오프라인 플레이어 `.dat` 파일을 읽는다(`DeliveryReceipt.saved`). 끝나지 않은 개봉 수만큼이라 보통 0~몇 건. | 보고만 (시작 1회). |
| 중간 | `DrawBoardBridge.java:66`, `close()` | 생성자에서 DB 연결을 메인 스레드에서 최대 15초 기다리고(`.get(15s)`), 끄기에서 10초 기다린다. 플러그인 켜기/끄기 때 1회. DB 가 죽어 있으면 서버 시작이 15초 늦어진다. | 보고만. |
| 중간 | `BalloonDonationBridge.java:157~158` | `here()` 검사보다 먼저 이벤트를 취소하므로 donation.yml servers 에 없는 서버(아레나)에서 받은 후원은 보상도 없고 donation 플러그인의 `api` 콘솔 명령도 안 돈다. 주석에 "취소만 하고 보상 없음"이라 적혀 있어 의도로 보이지만, 아레나에서 받은 후원이 조용히 사라진다(로그 1줄). | 오너 확인: 아레나 후원을 버릴지, 학교 쪽으로 넘길지. |
| 중간 | `TravelBridge.java:199~202` (deathReturn) | `deathMoves` 를 20틱 뒤에 지우는데, 표(ticket) DB 쓰기가 1초 넘게 걸리면 `go()` 콜백의 `refusal(...noCooldown=deathMoves.contains(id))` 가 쿨타임을 다시 보고 사망 귀환을 거절할 수 있다. 재시도 5번(10초) 안에 보통 풀리지만 DB 느릴 때 "학교로 돌아가지 못했어요"가 날 수 있다. | 보고만. 고치려면 go() 콜백에서 지우기. |
| 낮음 | `InventorySaveBridge.java:55` | `FORCED` 가 static HashSet — 메인 스레드 전용이라 문제 없지만 `forcedDeath` 가 다른 스레드에서 불리면 바로 예외. 지금 호출처(BalloonDonationBridge)는 메인 스레드 보장 확인함 (donation 플러그인은 BukkitRunnable 타이머에서 이벤트를 부름 — jar 확인). | 없음. |
| 낮음 | `LootBoxStore.java:343` (matches) | 열쇠가 `item:`(base64) 방식이면 우클릭·개봉 때마다 base64 → ItemStack 역직렬화(개봉 때 36칸 × 1회). ItemsAdder id 열쇠는 해당 없음. | 보고만 (작음). |
| 낮음 | `PetCompanions.apply()` → `PetBridge.collection()` | 효과 적용 때마다 모든 펫에 대해 `hasPermission` (동행 펫 + MCPets 목록). 접속·꺼내기·경기 상태 바뀔 때만이라 작음. | 없음. |
| 낮음 | `UtilityItemsBridge.useOn` | 손에 재화 주머니·힌트권을 들고 NPC/아무 엔티티를 우클릭해도 아이템이 쓰인다(변색종 변경권만 몹 대상). 400ms 게이트로 중복 사용은 막힘. | 오너 취향. |

## 특별히 본 것 (문제 없음으로 확인)

- **빼고-주기 경로**
  - 뽑기 상자(LootBoxBridge): 추첨 → 영수증 파일(IO 스레드) → 같은 틱에 `before` 인벤토리 바이트 비교 + 칸 판정(usable) 재확인 → 열쇠 차감·보상 넣기·PDC 영수증·saveData → 연출 → 남은 것 우편(source `lootbox:<op>:<part>` UNIQUE). 재시작·재접속 복구는 영수증 유무로만 판정, 재추첨 없음. 열쇠는 `takePlan` 이 핫바/잠금 해제된 쪽에서만 뺌. 복제·유실 경로 못 찾음.
  - 사망 배낭 드롭(InventorySaveBridge → BagHuskCoordinator.dropOnDeath): 증거 파일 fsync → beginSwitch/lockPlayer → WAL+영수증 → lease/head/seq CAS → **CAS 된 뒤에만** 떨어뜨림(적용 실패해도 떨어뜨림, 청크 비동기 로드, 세계 없으면 우편). CAS 실패면 DB 그대로. WAL 자동 재생 없음(수동). 복제 없음. 드롭 중 쪽 넘기기·정리·서버 이동(TravelBridge.refusal deathBusy) 막힘.
  - 후원 사망(forcedDeath): LOWEST 에서 keepInventory 끄고 drops 비었으면 인벤토리 복제본을 채움 → 바닐라가 drops 를 떨어뜨리고 keepInventory=false 라 인벤토리를 비움. 경기 중은 제외. 인벤세이브 소모 안 함.
  - 리턴권·텔포권(TravelItemsBridge): 먼저 1개 빼고 saveData → 이동. 환불은 `itemMoving`(출발 서버, 1회 제거) 또는 도착 서버(새 아이템)에서 정확히 한 번. 퇴장 시 remote 이동이면 출발 쪽 환불 포기 → 도착/만료가 처리. 이중 환불 경로 못 찾음.
  - 귀환권(ReturnTicketBridge)·배낭 확장권 환불·주머니: 공통 지급 함수로 바뀜, 호출 1회당 source 가 무작위라 그 호출이 한 번만 일어나는지 확인 → 모두 1회.
- **우편 source_key 고유성**: `lootbox:<op>:<part>`, `balloon:<op>:<part>`, `balloon-all:<op>:<uuid>:<part>`(여러 서버가 같은 키 → 한 통), `bag-death-drop:<uuid>:<증거파일명>`, `reward` 쪽은 호출자가 `…:<uuid>:<random>` — 모두 고유. 재전송은 같은 키로만.
- **DB 스레드**: DrawBoardStore 는 전용 1스레드만 호출, 모든 변경이 `UPDATE … WHERE revision=?` CAS + 트랜잭션(아이템 INSERT 도 같은 거래, 실패 시 롤백). `finishRound` 는 끝난 판의 revision 일 때만 → 여러 서버 동시 시도해도 1회. TravelStore presence/death 는 IO 스레드. MileageBridge.credit 은 기존 work 경로. BagHuskCoordinator 는 기존 io 스레드(`codex_bag_unlock` 조회 포함).
- **패킷 디코더**: TeleportPick·DrawBoard·LootBox·Tarot·Megaphone·Shop 모두 길이 상한·범위·trailing 검사 + `IOException|RuntimeException` → `IllegalArgumentException` 으로 감싸고, 수신 쪽은 전부 `catch(IllegalArgumentException)` 후 무시. slot 은 -1~35 로 제한(인벤토리 getItem 범위 안). 관리 패킷은 OP/권한 + 세션 토큰 + 순번 검사.
- **메모리**: 새 맵·집합은 전부 퇴장 때 지우거나(LootBox cooldown/admins, DrawBoard viewers/rate, TravelItems taken/asking/pending, Pet owners/applied/lastFire) 상한이 있음(Balloon granted 5000, LootBox/Reward pending 은 처리되면 삭제·실패분은 관리자 확인용). 매 틱 작업 없음(DrawBoard 1초 폴링은 창 연 사람 있을 때만, PetCompanions 1초 작업은 펫 꺼낸 사람만).

## 빌드 없이 확인 못 한 것

- `BagPageCodec.Journal.save(owner,server,active,seq,pages)` 5인자 오버로드 존재 확인함(78행). `AuditLog.사망/후원/뽑기상자` 상수 존재 확인함. 그 밖의 새 심볼(`CombatTag.refuse`, `ChacaApi.stat`, `plugin.petFishing`, `AuditText.clip`)은 다른 검수 구역 파일이라 컴파일로 확인 필요.
- Paper 사망 흐름(keepInventory=false 로 바꾸면 이벤트 뒤 인벤토리를 비움)은 CraftBukkit 소스 기억에 의존 — 후원 사망을 테스트 서버에서 한 번 실제로 죽여 보는 것을 권함 (인벤토리가 남아 있으면 복제).
- Gradle 빌드·JUnit(RewardPlan/LootBoxRules/DrawBoardRules 테스트)은 호스팅에서.

## 커밋

- `상점 판매·교환: 배낭 쪽 넘기는 중이면 거절 (27칸 복제 구멍)` — ShopBridge.java 2줄.
